package com.anyproto.anyfile.data.network.tls

import com.anyproto.anyfile.util.AnyfileException
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * TLS configuration provider for creating secure sockets.
 *
 * This provider creates TLS sockets configured for TLS 1.3 protocol,
 * with support for localhost certificate validation for testing purposes.
 *
 * The implementation follows Android's SSLSocket API and provides
 * secure connections to any-sync services (coordinator, filenode).
 *
 * Based on the Go implementation in any-sync/net/transport/quic which uses
 * libp2p TLS 1.3 for secure peer-to-peer connections.
 */
@Singleton
class TlsConfigProvider @Inject constructor() {

    companion object {
        /**
         * Default connection timeout in milliseconds.
         */
        private const val DEFAULT_CONNECTION_TIMEOUT_MS = 30000

        /**
         * Default read timeout in milliseconds.
         */
        private const val DEFAULT_READ_TIMEOUT_MS = 30000

        /**
         * TLS 1.3 protocol name.
         */
        private const val TLS_PROTOCOL_1_3 = "TLSv1.3"

        /**
         * Fallback to TLS 1.2 if TLS 1.3 is not available.
         */
        private const val TLS_PROTOCOL_1_2 = "TLSv1.2"

        /**
         * Application layer protocol negotiation for any-sync.
         */
        private const val ALPN_PROTO_ANY_SYNC = "anysync"
    }

    /**
     * The SSL socket factory used to create TLS sockets.
     * Lazily initialized with proper TLS configuration.
     */
    private val sslSocketFactory: SSLSocketFactory by lazy {
        createSslSocketFactory()
    }

    /**
     * Create a TLS socket connected to the specified host and port.
     *
     * @param host The hostname to connect to
     * @param port The port number
     * @param timeoutMs Connection timeout in milliseconds (default: 30000)
     * @return Configured SSLSocket ready for use
     * @throws TlsConnectionException if connection fails or TLS handshake fails
     */
    fun createTlsSocket(
        host: String,
        port: Int,
        timeoutMs: Int = DEFAULT_CONNECTION_TIMEOUT_MS
    ): SSLSocket {
        return try {
            // Create the underlying socket
            val socket = sslSocketFactory.createSocket() as? SSLSocket
                ?: throw TlsConnectionException("Failed to create SSL socket")

            // Configure timeouts
            socket.soTimeout = DEFAULT_READ_TIMEOUT_MS
            socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)

            // Configure TLS parameters
            configureTlsSocket(socket, host)

            socket
        } catch (e: IOException) {
            throw TlsConnectionException(
                "Failed to connect to $host:$port",
                e
            )
        } catch (e: TlsConnectionException) {
            throw e
        } catch (e: Exception) {
            throw TlsConnectionException(
                "Unexpected error connecting to $host:$port",
                e
            )
        }
    }

    /**
     * Create a TLS socket wrapping an existing connected socket.
     *
     * This is useful when you have a plain TCP socket that needs to be
     * upgraded to TLS (e.g., after a STARTTLS-like handshake).
     *
     * @param socket The existing connected socket
     * @param host The hostname for SNI (Server Name Indication)
     * @return Configured SSLSocket wrapping the original socket
     * @throws TlsConnectionException if TLS handshake fails
     */
    fun createTlsSocketOver(
        socket: Socket,
        host: String
    ): SSLSocket {
        return try {
            val sslSocket = sslSocketFactory.createSocket(
                socket,
                host,
                socket.port,
                true
            ) as? SSLSocket ?: throw TlsConnectionException(
                "Failed to create SSL socket over existing connection"
            )

            configureTlsSocket(sslSocket, host)
            sslSocket
        } catch (e: IOException) {
            throw TlsConnectionException(
                "Failed to establish TLS over existing connection to $host",
                e
            )
        } catch (e: TlsConnectionException) {
            throw e
        } catch (e: Exception) {
            throw TlsConnectionException(
                "Unexpected error establishing TLS to $host",
                e
            )
        }
    }

    /**
     * Configure an SSLSocket with appropriate TLS parameters.
     *
     * @param socket The SSLSocket to configure
     * @param host The hostname for SNI (Server Name Indication)
     */
    private fun configureTlsSocket(socket: SSLSocket, host: String) {
        try {
            // Enable TLS 1.3 (fallback to 1.2 if needed)
            val enabledProtocols = if (socket.supportedProtocols.contains(TLS_PROTOCOL_1_3)) {
                arrayOf(TLS_PROTOCOL_1_3)
            } else {
                // Fallback to TLS 1.2 for older Android versions
                arrayOf(TLS_PROTOCOL_1_2)
            }
            socket.enabledProtocols = enabledProtocols

            // Set SNI (Server Name Indication)
            // This is important for virtual hosting and certificate validation
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                val sslParameters = socket.sslParameters
                sslParameters.serverNames = listOf(
                    javax.net.ssl.SNIHostName(host)
                )
                socket.sslParameters = sslParameters
            }

            // Enable ALPN if supported (for protocol negotiation)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val sslParameters = socket.sslParameters
                sslParameters.applicationProtocols = arrayOf(ALPN_PROTO_ANY_SYNC)
                socket.sslParameters = sslParameters
            }
        } catch (e: Exception) {
            // Log but don't fail - connection may still work
            // In production, you might want to log this to a logging framework
        }
    }

    /**
     * Create an SSL socket factory with appropriate configuration.
     *
     * For production use, this uses the system's default trust manager.
     * For localhost testing, we could configure a custom trust manager
     * that accepts self-signed certificates.
     */
    private fun createSslSocketFactory(): SSLSocketFactory {
        val sslContext = javax.net.ssl.SSLContext.getInstance(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                "TLSv1.3"
            } else {
                "TLS"
            }
        )

        // For production, use the default trust managers
        // This ensures proper certificate validation
        sslContext.init(null, null, null)

        return sslContext.socketFactory
    }

    /**
     * Check if TLS 1.3 is available on this device.
     *
     * @return true if TLS 1.3 is supported
     */
    fun isTls13Supported(): Boolean {
        return try {
            val sslSocket = sslSocketFactory.createSocket() as? SSLSocket
            val supported = sslSocket?.supportedProtocols?.contains(TLS_PROTOCOL_1_3) == true
            supported
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Create a trust manager that accepts all certificates.
     *
     * WARNING: This should ONLY be used for testing with localhost.
     * Never use this in production code.
     *
     * @return A trust manager that accepts all certificates
     */
    private fun createInsecureTrustManager(): X509TrustManager {
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                // Accept all client certificates
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                // Accept all server certificates
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> {
                return emptyArray()
            }
        }
    }

    /**
     * Create a socket factory for testing with localhost.
     *
     * WARNING: This should ONLY be used for testing with localhost.
     * It accepts self-signed certificates and bypasses normal certificate validation.
     *
     * @return An SSLSocketFactory that accepts all certificates
     */
    fun createInsecureSocketFactoryForTesting(): SSLSocketFactory {
        val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
        sslContext.init(
            null,
            arrayOf<TrustManager>(createInsecureTrustManager()),
            java.security.SecureRandom()
        )
        return sslContext.socketFactory
    }
}

/**
 * Exception thrown when TLS connection establishment fails.
 */
class TlsConnectionException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    /**
     * User-friendly error message for UI display
     */
    val userMessage: String = message
}
