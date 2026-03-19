package com.anyproto.anyfile.data.network.drpc

import android.util.Log
import com.anyproto.anyfile.data.network.yamux.YamuxSession
import com.anyproto.anyfile.data.network.yamux.YamuxStream
import com.anyproto.anyfile.data.network.yamux.YamuxStreamException
import com.google.protobuf.MessageLite
import com.google.protobuf.Parser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DRPC client for making RPC calls over yamux streams.
 *
 * DRPC (Distributed RPC) is a simple RPC protocol that sends binary-encoded
 * protobuf messages over yamux streams. This client provides both synchronous
 * and coroutine-based asynchronous methods for making RPC calls.
 *
 * Example usage:
 * ```kotlin
 * // Initialize client with a yamux session
 * val client = DrpcClient(session)
 *
 * // Make an async call
 * val response = client.callAsync(
 *     service = "coordinator.Coordinator",
 *     method = "SpaceSign",
 *     request = spaceSignRequest,
 *     responseParser = SpaceSignResponse.parser()
 * )
 * ```
 *
 * @property session The yamux session to use for RPC calls
 */
@Singleton
class DrpcClient @Inject constructor(
    private val session: YamuxSession
) {
    companion object {
        private const val TAG = "DrpcClient"

        /**
         * Default timeout for RPC calls in milliseconds.
         */
        const val DEFAULT_TIMEOUT_MS = 30000L

        /**
         * Maximum buffer size for reading responses.
         */
        private const val MAX_RESPONSE_SIZE = 16 * 1024 * 1024 // 16MB

        /**
         * Buffer size for reading stream data chunks.
         */
        private const val READ_BUFFER_SIZE = 8192
    }

    /**
     * Default timeout for RPC calls.
     */
    var defaultTimeoutMs: Long = DEFAULT_TIMEOUT_MS

    /**
     * Make a synchronous RPC call.
     *
     * This method blocks until the response is received or timeout occurs.
     * It's a wrapper around callAsync that runs in the IO dispatcher.
     *
     * @param service The service identifier (e.g., "coordinator.Coordinator")
     * @param method The method identifier (e.g., "SpaceSign")
     * @param request The protobuf request message
     * @param responseParser The parser for the response message type
     * @param timeoutMs Optional timeout in milliseconds (uses default if not specified)
     * @return The parsed protobuf response message
     * @throws DrpcException if the RPC call fails
     */
    suspend fun <T : MessageLite> call(
        service: String,
        method: String,
        request: MessageLite,
        responseParser: Parser<T>,
        timeoutMs: Long = defaultTimeoutMs
    ): T {
        return callAsync(service, method, request, responseParser, timeoutMs)
    }

    /**
     * Make an asynchronous RPC call using coroutines.
     *
     * This is the main method for making RPC calls. It:
     * 1. Opens a new yamux stream
     * 2. Sends the DRPC request
     * 3. Reads and parses the response
     * 4. Closes the stream
     *
     * @param service The service identifier (e.g., "coordinator.Coordinator")
     * @param method The method identifier (e.g., "SpaceSign")
     * @param request The protobuf request message
     * @param responseParser The parser for the response message type
     * @param timeoutMs Optional timeout in milliseconds (uses default if not specified)
     * @return The parsed protobuf response message
     * @throws DrpcException if the RPC call fails
     */
    suspend fun <T : MessageLite> callAsync(
        service: String,
        method: String,
        request: MessageLite,
        responseParser: Parser<T>,
        timeoutMs: Long = defaultTimeoutMs
    ): T = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== DRPC Call: $service.$method ===")
        Log.d(TAG, "Request type: ${request.javaClass.simpleName}")

        val stream = try {
            Log.d(TAG, "Opening yamux stream for RPC call...")
            session.openStream()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open yamux stream", e)
            throw DrpcConnectionException("Failed to open yamux stream for RPC call", e)
        }

        try {
            withTimeout(timeoutMs) {
                // Wait for the stream to become OPEN (receive ACK for outbound streams)
                Log.d(TAG, "Waiting for stream to become OPEN...")
                stream.waitForOpen()
                Log.d(TAG, "Stream is OPEN, ready to send data")

                // Send the request
                Log.d(TAG, "Sending DRPC request...")
                sendRequest(stream, service, method, request)
                Log.d(TAG, "DRPC request sent successfully")

                // Read and parse the response
                Log.d(TAG, "Reading DRPC response...")
                val response = readResponse(stream, responseParser)
                Log.d(TAG, "DRPC response received: ${response.javaClass.simpleName}")
                response
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.e(TAG, "RPC call timed out after ${timeoutMs}ms")
            stream.close()
            throw DrpcTimeoutException("RPC call to $service.$method timed out after ${timeoutMs}ms", e)
        } catch (e: DrpcException) {
            Log.e(TAG, "DRPC exception: ${e.message}", e)
            stream.close()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "RPC call failed", e)
            stream.close()
            throw DrpcConnectionException("RPC call to $service.$method failed", e)
        } finally {
            // Ensure stream is properly closed
            if (stream.state != YamuxStream.State.CLOSED) {
                try {
                    stream.closeWrite()
                } catch (e: Exception) {
                    // Ignore close errors
                }
            }
        }
    }

    /**
     * Send a DRPC request over a yamux stream.
     *
     * @param stream The yamux stream to send the request on
     * @param service The service identifier
     * @param method The method identifier
     * @param request The protobuf request message
     * @throws DrpcEncodingException if encoding fails
     * @throws DrpcConnectionException if sending fails
     */
    private suspend fun sendRequest(
        stream: YamuxStream,
        service: String,
        method: String,
        request: MessageLite
    ) {
        try {
            // Create DRPC request
            val drpcRequest = DrpcRequest(
                serviceId = service,
                methodId = method,
                request = request
            )

            // Encode the request
            val encodedRequest = drpcRequest.encode()

            // Add length prefix for framing
            val framedRequest = DrpcEncoding.encodeMessageWithLength(encodedRequest)

            // Send over the stream
            stream.write(framedRequest)

            // Signal end of request
            stream.closeWrite()
        } catch (e: DrpcEncodingException) {
            throw e
        } catch (e: YamuxStreamException) {
            throw DrpcConnectionException("Failed to send DRPC request", e)
        } catch (e: Exception) {
            throw DrpcEncodingException("Failed to encode DRPC request", e)
        }
    }

    /**
     * Read and parse a DRPC response from a yamux stream.
     *
     * @param stream The yamux stream to read from
     * @param responseParser The parser for the response message type
     * @return The parsed protobuf response message
     * @throws DrpcTimeoutException if reading times out
     * @throws DrpcParseException if parsing fails
     * @throws DrpcRpcException if the RPC call failed on the server
     * @throws DrpcInvalidResponseException if the response is invalid
     */
    private suspend fun <T : MessageLite> readResponse(
        stream: YamuxStream,
        responseParser: Parser<T>
    ): T {
        try {
            // Read all response data from stream
            val responseData = readAllData(stream)

            if (responseData.isEmpty()) {
                throw DrpcInvalidResponseException("Empty response received from server")
            }

            // Decode the message with length prefix
            val (messageData, _) = DrpcEncoding.decodeMessageWithLength(responseData)

            // Decode DRPC response
            val decodedResponse = DrpcResponse.decode(messageData)

            // Check if the call was successful
            if (!decodedResponse.success) {
                val errorMsg = if (decodedResponse.payload.isNotEmpty()) {
                    String(decodedResponse.payload)
                } else {
                    "Unknown error"
                }
                throw DrpcRpcException(
                    code = decodedResponse.errorCode,
                    message = errorMsg
                )
            }

            // Parse the protobuf response (empty payload is valid for default messages)
            try {
                return responseParser.parseFrom(decodedResponse.payload)
            } catch (e: Exception) {
                throw DrpcParseException("Failed to parse protobuf response", e)
            }
        } catch (e: DrpcException) {
            throw e
        } catch (e: YamuxStreamException) {
            throw DrpcConnectionException("Failed to read DRPC response", e)
        } catch (e: Exception) {
            throw DrpcParseException("Failed to decode DRPC response", e)
        }
    }

    /**
     * Read all data from a yamux stream until it's closed.
     *
     * DRPC sends the complete response and then closes the write side,
     * so we keep reading until we get null (stream closed).
     *
     * @param stream The yamux stream to read from
     * @return The complete response data
     * @throws DrpcConnectionException if reading fails
     * @throws DrpcTimeoutException if reading times out
     */
    private suspend fun readAllData(stream: YamuxStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        var totalSize = 0

        while (true) {
            val chunk = try {
                stream.read(timeoutMs = 5000)
            } catch (e: YamuxStreamException) {
                if (e.message?.contains("timeout", ignoreCase = true) == true) {
                    // Check if we have any data
                    if (totalSize > 0) {
                        // We have some data, consider this as end of stream
                        break
                    }
                    throw DrpcTimeoutException("Timeout while reading response", e)
                }
                throw DrpcConnectionException("Failed to read from stream", e)
            }

            if (chunk == null) {
                // Stream closed
                break
            }

            if (totalSize + chunk.size > MAX_RESPONSE_SIZE) {
                stream.close()
                throw DrpcInvalidResponseException(
                    "Response too large: ${totalSize + chunk.size} bytes (max: $MAX_RESPONSE_SIZE)"
                )
            }

            buffer.write(chunk)
            totalSize += chunk.size
        }

        return buffer.toByteArray()
    }

    /**
     * Make a streaming RPC call and collect all response frames.
     *
     * Used for server-streaming RPCs (e.g. FilesGet) where the server sends
     * multiple DRPC frames on the same stream until it closes.
     */
    suspend fun <T : MessageLite> callStreamingAsync(
        service: String,
        method: String,
        request: MessageLite,
        responseParser: Parser<T>,
        timeoutMs: Long = defaultTimeoutMs
    ): List<T> = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== DRPC Streaming Call: $service.$method ===")

        val stream = try {
            session.openStream()
        } catch (e: Exception) {
            throw DrpcConnectionException("Failed to open yamux stream for streaming RPC call", e)
        }

        try {
            withTimeout(timeoutMs) {
                stream.waitForOpen()
                sendRequest(stream, service, method, request)
                val allData = readAllData(stream)
                decodeStreamingResponses(allData, responseParser)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            stream.close()
            throw DrpcTimeoutException("Streaming RPC call to $service.$method timed out", e)
        } catch (e: DrpcException) {
            stream.close()
            throw e
        } catch (e: Exception) {
            stream.close()
            throw DrpcConnectionException("Streaming RPC call to $service.$method failed", e)
        } finally {
            if (stream.state != YamuxStream.State.CLOSED) {
                try { stream.closeWrite() } catch (e: Exception) { /* ignore */ }
            }
        }
    }

    private fun <T : MessageLite> decodeStreamingResponses(
        data: ByteArray,
        responseParser: Parser<T>
    ): List<T> {
        if (data.isEmpty()) return emptyList()
        val results = mutableListOf<T>()
        var offset = 0
        while (offset < data.size) {
            val (messageData, bytesRead) = DrpcEncoding.decodeMessageWithLength(data, offset)
            offset += bytesRead
            val decoded = DrpcResponse.decode(messageData)
            if (!decoded.success) {
                val errorMsg = if (decoded.payload.isNotEmpty()) String(decoded.payload) else "Unknown error"
                throw DrpcRpcException(code = decoded.errorCode, message = errorMsg)
            }
            results.add(responseParser.parseFrom(decoded.payload))
        }
        return results
    }

    /**
     * Check if the client session is active.
     *
     * @return true if the session is active, false otherwise
     */
    fun isActive(): Boolean {
        return session.isActive()
    }

    /**
     * Get the number of active streams in the session.
     *
     * @return The number of active streams
     */
    fun getStreamCount(): Int {
        return session.getStreamCount()
    }
}

