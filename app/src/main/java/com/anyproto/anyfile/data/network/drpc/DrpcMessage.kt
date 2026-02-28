package com.anyproto.anyfile.data.network.drpc

import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * DRPC protocol message format.
 *
 * DRPC uses a simple binary protocol over yamux streams:
 * - Messages are prefixed with a varint32 length
 * - Message format: [length][service_id][method_id][encoded_request]
 * - Response format: [length][success_flag][error_code][encoded_response or error_message]
 *
 * Based on the storj.io/drpc protocol implementation.
 */
object DrpcMessage {
    /**
     * Maximum message size (16MB).
     */
    const val MAX_MESSAGE_SIZE = 16 * 1024 * 1024

    /**
     * DRPC protocol version.
     */
    const val VERSION = 0x00

    /**
     * Message types.
     */
    const val MESSAGE_TYPE_REQUEST = 0x01
    const val MESSAGE_TYPE_RESPONSE = 0x02
    const val MESSAGE_TYPE_ERROR = 0x03
    const val MESSAGE_TYPE_CANCEL = 0x04

    /**
     * Special values for success/error signaling.
     */
    const val SUCCESS = 0x00
    const val ERROR = 0x01

    /**
     * Standard DRPC error codes.
     */
    object ErrorCode {
        const val UNKNOWN = 0
        const val PARSE_ERROR = 1
        const val METHOD_NOT_FOUND = 2
        const val TIMEOUT = 3
        const val CANCELED = 4
        const val INTERNAL_ERROR = 5
    }
}

/**
 * DRPC request message.
 *
 * @param serviceId The service identifier (e.g., "coordinator.Coordinator")
 * @param methodId The method identifier (e.g., "SpaceSign")
 * @param request The protobuf request message
 */
data class DrpcRequest(
    val serviceId: String,
    val methodId: String,
    val request: MessageLite
) {
    /**
     * Encode the request to bytes for transmission.
     *
     * Format: [service_len][service_bytes][method_len][method_bytes][request_len][request_bytes]
     */
    fun encode(): ByteArray {
        val output = ByteArrayOutputStream()

        val serviceBytes = serviceId.toByteArray()
        val methodBytes = methodId.toByteArray()
        val requestBytes = request.toByteArray()

        // Check size limits
        val totalSize = serviceBytes.size + methodBytes.size + requestBytes.size
        if (totalSize > DrpcMessage.MAX_MESSAGE_SIZE) {
            throw DrpcEncodingException(
                "Message size $totalSize exceeds maximum ${DrpcMessage.MAX_MESSAGE_SIZE}"
            )
        }

        try {
            // Write service ID
            DrpcEncoding.writeVarInt32(output, serviceBytes.size)
            output.write(serviceBytes)

            // Write method ID
            DrpcEncoding.writeVarInt32(output, methodBytes.size)
            output.write(methodBytes)

            // Write request payload
            DrpcEncoding.writeVarInt32(output, requestBytes.size)
            output.write(requestBytes)

            return output.toByteArray()
        } catch (e: IOException) {
            throw DrpcEncodingException("Failed to encode DRPC request", e)
        }
    }

    companion object {
        /**
         * Decode a DRPC request from bytes.
         */
        fun decode(data: ByteArray, offset: Int = 0): DecodedDrpcRequest {
            var pos = offset

            // Read service ID
            val serviceLengthResult = DrpcEncoding.readVarInt32(data, pos)
            val serviceLength = serviceLengthResult.first
            pos += serviceLengthResult.second
            val serviceId = String(data, pos, pos + serviceLength, Charsets.UTF_8)
            pos += serviceLength

            // Read method ID
            val methodLengthResult = DrpcEncoding.readVarInt32(data, pos)
            val methodLength = methodLengthResult.first
            pos += methodLengthResult.second
            val methodId = String(data, pos, pos + methodLength, Charsets.UTF_8)
            pos += methodLength

            // Read request payload
            val requestLengthResult = DrpcEncoding.readVarInt32(data, pos)
            val requestLength = requestLengthResult.first
            pos += requestLengthResult.second
            val requestPayload = if (requestLength > 0) {
                data.copyOfRange(pos, pos + requestLength)
            } else {
                ByteArray(0)
            }

            return DecodedDrpcRequest(
                serviceId = serviceId,
                methodId = methodId,
                requestPayload = requestPayload,
                totalBytesRead = (pos + requestLength) - offset
            )
        }
    }
}

/**
 * Decoded DRPC request (without the request object parsed).
 */
data class DecodedDrpcRequest(
    val serviceId: String,
    val methodId: String,
    val requestPayload: ByteArray,
    val totalBytesRead: Int
)

/**
 * DRPC response message.
 *
 * @param success Whether the RPC call was successful
 * @param errorCode The error code (if success is false)
 * @param response The protobuf response message (if success is true)
 * @param errorMessage The error message (if success is false)
 */
