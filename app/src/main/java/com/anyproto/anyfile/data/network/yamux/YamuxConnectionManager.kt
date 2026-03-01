package com.anyproto.anyfile.data.network.yamux

import com.anyproto.anyfile.data.network.handshake.AnySyncHandshake
import com.anyproto.anyfile.data.network.handshake.CredentialChecker
import com.anyproto.anyfile.data.network.handshake.PeerSignVerifier
import com.anyproto.anyfile.data.network.handshake.SecureSession
import com.anyproto.anyfile.data.network.libp2p.Libp2pTlsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext

/**
 * Manages yamux connections to multiple endpoints with session caching.
 *
 * The YamuxConnectionManager provides a singleton interface for managing
 * yamux connections to coordinators, filenodes, and other peers. It caches
 * active sessions keyed by "host:port" to enable connection reuse.
 *
 * Features:
 * - Session caching by "host:port" key
 * - Thread-safe session management using ConcurrentHashMap
 * - Connection health checks via ping
 * - Graceful cleanup of all sessions
 * - Coroutine scope for async operations
 *
 * Based on the Go implementation in any-file/internal/p2p/yamux_client.go
 *
 * Example usage:
 * ```kotlin
 * class CoordinatorClient @Inject constructor(
 *     private val connectionManager: YamuxConnectionManager,
 * ) {
 *     private var session: YamuxSession? = null
 *
 *     suspend fun initialize(host: String, port: Int) {
 *         session = connectionManager.getSession(host, port)
 *         session.start()
 *     }
 * }
 * ```
 *
 * @property tlsProvider Provider for libp2p TLS socket configuration
 */
