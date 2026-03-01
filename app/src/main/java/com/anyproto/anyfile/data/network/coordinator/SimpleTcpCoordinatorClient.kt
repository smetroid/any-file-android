package com.anyproto.anyfile.data.network.coordinator

import com.anyproto.anyfile.data.network.drpc.DrpcEncoding
import com.anyproto.anyfile.data.network.drpc.DrpcException
import com.anyproto.anyfile.data.network.drpc.DrpcParseException
import com.anyproto.anyfile.data.network.drpc.DrpcRequest
import com.anyproto.anyfile.data.network.drpc.DrpcResponse
import com.anyproto.anyfile.data.network.model.NetworkConfiguration
import com.anyproto.anyfile.data.network.model.NodeInfo
import com.anyproto.anyfile.data.network.model.NodeType
import com.anyproto.anyfile.protos.NetworkConfigurationRequest
import com.anyproto.anyfile.protos.NetworkConfigurationResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Simple TCP coordinator client for E2E tests.
 *
 * This client connects to the any-sync coordinator using plain TCP+DRPC
 * (no TLS, no yamux, no handshake). This matches how the Go any-file
 * client connects for basic coordinator operations.
 *
 * Purpose: E2E test infrastructure to verify coordinator accepts plain TCP+DRPC
 * while the Ed25519 TLS client authentication issue is resolved.
 *
 * Based on: any-file/internal/coordinator/client.go (Go implementation)
 *
 * @throws SimpleTcpCoordinatorException on connection or RPC failures
 */
