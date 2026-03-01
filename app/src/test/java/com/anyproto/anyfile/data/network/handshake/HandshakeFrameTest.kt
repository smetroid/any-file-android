package com.anyproto.anyfile.data.network.handshake

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class HandshakeFrameTest {

    @Test
    fun writeMessage_writesCorrectHeader() {
        val output = ByteArrayOutputStream()
        val payload = byteArrayOf(1, 2, 3, 4, 5)

        HandshakeFrame.writeMessage(output, HandshakeFrame.MSG_TYPE_CRED, payload)

        val result = output.toByteArray()
        assertThat(result[0]).isEqualTo(HandshakeFrame.MSG_TYPE_CRED)
        // Size should be 5 in little endian
        assertThat(result[1]).isEqualTo(5)
        assertThat(result[2]).isEqualTo(0)
        assertThat(result[3]).isEqualTo(0)
        assertThat(result[4]).isEqualTo(0)
    }

    @Test
    fun writeMessage_writesPayloadAfterHeader() {
        val output = ByteArrayOutputStream()
        val payload = byteArrayOf(10, 20, 30)

        HandshakeFrame.writeMessage(output, HandshakeFrame.MSG_TYPE_ACK, payload)

        val result = output.toByteArray()
        val writtenPayload = result.sliceArray(HandshakeFrame.HEADER_SIZE until result.size)
        assertThat(writtenPayload).isEqualTo(payload)
    }

    @Test
    fun writeMessage_withLargePayload_writesCorrectSize() {
        val output = ByteArrayOutputStream()
        val payload = ByteArray(1000) { it.toByte() }

        HandshakeFrame.writeMessage(output, HandshakeFrame.MSG_TYPE_PROTO, payload)

        val result = output.toByteArray()
        assertThat(result[0]).isEqualTo(HandshakeFrame.MSG_TYPE_PROTO)
        // Check size in little endian (1000 = 0x03E8)
        assertThat(result[1]).isEqualTo(0xE8.toByte())
        assertThat(result[2]).isEqualTo(0x03)
        assertThat(result[3]).isEqualTo(0)
        assertThat(result[4]).isEqualTo(0)
    }

    @Test
    fun writeMessage_withPayloadExceedingLimit_throwsException() {
        val output = ByteArrayOutputStream()
        val payload = ByteArray(HandshakeFrame.SIZE_LIMIT + 1)

        val exception = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            HandshakeFrame.writeMessage(output, HandshakeFrame.MSG_TYPE_CRED, payload)
        }

        assertThat(exception.message).contains("exceeds limit")
    }

    @Test
    fun readMessage_readsCorrectFrame() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val output = ByteArrayOutputStream()
        HandshakeFrame.writeMessage(output, HandshakeFrame.MSG_TYPE_CRED, payload)

        val input = ByteArrayInputStream(output.toByteArray())
        val frame = HandshakeFrame.readMessage(input, setOf(HandshakeFrame.MSG_TYPE_CRED))

        assertThat(frame.type).isEqualTo(HandshakeFrame.MSG_TYPE_CRED)
        assertThat(frame.payload).isEqualTo(payload)
    }

    @Test
    fun readMessage_withUnexpectedType_throwsException() {
        val payload = byteArrayOf(1, 2, 3)
        val output = ByteArrayOutputStream()
        HandshakeFrame.writeMessage(output, HandshakeFrame.MSG_TYPE_ACK, payload)

        val input = ByteArrayInputStream(output.toByteArray())

        val exception = org.junit.Assert.assertThrows(HandshakeProtocolException::class.java) {
            HandshakeFrame.readMessage(input, setOf(HandshakeFrame.MSG_TYPE_CRED))
        }

        assertThat(exception.message).contains("Unexpected message type")
    }

    @Test
    fun readMessage_withInvalidSize_throwsException() {
        // Create a frame with negative size
        val header = ByteBuffer.allocate(HandshakeFrame.HEADER_SIZE)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put(HandshakeFrame.MSG_TYPE_CRED)
            .putInt(-1)
            .array()

        val input = ByteArrayInputStream(header)

        val exception = org.junit.Assert.assertThrows(HandshakeProtocolException::class.java) {
            HandshakeFrame.readMessage(input, setOf(HandshakeFrame.MSG_TYPE_CRED))
        }

        assertThat(exception.message).contains("Invalid payload size")
    }

    @Test
    fun readMessage_withSizeExceedingLimit_throwsException() {
        // Create a frame with size exceeding limit
        val header = ByteBuffer.allocate(HandshakeFrame.HEADER_SIZE)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put(HandshakeFrame.MSG_TYPE_CRED)
            .putInt(HandshakeFrame.SIZE_LIMIT + 1)
            .array()

        val input = ByteArrayInputStream(header)

        val exception = org.junit.Assert.assertThrows(HandshakeProtocolException::class.java) {
            HandshakeFrame.readMessage(input, setOf(HandshakeFrame.MSG_TYPE_CRED))
        }

        assertThat(exception.message).contains("Invalid payload size")
    }

    @Test
    fun readMessage_withEmptyPayload_works() {
        val payload = byteArrayOf()
        val output = ByteArrayOutputStream()
        HandshakeFrame.writeMessage(output, HandshakeFrame.MSG_TYPE_ACK, payload)

        val input = ByteArrayInputStream(output.toByteArray())
        val frame = HandshakeFrame.readMessage(input, setOf(HandshakeFrame.MSG_TYPE_ACK))

        assertThat(frame.type).isEqualTo(HandshakeFrame.MSG_TYPE_ACK)
        assertThat(frame.payload).isEmpty()
    }

    @Test
    fun roundTrip_writeAndRead_preservesData() {
        val originalPayload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

        val output = ByteArrayOutputStream()
        HandshakeFrame.writeMessage(output, HandshakeFrame.MSG_TYPE_PROTO, originalPayload)

        val input = ByteArrayInputStream(output.toByteArray())
        val frame = HandshakeFrame.readMessage(input, setOf(HandshakeFrame.MSG_TYPE_PROTO))

        assertThat(frame.payload).isEqualTo(originalPayload)
    }

    @Test
    fun readMessage_readsAllMessageTypes() {
        val payload = byteArrayOf(42)

        for (type in listOf(HandshakeFrame.MSG_TYPE_CRED, HandshakeFrame.MSG_TYPE_ACK, HandshakeFrame.MSG_TYPE_PROTO)) {
            val output = ByteArrayOutputStream()
            HandshakeFrame.writeMessage(output, type, payload)

            val input = ByteArrayInputStream(output.toByteArray())
            val frame = HandshakeFrame.readMessage(input, setOf(type))

            assertThat(frame.type).isEqualTo(type)
            assertThat(frame.payload).isEqualTo(payload)
        }
    }

    @Test
    fun readMessage_withMultipleAllowedTypes_acceptsAnyAllowedType() {
        val payload = byteArrayOf(1, 2, 3)
        val output = ByteArrayOutputStream()
        HandshakeFrame.writeMessage(output, HandshakeFrame.MSG_TYPE_ACK, payload)

        val input = ByteArrayInputStream(output.toByteArray())
        val frame = HandshakeFrame.readMessage(
            input,
            setOf(HandshakeFrame.MSG_TYPE_CRED, HandshakeFrame.MSG_TYPE_ACK)
        )

        assertThat(frame.type).isEqualTo(HandshakeFrame.MSG_TYPE_ACK)
        assertThat(frame.payload).isEqualTo(payload)
    }
}
