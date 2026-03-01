package com.anyproto.anyfile.data.network.handshake

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Message framing for the any-sync handshake protocol.
 *
 * Frame format:
 * ```
 * [0: type] [1-4: payload size (Little Endian)] [5: payload...]
 * ```
 *
 * @property type Message type (MSG_TYPE_CRED, MSG_TYPE_ACK, MSG_TYPE_PROTO)
 * @property payload Payload bytes (protobuf serialized message)
 */
data class Frame(
    val type: Byte,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Frame

        if (type != other.type) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * Constants for message framing.
 */
object HandshakeFrame {
    /** Size of the frame header (type + size) */
    const val HEADER_SIZE = 5

    /** Credentials message type */
    const val MSG_TYPE_CRED: Byte = 1

    /** Acknowledgment message type */
    const val MSG_TYPE_ACK: Byte = 2

    /** Protocol negotiation message type */
    const val MSG_TYPE_PROTO: Byte = 3

    /** Maximum payload size (200 KB) */
    const val SIZE_LIMIT = 200 * 1024

    /**
     * Write a framed message to the output stream.
     *
     * @param output Output stream to write to
     * @param type Message type (MSG_TYPE_CRED, MSG_TYPE_ACK, MSG_TYPE_PROTO)
     * @param payload Payload bytes to write
     * @throws IOException if writing fails
     * @throws IllegalArgumentException if payload exceeds SIZE_LIMIT
     */
    fun writeMessage(output: OutputStream, type: Byte, payload: ByteArray) {
        require(payload.size <= SIZE_LIMIT) {
            "Payload size ${payload.size} exceeds limit $SIZE_LIMIT"
        }

        // Create header: [type] [size (little endian)]
        val header = ByteBuffer.allocate(HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(type)
            .putInt(payload.size)
            .array()

        // Write header and payload
        output.write(header)
        output.write(payload)
        output.flush()
    }

    /**
     * Read a framed message from the input stream.
     *
     * @param input Input stream to read from
     * @param allowedTypes Set of allowed message types
     * @return Frame containing type and payload
     * @throws IOException if reading fails
     * @throws HandshakeProtocolException if message type is not allowed
     * @throws HandshakeProtocolException if payload size exceeds SIZE_LIMIT
     */
    fun readMessage(input: InputStream, allowedTypes: Set<Byte>): Frame {
        // Read header
        val header = ByteArray(HEADER_SIZE)
        readFully(input, header)

        // Parse header
        val type = header[0]
        val size = ByteBuffer.wrap(header, 1, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int

        // Validate size
        if (size < 0 || size > SIZE_LIMIT) {
            throw HandshakeProtocolException(
                "Invalid payload size: $size (limit: $SIZE_LIMIT)"
            )
        }

        // Validate message type
        if (type !in allowedTypes) {
            throw HandshakeProtocolException(
                "Unexpected message type: $type (allowed: $allowedTypes)"
            )
        }

        // Read payload
        val payload = ByteArray(size)
        readFully(input, payload)

        return Frame(type, payload)
    }

    /**
     * Read exactly the specified number of bytes from the input stream.
     *
     * @param input Input stream to read from
     * @param buffer Buffer to read into
     * @throws IOException if reading fails or end of stream is reached
     */
    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        val size = buffer.size

        while (offset < size) {
            val read = input.read(buffer, offset, size - offset)
            if (read < 0) {
                throw IOException("Unexpected end of stream")
            }
            offset += read
        }
    }
}

/**
 * Exception thrown when a handshake protocol violation occurs.
 */
class HandshakeProtocolException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
