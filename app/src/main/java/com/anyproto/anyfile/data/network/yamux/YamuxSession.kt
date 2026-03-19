package com.anyproto.anyfile.data.network.yamux

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.CoroutineContext

/**
 * Manages a yamux session over a TLS socket.
 *
 * A yamux session represents a multiplexed connection over a single TCP socket.
 * It manages multiple virtual streams that operate concurrently over the shared connection.
 *
 * Session responsibilities:
 * - Create new streams via openStream()
 * - Accept incoming streams from the remote
 * - Route incoming frames to appropriate streams
 * - Manage session lifecycle (active, closing, closed)
 * - Handle session-level frames (PING, GO_AWAY)
 * - Use coroutines for async frame reading
 *
 * Stream ID allocation:
 * - Client-initiated streams use odd IDs: 1, 3, 5, 7, ...
 * - Server-initiated streams use even IDs: 2, 4, 6, 8, ...
 *
 * @property socket The underlying socket (raw TCP after handshake, per Go any-sync behavior)
 * @property isClient Whether this session acts as a client (true) or server (false)
 * @property scope Coroutine scope for async operations
 */
class YamuxSession(
    private val socket: Socket,
    private val isClient: Boolean = true,
    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.IO
) : CoroutineScope {

    companion object {
        private const val TAG = "YamuxSession"

        /**
         * Maximum number of concurrent streams per session.
         */
        const val MAX_STREAMS = 1024

        /**
         * Default keep-alive interval in milliseconds.
         */
        const val DEFAULT_KEEP_ALIVE_INTERVAL_MS = 30000L

        /**
         * Connection read timeout in milliseconds.
         */
        private const val READ_TIMEOUT_MS = 60000L

        /**
         * Initial stream ID for clients (odd).
         */
        private const val CLIENT_INITIAL_STREAM_ID = 1

        /**
         * Initial stream ID for servers (even).
         */
        private const val SERVER_INITIAL_STREAM_ID = 2

        /**
         * Stream ID increment (2 to maintain odd/even separation).
         */
        private const val STREAM_ID_INCREMENT = 2

        /**
         * Create a YamuxSession from a SecureSession (after handshake).
         *
         * @param secureSession The authenticated session from handshake
         * @return A new YamuxSession wrapping the authenticated socket
         */
        fun fromSecureSession(secureSession: com.anyproto.anyfile.data.network.handshake.SecureSession): YamuxSession {
            // Use raw TCP socket (not SSLSocket) to match Go any-sync: yamux runs on raw TCP
            val rawSocket = secureSession.socket.rawSocket ?: secureSession.socket.socket
            return YamuxSession(
                socket = rawSocket,
                isClient = true
            )
        }
    }

    /**
     * Session state enumeration.
     */
    enum class State {
        ACTIVE,    // Session is active and can create/accept streams
        DRAINING,  // Session is closing, no new streams allowed
        CLOSED     // Session is closed
    }

    // Session state
    private val lock = ReentrantLock()
    private var _state = State.ACTIVE
    val state: State get() = _state

    // Stream ID management
    private var nextStreamId = if (isClient) CLIENT_INITIAL_STREAM_ID else SERVER_INITIAL_STREAM_ID

    // Active streams - using regular map with mutex for simplicity
    private val streamsMap = mutableMapOf<Int, YamuxStream>()

    // Pending incoming streams (waiting to be accepted)
    private val pendingStreamsChannel = Channel<YamuxStream>(capacity = Channel.UNLIMITED)

    // I/O streams
    private val inputStream: InputStream = socket.getInputStream()
    private val outputStream: OutputStream = socket.getOutputStream()

    // Frame reading job
    private var frameReaderJob: Job? = null

    // Ping handling
    private val pendingPingsMap = mutableMapOf<Int, PingHandler>()

    /**
     * Data class for tracking ping operations.
     */
    private data class PingHandler(
        val responseChannel: Channel<YamuxFrame.Ping>,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Start the yamux session.
     * Begins reading frames from the network in the background.
     */
    suspend fun start() {
        lock.withLock {
            check(_state == State.ACTIVE) { "Session already started or closed" }
        }

        Log.d(TAG, "=== Starting YamuxSession ===")
        Log.d(TAG, "Mode: ${if (isClient) "Client" else "Server"}")
        Log.d(TAG, "Remote: ${socket.inetAddress?.hostAddress}:${socket.port}")

        // Start frame reader job
        frameReaderJob = launch {
            try {
                readFramesLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Frame reader loop failed", e)
                throw e
            }
        }

        Log.d(TAG, "YamuxSession started, frame reader running")
    }

    /**
     * Open a new stream as the initiator.
     *
     * @return The newly opened stream
     * @throws YamuxSessionException if session is closed or max streams reached
     */
    suspend fun openStream(): YamuxStream {
        lock.withLock {
            check(_state == State.ACTIVE) {
                Log.e(TAG, "Cannot open stream: session is in state ${_state}")
                throw YamuxSessionException("Cannot open stream in state ${_state}")
            }
            check(streamsMap.size < MAX_STREAMS) {
                Log.e(TAG, "Cannot open stream: max streams limit reached: $MAX_STREAMS")
                throw YamuxSessionException("Maximum streams limit reached: $MAX_STREAMS")
            }
        }

        // Allocate stream ID
        val streamId = lock.withLock { nextStreamId }.also {
            lock.withLock { nextStreamId += STREAM_ID_INCREMENT }
        }

        Log.d(TAG, "Opening new stream: streamId=$streamId")

        // Create stream
        val stream = YamuxStream(streamId, this@YamuxSession)
        lock.withLock { streamsMap[streamId] = stream }

        // Initialize stream (sends SYN)
        try {
            stream.initialize()
            Log.d(TAG, "Stream opened successfully: streamId=$streamId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize stream: streamId=$streamId", e)
            lock.withLock { streamsMap.remove(streamId) }
            throw e
        }

        return stream
    }

    /**
     * Accept the next incoming stream.
     * Suspends until a stream is available.
     *
     * @return The accepted stream
     * @throws YamuxSessionException if session is closed
     */
    suspend fun acceptStream(): YamuxStream {
        while (true) {
            lock.withLock {
                check(_state == State.ACTIVE || _state == State.DRAINING) {
                    throw YamuxSessionException("Cannot accept stream in state ${_state}")
                }
            }

            val stream = pendingStreamsChannel.receive()
            if (!stream.isClosed()) {
                return stream
            }
        }
    }

    /**
     * Try to accept an incoming stream without blocking.
     *
     * @return The accepted stream, or null if no stream is available
     */
    suspend fun tryAcceptStream(): YamuxStream? {
        return try {
            pendingStreamsChannel.tryReceive().getOrNull()?.takeIf { !it.isClosed() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Send a ping to the remote.
     *
     * @param timeoutMs Timeout for waiting for pong response (0 = no wait)
     * @return The round-trip time in milliseconds, or null if timeout
     * @throws YamuxSessionException if session is closed
     */
    suspend fun ping(timeoutMs: Long = 0): Long? {
        val pingId = generatePingId()
        val responseChannel = Channel<YamuxFrame.Ping>(capacity = 1)

        lock.withLock {
            pendingPingsMap[pingId] = PingHandler(responseChannel)
        }

        // Send ping
        val pingFrame = YamuxFrame.Ping(
            flags = setOf(YamuxFrame.Flag.SYN),
            value = pingId
        )
        sendFrame(pingFrame)

        return try {
            val start = System.currentTimeMillis()
            if (timeoutMs > 0) {
                kotlinx.coroutines.withTimeout(timeoutMs) {
                    responseChannel.receive()
                }
            } else {
                responseChannel.receive()
            }
            System.currentTimeMillis() - start
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            lock.withLock { pendingPingsMap.remove(pingId) }
            null
        }
    }

    /**
     * Close the session gracefully.
     * Sends a GO_AWAY frame and closes all streams.
     *
     * @param errorCode The error code for the close reason
     */
    suspend fun close(errorCode: YamuxFrame.GoAwayErrorCode = YamuxFrame.GoAwayErrorCode.NORMAL_TERMINATION) {
        lock.withLock {
            if (_state == State.CLOSED) return
            _state = State.CLOSED
        }

        // Send GO_AWAY frame
        try {
            val goAwayFrame = YamuxFrame.GoAway(
                flags = emptySet(),
                errorCode = errorCode
            )
            YamuxProtocol.writeFrame(outputStream, goAwayFrame)
        } catch (e: Exception) {
            // Ignore errors when sending GO_AWAY
        }

        // Close all streams
        val streamsToClose = lock.withLock { streamsMap.values.toList() }
        streamsToClose.forEach { stream ->
            launch {
                stream.close()
            }
        }
        lock.withLock { streamsMap.clear() }

        // Cancel frame reader
        frameReaderJob?.cancel()

        // Close socket
        try {
            socket.close()
        } catch (e: Exception) {
            // Ignore close errors
        }

        // Close pending streams channel
        pendingStreamsChannel.close()
    }

    /**
     * Send a frame on the session.
     * Internal API used by streams.
     *
     * @param frame The frame to send
     */
    internal suspend fun sendFrame(frame: YamuxFrame) {
        lock.withLock {
            check(_state != State.CLOSED) {
                throw YamuxSessionException("Session is closed")
            }
        }

        withContext(Dispatchers.IO) {
            YamuxProtocol.writeFrame(outputStream, frame)
        }
    }

    /**
     * Remove a closed stream from the session.
     * Internal API used by streams.
     *
     * @param streamId The stream ID to remove
     */
    internal suspend fun removeStream(streamId: Int) {
        lock.withLock {
            streamsMap.remove(streamId)
        }
    }

    /**
     * Get the number of active streams.
     */
    fun getStreamCount(): Int {
        return lock.withLock { streamsMap.size }
    }

    /**
     * Get a stream by ID.
     *
     * @param streamId The stream ID
     * @return The stream, or null if not found
     */
    fun getStream(streamId: Int): YamuxStream? {
        return lock.withLock { streamsMap[streamId] }
    }

    /**
     * Check if the session is active.
     */
    fun isActive(): Boolean {
        return lock.withLock { _state == State.ACTIVE }
    }

    /**
     * Main frame reading loop.
     * Runs in a coroutine and reads frames continuously.
     */
    private suspend fun readFramesLoop() {
        try {
            socket.soTimeout = READ_TIMEOUT_MS.toInt()

            while (isActive && _state != State.CLOSED) {
                try {
                    val frame = withContext(Dispatchers.IO) {
                        YamuxProtocol.readFrame(inputStream)
                    }
                    handleFrame(frame)
                } catch (e: SocketException) {
                    if (_state != State.CLOSED) {
                        throw YamuxSessionException("Socket closed unexpectedly", e)
                    }
                    break
                } catch (e: YamuxProtocolException) {
                    // Protocol error - close session
                    close(YamuxFrame.GoAwayErrorCode.PROTOCOL_ERROR)
                    throw e
                }
            }
        } catch (e: Exception) {
            // Fatal error - close session
            close(YamuxFrame.GoAwayErrorCode.INTERNAL_ERROR)
        }
    }

    /**
     * Handle an incoming frame.
     * Routes frames to appropriate streams or handles session-level frames.
     *
     * @param frame The frame to handle
     */
    private suspend fun handleFrame(frame: YamuxFrame) {
        when (frame) {
            is YamuxFrame.Data -> handleDataFrame(frame)
            is YamuxFrame.WindowUpdate -> handleWindowUpdateFrame(frame)
            is YamuxFrame.Ping -> handlePingFrame(frame)
            is YamuxFrame.GoAway -> handleGoAwayFrame(frame)
        }
    }

    /**
     * Handle a DATA frame.
     * Routes to the appropriate stream or creates a new stream.
     */
    private suspend fun handleDataFrame(frame: YamuxFrame.Data) {
        val streamId = frame.streamId

        when {
            // Check for RST flag
            frame.flags.contains(YamuxFrame.Flag.RST) -> {
                lock.withLock { streamsMap[streamId] }?.handleReset()
            }
            // New incoming stream (SYN without ACK)
            frame.flags.contains(YamuxFrame.Flag.SYN) &&
                !frame.flags.contains(YamuxFrame.Flag.ACK) -> {
                handleNewStream(frame)
            }
            // Existing stream
            lock.withLock { streamsMap.containsKey(streamId) } -> {
                lock.withLock { streamsMap[streamId] }?.handleDataFrame(frame)
            }
            // Stream not found
            else -> {
                // Send RST for unknown stream
                sendReset(streamId)
            }
        }
    }

    /**
     * Handle a new incoming stream (SYN without ACK).
     */
    private suspend fun handleNewStream(frame: YamuxFrame.Data) {
        lock.withLock {
            if (_state == State.CLOSED) return
            if (streamsMap.size >= MAX_STREAMS) {
                // Max streams reached, send RST
                launch { sendReset(frame.streamId) }
                return
            }

            // Create new stream
            val stream = YamuxStream(frame.streamId, this@YamuxSession)
            streamsMap[frame.streamId] = stream
        }

        // Accept the stream (sends ACK)
        val stream = getStream(frame.streamId) ?: return
        launch { stream.accept() }

        // Queue for application to accept
        launch {
            pendingStreamsChannel.send(stream)
        }
    }

    /**
     * Handle a WINDOW_UPDATE frame.
     *
     * Go's yamux server (hashicorp/yamux) acknowledges a client SYN by sending
     * typeWindowUpdate|flagACK, NOT typeData|flagACK. We must detect this and
     * transition the stream from SYN_SENT → OPEN so waitForOpen() unblocks.
     */
    private suspend fun handleWindowUpdateFrame(frame: YamuxFrame.WindowUpdate) {
        val streamId = frame.streamId

        if (streamId == 0) {
            // Session-level window update (not implemented in this version)
            return
        }

        val stream = lock.withLock { streamsMap[streamId] } ?: return
        if (frame.flags.contains(YamuxFrame.Flag.ACK)) {
            // SYN-ACK: transition stream SYN_SENT → OPEN
            stream.handleWindowUpdateAck(frame.delta)
        } else {
            stream.handleWindowUpdate(frame.delta)
        }
    }

    /**
     * Handle a PING frame.
     */
    private suspend fun handlePingFrame(frame: YamuxFrame.Ping) {
        when {
            frame.flags.contains(YamuxFrame.Flag.SYN) -> {
                // Ping request - send pong (ACK)
                val pongFrame = YamuxFrame.Ping(
                    flags = setOf(YamuxFrame.Flag.ACK),
                    value = frame.value
                )
                sendFrame(pongFrame)
            }
            frame.flags.contains(YamuxFrame.Flag.ACK) -> {
                // Pong response
                lock.withLock {
                    pendingPingsMap[frame.value]?.responseChannel?.trySend(frame)
                    pendingPingsMap.remove(frame.value)
                }
            }
        }
    }

    /**
     * Handle a GO_AWAY frame.
     */
    private suspend fun handleGoAwayFrame(frame: YamuxFrame.GoAway) {
        close(YamuxFrame.GoAwayErrorCode.RECEIVED_GO_AWAY)
    }

    /**
     * Send a RST frame for a stream.
     */
    private suspend fun sendReset(streamId: Int) {
        val rstFrame = YamuxFrame.Data(
            streamId = streamId,
            flags = setOf(YamuxFrame.Flag.RST),
            data = ByteArray(0)
        )
        try {
            sendFrame(rstFrame)
        } catch (e: Exception) {
            // Ignore errors
        }
    }

    /**
     * Generate a unique ping ID.
     */
    private fun generatePingId(): Int {
        return (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
    }
}

/**
 * Exception thrown when yamux session operations fail.
 */
class YamuxSessionException(message: String, cause: Throwable? = null) : Exception(message, cause)