/**
 * Convenience extension function to make RPC calls with default service.
 */
suspend fun <T : MessageLite> DrpcClient.coordinatorCall(
    method: String,
    request: MessageLite,
    responseParser: Parser<T>,
    timeoutMs: Long = DrpcClient.DEFAULT_TIMEOUT_MS
): T {
    return call("coordinator.Coordinator", method, request, responseParser, timeoutMs)
}

/**
 * Convenience extension function to make RPC calls to filenode service.
 *
 * Note: The proto defines this as "filesync.File" (package filesync, service File),
 * not "filenode.Filenode". This matches the actual any-sync filenode implementation.
 */
suspend fun <T : MessageLite> DrpcClient.filenodeCall(
    method: String,
    request: MessageLite,
    responseParser: Parser<T>,
    timeoutMs: Long = DrpcClient.DEFAULT_TIMEOUT_MS
): T {
    return call("filesync.File", method, request, responseParser, timeoutMs)
}

/**
 * Convenience extension for server-streaming filenode calls (e.g. FilesGet).
 */
suspend fun <T : MessageLite> DrpcClient.filenodeStreamingCall(
    method: String,
    request: MessageLite,
    responseParser: Parser<T>,
    timeoutMs: Long = DrpcClient.DEFAULT_TIMEOUT_MS
): List<T> {
    return callStreamingAsync("filesync.File", method, request, responseParser, timeoutMs)
}