data class DrpcResponse(
    val success: Boolean,
    val errorCode: Int = 0,
    val response: MessageLite? = null,
    val errorMessage: String? = null
) {
    /**
     * Encode the response to bytes for transmission.
     *
     * Format: [success_flag][error_code][response_len][response_bytes] or
     *         [success_flag][error_code][error_msg_len][error_msg_bytes]
     */
    fun encode(): ByteArray {
        val output = ByteArrayOutputStream()

        try {
            // Write success flag
            output.write(if (success) DrpcMessage.SUCCESS else DrpcMessage.ERROR)

            // Write error code
            DrpcEncoding.writeVarInt32(output, errorCode)

            if (success) {
                // Write response payload
                val responseBytes = response?.toByteArray() ?: ByteArray(0)
                DrpcEncoding.writeVarInt32(output, responseBytes.size)
                if (responseBytes.isNotEmpty()) {
                    output.write(responseBytes)
                }
            } else {
                // Write error message
                val errorMsgBytes = (errorMessage ?: "").toByteArray()
                DrpcEncoding.writeVarInt32(output, errorMsgBytes.size)
                if (errorMsgBytes.isNotEmpty()) {
                    output.write(errorMsgBytes)
                }
            }

            return output.toByteArray()
        } catch (e: IOException) {
            throw DrpcEncodingException("Failed to encode DRPC response", e)
        }
    }

    companion object {
        /**
         * Decode a DRPC response from bytes.
         */
        fun decode(data: ByteArray, offset: Int = 0): DecodedDrpcResponse {
            var pos = offset

            // Read success flag
            val success = data[pos].toInt() == DrpcMessage.SUCCESS
            pos++

            // Read error code
            val errorCodeResult = DrpcEncoding.readVarInt32(data, pos)
            val errorCode = errorCodeResult.first
            pos += errorCodeResult.second

            // Read payload
            val payloadLengthResult = DrpcEncoding.readVarInt32(data, pos)
            val payloadLength = payloadLengthResult.first
            pos += payloadLengthResult.second
            val payload = if (payloadLength > 0) {
                data.copyOfRange(pos, pos + payloadLength)
            } else {
                ByteArray(0)
            }

            return DecodedDrpcResponse(
                success = success,
                errorCode = errorCode,
                payload = payload,
                totalBytesRead = (pos + payloadLength) - offset
            )
        }
    }
}

/**
 * Decoded DRPC response (without the response object parsed).
 */
data class DecodedDrpcResponse(
    val success: Boolean,
    val errorCode: Int,
    val payload: ByteArray,
    val totalBytesRead: Int
)

/**
 * Utility functions for varint encoding/decoding.
 */
object DrpcEncoding {
    /**
     * Write a varint32 to a ByteArrayOutputStream.
     */
    fun writeVarInt32(output: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v and 0x7F != 0) {
            output.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        output.write(v)
    }

    /**
     * Read a varint32 from a byte array.
     *
     * @return Pair of (value, bytes read)
     */
    fun readVarInt32(data: ByteArray, offset: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var bytesRead = 0

        while (true) {
            if (offset + bytesRead >= data.size) {
                throw DrpcParseException("Unexpected end of data while reading varint")
            }
            val b = data[offset + bytesRead].toInt() and 0xFF
            bytesRead++
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) {
                break
            }
            shift += 7
            if (shift >= 32) {
                throw DrpcParseException("Varint32 too large")
            }
        }

        return Pair(result, bytesRead)
    }

    /**
     * Calculate the size of a varint32 when encoded.
     */
    fun varInt32Size(value: Int): Int {
        var v = value
        var size = 0
        do {
            v = v ushr 7
            size++
        } while (v != 0)
        return size
    }

    /**
     * Encode a complete message with length prefix.
     */
    fun encodeMessageWithLength(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        writeVarInt32(output, data.size)
        output.write(data)
        return output.toByteArray()
    }

    /**
     * Decode a message with length prefix.
     *
     * @return Pair of (message data, bytes read)
     */
    fun decodeMessageWithLength(data: ByteArray, offset: Int = 0): Pair<ByteArray, Int> {
        val lengthResult = readVarInt32(data, offset)
        val length = lengthResult.first
        var pos = offset + lengthResult.second

        if (length < 0 || length > DrpcMessage.MAX_MESSAGE_SIZE) {
            throw DrpcParseException(
                "Invalid message length: $length (max: ${DrpcMessage.MAX_MESSAGE_SIZE})"
            )
        }

        if (pos + length > data.size) {
            throw DrpcParseException(
                "Incomplete message: expected $length bytes, only ${data.size - pos} available"
            )
        }

        val messageData = if (length > 0) {
            data.copyOfRange(pos, pos + length)
        } else {
            ByteArray(0)
        }

        return Pair(messageData, (pos + length) - offset)
    }
}
