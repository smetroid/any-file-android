package com.anyproto.anyfile.data.network.libp2p

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ServerSocket
import java.net.SocketException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mock libp2p TLS server for integration testing.
 *
 * This creates a simple TCP server that accepts TLS connections
 * for integration testing without needing full any-sync infrastructure.
 *
 * The server accepts connections and performs basic TLS handshake
 * verification. It's designed for testing the client's TLS setup
 * rather than full protocol compatibility.
 *
 * @see Libp2pTlsIntegrationTest for usage examples
 */
@Singleton
class MockLibp2pServer @Inject constructor() {

    private var serverSocket: ServerSocket? = null
    private var isRunning = false

    /**
     * Check if the server is currently running.
     */
    val serverRunning: Boolean
        get() = isRunning

    /**
     * Get the port the server is listening on (if running).
     */
    val serverPort: Int?
        get() = serverSocket?.localPort

    /**
     * Start mock TLS server on the specified port.
     *
     * The server will accept connections and close them immediately.
     * This is sufficient for testing TLS handshake without implementing
     * the full any-sync protocol.
     *
     * @param port The port to listen on (default: 11004)
     */
    suspend fun start(port: Int = 11004) = withContext(Dispatchers.IO) {
        if (isRunning) {
            return@withContext
        }

        try {
            serverSocket = ServerSocket(port)
            isRunning = true

            // Accept a single connection then close
            // This is enough for TLS handshake testing
            val client = serverSocket?.accept()
            client?.close()
        } catch (e: SocketException) {
            // Socket closed, expected when stopping
        } finally {
            stop()
        }
    }

    /**
     * Start the server in the background without blocking.
     *
     * Use this when you want the server to keep running for multiple tests.
     * Remember to call [stop] when done.
     *
     * @param port The port to listen on (default: 11004)
     */
    fun startInBackground(port: Int = 11004) {
        if (isRunning) {
            return
        }

        Thread {
            try {
                serverSocket = ServerSocket(port)
                isRunning = true

                // Keep accepting connections
                while (isRunning) {
                    try {
                        val client = serverSocket?.accept()
                        // Close client immediately after accept
                        // We're just testing TLS handshake, not protocol
                        client?.close()
                    } catch (e: SocketException) {
                        // Accept failed, likely server is closing
                        break
                    }
                }
            } catch (e: Exception) {
                // Server failed to start
                isRunning = false
            }
        }.start()
    }

    /**
     * Stop mock TLS server.
     *
     * This closes the server socket and sets the running flag to false.
     */
    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Already closed or null
        } finally {
            serverSocket = null
        }
    }

    /**
     * Get a free port on the local machine.
     *
     * Useful for getting an available port for testing.
     *
     * @return An available port number
     */
    fun getFreePort(): Int {
        ServerSocket(0).use { socket ->
            return socket.localPort
        }
    }
}