@Singleton
class YamuxConnectionManager @Inject constructor(
    private val tlsProvider: Libp2pTlsProvider
) : CoroutineScope {

    companion object {
        /**
         * Default connection timeout in milliseconds.
         */
        private const val DEFAULT_CONNECTION_TIMEOUT_MS = 30000L

        /**
         * Default ping timeout for health checks in milliseconds.
         */
        private const val DEFAULT_PING_TIMEOUT_MS = 5000L

        /**
         * Maximum number of cached sessions.
         */
        private const val MAX_CACHED_SESSIONS = 100
    }

    /**
     * Coroutine context for async operations.
     */
    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.IO

    /**
     * Credential checker for any-sync handshake (SignedPeerIds mode).
     * Created lazily to ensure peer identity is available.
     */
    private val credentialChecker: CredentialChecker by lazy {
        val identity = tlsProvider.getPeerIdentity()
        PeerSignVerifier(
            localKeyPair = identity.keyPair,
            localPeerId = identity.peerId
        )
    }

    /**
     * Cached sessions keyed by "host:port".
     * Using ConcurrentHashMap for thread-safe access.
     */
    private val sessionCache = ConcurrentHashMap<String, CachedSession>()

    /**
     * Mutex for atomic cache operations.
     */
    private val cacheMutex = Mutex()

    /**
     * Data class wrapping a YamuxSession with metadata.
     *
     * @property session The yamux session
     * @property host The remote host
     * @property port The remote port
     * @property createdAt Timestamp when session was created
     */
    private data class CachedSession(
        val session: YamuxSession,
        val host: String,
        val port: Int,
        val createdAt: Long = System.currentTimeMillis()
    )

    /**
     * Get or create a yamux session for the specified host and port.
     *
     * This method:
     * 1. Checks if a session exists in the cache
     * 2. If cached, verifies it's still healthy
     * 3. If not cached or unhealthy, creates a new session
     * 4. Starts the session frame reader
     *
     * @param host The hostname or IP address
     * @param port The port number
     * @param timeoutMs Connection timeout in milliseconds
     * @return A started YamuxSession ready for use
     * @throws YamuxConnectionException if connection fails
     */
    suspend fun getSession(
        host: String,
        port: Int,
        timeoutMs: Long = DEFAULT_CONNECTION_TIMEOUT_MS
    ): YamuxSession {
        val key = createSessionKey(host, port)

        // Check cache for existing session
        val existingSession = sessionCache[key]
        if (existingSession != null) {
            // Verify session is still healthy
            if (isSessionHealthy(existingSession.session)) {
                return existingSession.session
            } else {
                // Remove unhealthy session
                removeSession(key, existingSession.session)
            }
        }

        // Create new session
        return withContext(Dispatchers.IO) {
            try {
                // Create TLS socket with libp2p peer identity
                val libp2pSocket = tlsProvider.createTlsSocket(
                    host = host,
                    port = port,
                    timeoutMs = timeoutMs.toInt(),
                    enableAlpn = false,      // Disable ALPN for compatibility
                    trustAllCerts = false,    // Don't use trust-all
                    useLibp2pTls = true       // Use libp2p TLS with client cert
                )

                // Perform any-sync handshake
                val secureSession = try {
                    val result = AnySyncHandshake.performOutgoingHandshake(
                        socket = libp2pSocket,
                        checker = credentialChecker,
                        timeoutMs = timeoutMs
                    )
                    // Wrap in SecureSession with peer information
                    SecureSession.fromHandshake(libp2pSocket, result)
                } catch (e: Exception) {
                    libp2pSocket.socket.close()
                    throw YamuxConnectionException("Handshake failed with $host:$port", e)
                }

                // Create yamux session (client mode)
                // YamuxSession uses the underlying SSLSocket from the authenticated session
                val session = YamuxSession(secureSession.socket.socket, isClient = true)

                // Cache the session before starting to prevent race conditions
                cacheSession(key, session, host, port)

                // Start the session (begins frame reading)
                session.start()

                session
            } catch (e: Exception) {
                throw YamuxConnectionException(
                    "Failed to connect to $host:$port",
                    e
                )
            }
        }
    }

    /**
     * Get an existing session without creating a new one.
     *
     * Note: This method returns the cached session without performing health checks.
     * For health-checked sessions, use getSession() which will verify and recreate if needed.
     *
     * @param host The hostname or IP address
     * @param port The port number
     * @return The cached session, or null if not found
     */
    fun getCachedSession(host: String, port: Int): YamuxSession? {
        val key = createSessionKey(host, port)
        val cached = sessionCache[key]
        return cached?.session?.takeIf { it.isActive() }
    }

    /**
     * Check if a session to the specified endpoint exists and is active.
     *
     * @param host The hostname or IP address
     * @param port The port number
     * @return true if an active session exists
     */
    fun hasSession(host: String, port: Int): Boolean {
        return getCachedSession(host, port) != null
    }

    /**
     * Close a specific session.
     *
     * @param host The hostname or IP address
     * @param port The port number
     */
    suspend fun closeSession(host: String, port: Int) {
        val key = createSessionKey(host, port)
        val cached = sessionCache.remove(key)
        cached?.session?.close()
    }

    /**
     * Close all cached sessions.
     *
     * This should be called during application shutdown to ensure
     * all connections are properly closed.
     */
    suspend fun closeAll() {
        val sessionsToClose = sessionCache.values.toList()
        sessionCache.clear()

        sessionsToClose.forEach { cached ->
            try {
                cached.session.close()
            } catch (e: Exception) {
                // Ignore close errors during shutdown
            }
        }

        // Cancel coroutine context
        coroutineContext.cancel()
    }

    /**
     * Get the number of active cached sessions.
     *
     * @return The number of sessions in the cache
     */
    fun getSessionCount(): Int {
        return sessionCache.size
    }

    /**
     * Get all cached session endpoints.
     *
     * @return List of "host:port" strings for cached sessions
     */
    fun getCachedEndpoints(): List<String> {
        return sessionCache.keys.toList()
    }

    /**
     * Check health of a session by sending a ping.
     *
     * @param session The session to check
     * @return true if session is healthy, false otherwise
     */
    private suspend fun isSessionHealthy(session: YamuxSession): Boolean {
        return try {
            if (!session.isActive()) {
                return false
            }

            // Send ping to verify connection
            val rtt = session.ping(timeoutMs = DEFAULT_PING_TIMEOUT_MS)
            rtt != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Cache a session with its metadata.
     *
     * @param key The session key ("host:port")
     * @param session The yamux session
     * @param host The remote host
     * @param port The remote port
     */
    private suspend fun cacheSession(key: String, session: YamuxSession, host: String, port: Int) {
        // Atomically ensure space exists before inserting
        cacheMutex.withLock {
            while (sessionCache.size >= MAX_CACHED_SESSIONS) {
                val oldestKey = sessionCache.entries
                    .minByOrNull { it.value.createdAt }?.key
                if (oldestKey == null) break // Shouldn't happen when size > 0
                val removed = sessionCache.remove(oldestKey)
                removed?.session?.close()
            }
            sessionCache[key] = CachedSession(session, host, port, System.currentTimeMillis())
        }
    }

    /**
     * Remove a session from the cache and close it.
     *
     * @param key The session key
     * @param session The session to close
     */
    private suspend fun removeSession(key: String, session: YamuxSession) {
        sessionCache.remove(key)
        try {
            session.close()
        } catch (e: Exception) {
            // Ignore close errors
        }
    }

    /**
     * Create a session key from host and port.
     *
     * @param host The hostname or IP address
     * @param port The port number
     * @return The session key in format "host:port"
     */
    private fun createSessionKey(host: String, port: Int): String {
        return "$host:$port"
    }
}

/**
 * Exception thrown when yamux connection operations fail.
 */
class YamuxConnectionException(message: String, cause: Throwable? = null) : Exception(message, cause)