@Singleton
class SimpleTcpCoordinatorClient @Inject constructor() {

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var coordinatorHost: String? = null
    private var coordinatorPort: Int? = null

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 30000L
        private const val SOCKET_TIMEOUT_MS = 30000
        private const val MAX_RESPONSE_SIZE = 16 * 1024 * 1024 // 16MB
        private const val SERVICE_ID = "coordinator.Coordinator"
    }

    /**
     * Initialize the client with coordinator endpoint.
     *
     * @param host The hostname or IP address
     * @param port The port number
     */
    fun initialize(host: String, port: Int) {
        this.coordinatorHost = host
        this.coordinatorPort = port
    }

    /**
     * Get network configuration from coordinator.
     *
     * @param currentId Optional current configuration ID
     * @return NetworkConfiguration with node list
     */
    suspend fun getNetworkConfiguration(currentId: String? = null): Result<NetworkConfiguration> =
        withContext(Dispatchers.IO) {
            try {
                ensureInitialized()

                val requestBuilder = NetworkConfigurationRequest.newBuilder()
                currentId?.let { requestBuilder.setCurrentId(it) }
                val requestProto = requestBuilder.build()

                val response = callRpc(
                    method = "NetworkConfiguration",
                    request = requestProto,
                    responseParser = NetworkConfigurationResponse.parser()
                )

                val networkConfig = NetworkConfiguration.fromProto(response)
                Result.success(networkConfig)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Register peer with network (alias for getNetworkConfiguration).
     *
     * @return NetworkConfiguration with available nodes
     */
    suspend fun registerPeer(): Result<NetworkConfiguration> = getNetworkConfiguration()

    /**
     * Get coordinator nodes from network configuration.
     *
     * @return List of coordinator node info
     */
    suspend fun getCoordinatorNodes(): Result<List<NodeInfo>> {
        val configResult = getNetworkConfiguration()
        return configResult.map { config ->
            config.nodes.filter { it.types.contains(NodeType.COORDINATOR_API) }
        }
    }

    /**
     * Close the connection.
     */
    fun close() {
        try {
            outputStream?.close()
            inputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            // Ignore close errors
        } finally {
            outputStream = null
            inputStream = null
            socket = null
        }
    }

    /**
     * Make an RPC call using DRPC protocol over plain TCP.
     *
     * DRPC format: [length][service_len][service][method_len][method][request_len][request]
     */
    private suspend fun <T : com.google.protobuf.MessageLite> callRpc(
        method: String,
        request: com.google.protobuf.MessageLite,
        responseParser: com.google.protobuf.Parser<T>
    ): T = withContext(Dispatchers.IO) {
        ensureConnection()

        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                // Send DRPC request
                sendRequest(SERVICE_ID, method, request)

                // Read DRPC response
                readResponse(responseParser)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw SimpleTcpCoordinatorException(
                "RPC call to $SERVICE_ID.$method timed out",
                e
            )
        } catch (e: DrpcException) {
            throw SimpleTcpCoordinatorException(
                "DRPC error calling $SERVICE_ID.$method: ${e.message}",
                e
            )
        } catch (e: Exception) {
            throw SimpleTcpCoordinatorException(
                "Failed to call $SERVICE_ID.$method",
                e
            )
        }
    }

    /**
     * Send DRPC request over TCP socket.
     */
    private suspend fun sendRequest(
        serviceId: String,
        method: String,
        request: com.google.protobuf.MessageLite
    ) {
        val drpcRequest = DrpcRequest(
            serviceId = serviceId,
            methodId = method,
            request = request
        )

        val encodedRequest = drpcRequest.encode()
        val framedRequest = DrpcEncoding.encodeMessageWithLength(encodedRequest)

        outputStream?.write(framedRequest)
            ?: throw IllegalStateException("Output stream not initialized")
        outputStream?.flush()
    }

    /**
     * Read DRPC response from TCP socket.
     */
    private suspend fun <T : com.google.protobuf.MessageLite> readResponse(
        responseParser: com.google.protobuf.Parser<T>
    ): T {
        // Read length prefix (varint)
        val lengthBuffer = mutableListOf<Byte>()
        var b: Int
        var shift = 0
        var length = 0

        do {
            b = inputStream?.read()
                ?: throw IOException("End of stream while reading length prefix")
            if (b == -1) throw IOException("Connection closed while reading length prefix")

            lengthBuffer.add(b.toByte())

            val value = b and 0x7F
            length = length or (value shl shift)

            if ((b and 0x80) == 0) break
            shift += 7
            if (shift >= 32) {
                throw DrpcParseException("Varint32 too large")
            }
        } while (true)

        if (length < 0 || length > MAX_RESPONSE_SIZE) {
            throw DrpcParseException(
                "Invalid message length: $length (max: $MAX_RESPONSE_SIZE)"
            )
        }

        // Read response data
        val responseData = ByteArray(length)
        var totalRead = 0
        while (totalRead < length) {
            val read = inputStream?.read(responseData, totalRead, length - totalRead)
                ?: throw IOException("End of stream while reading response data")
            if (read == -1) throw IOException("Connection closed while reading response data")
            totalRead += read
        }

        // Decode DRPC response
        val decodedResponse = DrpcResponse.decode(responseData)

        // Check for success
        if (!decodedResponse.success) {
            val errorMsg = if (decodedResponse.payload.isNotEmpty()) {
                String(decodedResponse.payload)
            } else {
                "Unknown error (code: ${decodedResponse.errorCode})"
            }
            throw SimpleTcpCoordinatorException(
                "RPC call failed: $errorMsg"
            )
        }

        // Parse protobuf response
        return try {
            responseParser.parseFrom(decodedResponse.payload)
        } catch (e: Exception) {
            throw DrpcParseException("Failed to parse protobuf response", e)
        }
    }

    /**
     * Ensure connection is established.
     */
    private fun ensureConnection() {
        if (socket?.isConnected != true || socket?.isClosed == true) {
            val host = coordinatorHost ?: throw IllegalStateException("Not initialized")
            val port = coordinatorPort ?: throw IllegalStateException("Not initialized")

            try {
                socket = Socket(host, port)
                socket?.soTimeout = SOCKET_TIMEOUT_MS
                inputStream = socket?.getInputStream()
                outputStream = socket?.getOutputStream()
            } catch (e: IOException) {
                throw SimpleTcpCoordinatorException(
                    "Failed to connect to $host:$port",
                    e
                )
            }
        }
    }

    /**
     * Ensure client is initialized.
     */
    private fun ensureInitialized() {
        if (coordinatorHost == null || coordinatorPort == null) {
            throw IllegalStateException(
                "SimpleTcpCoordinatorClient not initialized. Call initialize(host, port) first."
            )
        }
    }
}

/**
 * Exception thrown when simple TCP coordinator operations fail.
 */
class SimpleTcpCoordinatorException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
