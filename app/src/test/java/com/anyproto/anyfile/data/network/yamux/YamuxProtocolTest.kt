package com.anyproto.anyfile.data.network.yamux

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Unit tests for Yamux protocol frame encoding/decoding.
 *
 * Tests the yamux protocol frame format:
 * - Version (1 byte): 0x00
 * - Type (1 byte): DATA(0x00), WINDOW_UPDATE(0x01), PING(0x02), GO_AWAY(0x03)
 * - Flags (2 bytes): SYN(0x01), ACK(0x02), FIN(0x04), RST(0x08)
 * - Length (4 bytes): payload length for DATA frames
 * - StreamID (4 bytes): stream identifier
 *
 * Total header: 12 bytes + payload
 */
class YamuxProtocolTest {

    @Test
    fun `encodeFrame should encode DataFrame with SYN flag correctly`() {
        // Arrange
        val frame = YamuxFrame.Data(
            streamId = 1,
            flags = setOf(YamuxFrame.Flag.SYN),
            data = byteArrayOf(1, 2, 3, 4, 5)
        )

        // Act
        val encoded = YamuxProtocol.encodeFrame(frame)

        // Assert
        // Version: 0x00 (1 byte)
        assertEquals(0x00, encoded[0].toInt() and 0xFF)

        // Type: DATA = 0x00 (1 byte)
        assertEquals(0x00, encoded[1].toInt() and 0xFF)

        // Flags: SYN = 0x01 (2 bytes, big-endian)
        assertEquals(0x00, encoded[2].toInt() and 0xFF)
        assertEquals(0x01, encoded[3].toInt() and 0xFF)

        // StreamID: 1 (4 bytes, big-endian) — bytes 4-7 per hashicorp/yamux spec
        assertEquals(0x00, encoded[4].toInt() and 0xFF)
        assertEquals(0x00, encoded[5].toInt() and 0xFF)
        assertEquals(0x00, encoded[6].toInt() and 0xFF)
        assertEquals(0x01, encoded[7].toInt() and 0xFF)

        // Length: 5 (4 bytes, big-endian) — bytes 8-11
        assertEquals(0x00, encoded[8].toInt() and 0xFF)
        assertEquals(0x00, encoded[9].toInt() and 0xFF)
        assertEquals(0x00, encoded[10].toInt() and 0xFF)
        assertEquals(0x05, encoded[11].toInt() and 0xFF)

        // Payload
        assertEquals(5, encoded.size - 12)
        assertEquals(1, encoded[12].toInt() and 0xFF)
        assertEquals(2, encoded[13].toInt() and 0xFF)
        assertEquals(3, encoded[14].toInt() and 0xFF)
        assertEquals(4, encoded[15].toInt() and 0xFF)
        assertEquals(5, encoded[16].toInt() and 0xFF)
    }

    @Test
    fun `encodeFrame should encode DataFrame with ACK flag correctly`() {
        // Arrange
        val frame = YamuxFrame.Data(
            streamId = 2,
            flags = setOf(YamuxFrame.Flag.ACK),
            data = byteArrayOf()
        )

        // Act
        val encoded = YamuxProtocol.encodeFrame(frame)

        // Assert - check flags
        assertEquals(0x00, encoded[2].toInt() and 0xFF)
        assertEquals(0x02, encoded[3].toInt() and 0xFF)
    }

    @Test
    fun `encodeFrame should encode DataFrame with multiple flags correctly`() {
        // Arrange
        val frame = YamuxFrame.Data(
            streamId = 3,
            flags = setOf(YamuxFrame.Flag.SYN, YamuxFrame.Flag.ACK),
            data = byteArrayOf(10, 20)
        )

        // Act
        val encoded = YamuxProtocol.encodeFrame(frame)

        // Assert - flags should be OR'd: 0x01 | 0x02 = 0x03
        assertEquals(0x00, encoded[2].toInt() and 0xFF)
        assertEquals(0x03, encoded[3].toInt() and 0xFF)
    }

    @Test
    fun `encodeFrame should encode WindowUpdateFrame correctly`() {
        // Arrange
        val frame = YamuxFrame.WindowUpdate(
            streamId = 5,
            flags = setOf(YamuxFrame.Flag.SYN),
            delta = 1024
        )

        // Act
        val encoded = YamuxProtocol.encodeFrame(frame)

        // Assert
        assertEquals(0x00, encoded[0].toInt() and 0xFF) // Version
        assertEquals(0x01, encoded[1].toInt() and 0xFF) // Type: WINDOW_UPDATE
        assertEquals(0x00, encoded[2].toInt() and 0xFF)
        assertEquals(0x01, encoded[3].toInt() and 0xFF) // Flags: SYN

        // StreamID: 5 (4 bytes, big-endian) — bytes 4-7 per hashicorp/yamux spec
        assertEquals(0x00, encoded[4].toInt() and 0xFF)
        assertEquals(0x00, encoded[5].toInt() and 0xFF)
        assertEquals(0x00, encoded[6].toInt() and 0xFF)
        assertEquals(0x05, encoded[7].toInt() and 0xFF)

        // Delta: 1024 = 0x00000400 (4 bytes, big-endian) — bytes 8-11
        assertEquals(0x00, encoded[8].toInt() and 0xFF)
        assertEquals(0x00, encoded[9].toInt() and 0xFF)
        assertEquals(0x04, encoded[10].toInt() and 0xFF)
        assertEquals(0x00, encoded[11].toInt() and 0xFF)
    }

    @Test
    fun `encodeFrame should encode PingFrame correctly`() {
        // Arrange
        val frame = YamuxFrame.Ping(
            flags = setOf(YamuxFrame.Flag.SYN),
            value = 12345
        )

        // Act
        val encoded = YamuxProtocol.encodeFrame(frame)

        // Assert
        assertEquals(0x00, encoded[0].toInt() and 0xFF) // Version
        assertEquals(0x02, encoded[1].toInt() and 0xFF) // Type: PING
        assertEquals(0x00, encoded[2].toInt() and 0xFF)
        assertEquals(0x01, encoded[3].toInt() and 0xFF) // Flags: SYN

        // StreamID: 0 (always 0 for PING) — bytes 4-7 per hashicorp/yamux spec
        assertEquals(0x00, encoded[4].toInt() and 0xFF)
        assertEquals(0x00, encoded[5].toInt() and 0xFF)
        assertEquals(0x00, encoded[6].toInt() and 0xFF)
        assertEquals(0x00, encoded[7].toInt() and 0xFF)

        // Ping value: 12345 = 0x3039 — bytes 8-11
        assertEquals(0x00, encoded[8].toInt() and 0xFF)
        assertEquals(0x00, encoded[9].toInt() and 0xFF)
        assertEquals(0x30, encoded[10].toInt() and 0xFF)
        assertEquals(0x39, encoded[11].toInt() and 0xFF)
    }

    @Test
    fun `encodeFrame should encode GoAwayFrame correctly`() {
        // Arrange
        val frame = YamuxFrame.GoAway(
            flags = emptySet(),
            errorCode = YamuxFrame.GoAwayErrorCode.NORMAL_TERMINATION
        )

        // Act
        val encoded = YamuxProtocol.encodeFrame(frame)

        // Assert
        assertEquals(0x00, encoded[0].toInt() and 0xFF) // Version
        assertEquals(0x03, encoded[1].toInt() and 0xFF) // Type: GO_AWAY

        // Length is the error code
        assertEquals(0x00, encoded[4].toInt() and 0xFF)
        assertEquals(0x00, encoded[5].toInt() and 0xFF)
        assertEquals(0x00, encoded[6].toInt() and 0xFF)
        assertEquals(0x00, encoded[7].toInt() and 0xFF) // NORMAL_TERMINATION = 0
    }

    @Test
    fun `readFrame should decode DataFrame correctly`() {
        // Arrange
        val header = byteArrayOf(
            0x00,                   // Version
            0x00,                   // Type: DATA
            0x00, 0x01.toByte(),   // Flags: SYN
            0x00, 0x00, 0x00, 0x01, // StreamID: 1 (bytes 4-7 per hashicorp/yamux spec)
            0x00, 0x00, 0x00, 0x05  // Length: 5 (bytes 8-11)
        )
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val frameBytes = header + payload
        val inputStream = ByteArrayInputStream(frameBytes)

        // Act
        val frame = YamuxProtocol.readFrame(inputStream)

        // Assert
        assertTrue(frame is YamuxFrame.Data)
        assertEquals(1, frame.streamId)
        assertTrue(frame.flags.contains(YamuxFrame.Flag.SYN))
        assertEquals(5, frame.data.size)
        assertEquals(1, frame.data[0].toInt() and 0xFF)
        assertEquals(5, frame.data[4].toInt() and 0xFF)
    }

    @Test
    fun `readFrame should decode WindowUpdateFrame correctly`() {
        // Arrange
        val header = byteArrayOf(
            0x00,                   // Version
            0x01,                   // Type: WINDOW_UPDATE
            0x00, 0x01.toByte(),   // Flags: SYN
            0x00, 0x00, 0x00, 0x05, // StreamID: 5 (bytes 4-7 per hashicorp/yamux spec)
            0x00, 0x00, 0x04, 0x00  // Delta: 1024 (bytes 8-11)
        )
        val inputStream = ByteArrayInputStream(header)

        // Act
        val frame = YamuxProtocol.readFrame(inputStream)

        // Assert
        assertTrue(frame is YamuxFrame.WindowUpdate)
        assertEquals(5, frame.streamId)
        assertTrue(frame.flags.contains(YamuxFrame.Flag.SYN))
        assertEquals(1024, frame.delta)
    }

    @Test
    fun `readFrame should decode PingFrame correctly`() {
        // Arrange
        val header = byteArrayOf(
            0x00,                   // Version
            0x02,                   // Type: PING
            0x00, 0x01.toByte(),   // Flags: SYN
            0x00, 0x00, 0x00, 0x00, // StreamID: 0 (always 0 for PING, bytes 4-7)
            0x00, 0x00, 0x30, 0x39  // Ping value: 12345 (bytes 8-11)
        )
        val inputStream = ByteArrayInputStream(header)

        // Act
        val frame = YamuxProtocol.readFrame(inputStream)

        // Assert
        assertTrue(frame is YamuxFrame.Ping)
        assertTrue(frame.flags.contains(YamuxFrame.Flag.SYN))
        assertEquals(12345, frame.value)
    }

    @Test
    fun `readFrame should decode GoAwayFrame correctly`() {
        // Arrange
        val header = byteArrayOf(
            0x00,                   // Version
            0x03,                   // Type: GO_AWAY
            0x00, 0x00,            // Flags: none
            0x00, 0x00, 0x00, 0x00, // StreamID: 0 (always 0 for GO_AWAY, bytes 4-7)
            0x00, 0x00, 0x00, 0x02  // Error code: 2 = RECEIVED_GO_AWAY (bytes 8-11)
        )
        val inputStream = ByteArrayInputStream(header)

        // Act
        val frame = YamuxProtocol.readFrame(inputStream)

        // Assert
        assertTrue(frame is YamuxFrame.GoAway)
        assertEquals(YamuxFrame.GoAwayErrorCode.RECEIVED_GO_AWAY, frame.errorCode)
    }

    @Test
    fun `readFrame should throw exception for invalid version`() {
        // Arrange
        val header = byteArrayOf(
            0x01,                   // Invalid version
            0x00,                   // Type: DATA
            0x00, 0x01.toByte(),   // Flags: SYN
            0x00, 0x00, 0x00, 0x00, // Length: 0
            0x00, 0x00, 0x00, 0x01  // StreamID: 1
        )
        val inputStream = ByteArrayInputStream(header)

        // Act & Assert
        assertFailsWith<YamuxProtocolException> {
            YamuxProtocol.readFrame(inputStream)
        }
    }

    @Test
    fun `readFrame should throw exception for invalid frame type`() {
        // Arrange
        val header = byteArrayOf(
            0x00,                   // Version
            0x05.toByte(),          // Invalid type
            0x00, 0x01.toByte(),   // Flags: SYN
            0x00, 0x00, 0x00, 0x00, // Length: 0
            0x00, 0x00, 0x00, 0x01  // StreamID: 1
        )
        val inputStream = ByteArrayInputStream(header)

        // Act & Assert
        assertFailsWith<YamuxProtocolException> {
            YamuxProtocol.readFrame(inputStream)
        }
    }

    @Test
    fun `readFrame should throw exception for incomplete header`() {
        // Arrange
        val incompleteHeader = byteArrayOf(0x00, 0x00, 0x00)
        val inputStream = ByteArrayInputStream(incompleteHeader)

        // Act & Assert
        assertFailsWith<YamuxProtocolException> {
            YamuxProtocol.readFrame(inputStream)
        }
    }

    @Test
    fun `readFrame should throw exception for incomplete DataFrame payload`() {
        // Arrange
        val header = byteArrayOf(
            0x00,                   // Version
            0x00,                   // Type: DATA
            0x00, 0x01.toByte(),   // Flags: SYN
            0x00, 0x00, 0x00, 0x01, // StreamID: 1 (bytes 4-7)
            0x00, 0x00, 0x00, 0x0A  // Length: 10 (bytes 8-11)
        )
        val incompletePayload = byteArrayOf(1, 2, 3) // Only 3 bytes instead of 10
        val frameBytes = header + incompletePayload
        val inputStream = ByteArrayInputStream(frameBytes)

        // Act & Assert
        assertFailsWith<YamuxProtocolException> {
            YamuxProtocol.readFrame(inputStream)
        }
    }

    @Test
    fun `encodeFrame and readFrame should be symmetric for DataFrame`() {
        // Arrange
        val originalFrame = YamuxFrame.Data(
            streamId = 42,
            flags = setOf(YamuxFrame.Flag.SYN, YamuxFrame.Flag.ACK),
            data = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte())
        )

        // Act
        val encoded = YamuxProtocol.encodeFrame(originalFrame)
        val decodedFrame = YamuxProtocol.readFrame(ByteArrayInputStream(encoded))

        // Assert
        assertTrue(decodedFrame is YamuxFrame.Data)
        assertEquals(originalFrame.streamId, decodedFrame.streamId)
        assertEquals(originalFrame.flags, decodedFrame.flags)
        assertTrue(decodedFrame.data.contentEquals(originalFrame.data))
    }

    @Test
    fun `encodeFrame and readFrame should be symmetric for WindowUpdateFrame`() {
        // Arrange
        val originalFrame = YamuxFrame.WindowUpdate(
            streamId = 99,
            flags = setOf(YamuxFrame.Flag.ACK),
            delta = 65536
        )

        // Act
        val encoded = YamuxProtocol.encodeFrame(originalFrame)
        val decodedFrame = YamuxProtocol.readFrame(ByteArrayInputStream(encoded))

        // Assert
        assertTrue(decodedFrame is YamuxFrame.WindowUpdate)
        assertEquals(originalFrame.streamId, decodedFrame.streamId)
        assertEquals(originalFrame.flags, decodedFrame.flags)
        assertEquals(originalFrame.delta, decodedFrame.delta)
    }

    @Test
    fun `encodeFrame and readFrame should be symmetric for PingFrame`() {
        // Arrange
        val originalFrame = YamuxFrame.Ping(
            flags = setOf(YamuxFrame.Flag.ACK),
            value = 99999
        )

        // Act
        val encoded = YamuxProtocol.encodeFrame(originalFrame)
        val decodedFrame = YamuxProtocol.readFrame(ByteArrayInputStream(encoded))

        // Assert
        assertTrue(decodedFrame is YamuxFrame.Ping)
        assertEquals(originalFrame.flags, decodedFrame.flags)
        assertEquals(originalFrame.value, decodedFrame.value)
    }

    @Test
    fun `encodeFrame and readFrame should be symmetric for GoAwayFrame`() {
        // Arrange
        val originalFrame = YamuxFrame.GoAway(
            flags = emptySet(),
            errorCode = YamuxFrame.GoAwayErrorCode.RECEIVED_GO_AWAY
        )

        // Act
        val encoded = YamuxProtocol.encodeFrame(originalFrame)
        val decodedFrame = YamuxProtocol.readFrame(ByteArrayInputStream(encoded))

        // Assert
        assertTrue(decodedFrame is YamuxFrame.GoAway)
        assertEquals(originalFrame.errorCode, decodedFrame.errorCode)
    }

    @Test
    fun `DataFrame with FIN flag should be encoded correctly`() {
        // Arrange
        val frame = YamuxFrame.Data(
            streamId = 7,
            flags = setOf(YamuxFrame.Flag.FIN),
            data = byteArrayOf(1, 2, 3)
        )

        // Act
        val encoded = YamuxProtocol.encodeFrame(frame)

        // Assert - FIN flag is 0x04
        assertEquals(0x00, encoded[2].toInt() and 0xFF)
        assertEquals(0x04, encoded[3].toInt() and 0xFF)
    }

    @Test
    fun `DataFrame with RST flag should be encoded correctly`() {
        // Arrange
        val frame = YamuxFrame.Data(
            streamId = 8,
            flags = setOf(YamuxFrame.Flag.RST),
            data = byteArrayOf()
        )

        // Act
        val encoded = YamuxProtocol.encodeFrame(frame)

        // Assert - RST flag is 0x08
        assertEquals(0x00, encoded[2].toInt() and 0xFF)
        assertEquals(0x08, encoded[3].toInt() and 0xFF)
    }

    @Test
    fun `DataFrame with all flags should be encoded correctly`() {
        // Arrange
        val frame = YamuxFrame.Data(
            streamId = 9,
            flags = setOf(
                YamuxFrame.Flag.SYN,
                YamuxFrame.Flag.ACK,
                YamuxFrame.Flag.FIN,
                YamuxFrame.Flag.RST
            ),
            data = byteArrayOf(42)
        )

        // Act
        val encoded = YamuxProtocol.encodeFrame(frame)

        // Assert - All flags: 0x01 | 0x02 | 0x04 | 0x08 = 0x0F
        assertEquals(0x00, encoded[2].toInt() and 0xFF)
        assertEquals(0x0F, encoded[3].toInt() and 0xFF)
    }

    @Test
    fun `readFrame should decode DataFrame with FIN flag correctly`() {
        // Arrange
        val header = byteArrayOf(
            0x00,                   // Version
            0x00,                   // Type: DATA
            0x00, 0x04.toByte(),   // Flags: FIN
            0x00, 0x00, 0x00, 0x07, // StreamID: 7 (bytes 4-7)
            0x00, 0x00, 0x00, 0x03  // Length: 3 (bytes 8-11)
        )
        val payload = byteArrayOf(1, 2, 3)
        val frameBytes = header + payload
        val inputStream = ByteArrayInputStream(frameBytes)

        // Act
        val frame = YamuxProtocol.readFrame(inputStream)

        // Assert
        assertTrue(frame is YamuxFrame.Data)
        assertEquals(7, frame.streamId)
        assertTrue(frame.flags.contains(YamuxFrame.Flag.FIN))
        assertEquals(3, frame.data.size)
    }

    @Test
    fun `DataFrame with no flags should be encoded correctly`() {
        // Arrange
        val frame = YamuxFrame.Data(
            streamId = 10,
            flags = emptySet(),
            data = byteArrayOf(5, 6, 7)
        )

        // Act
        val encoded = YamuxProtocol.encodeFrame(frame)

        // Assert - No flags = 0x00
        assertEquals(0x00, encoded[2].toInt() and 0xFF)
        assertEquals(0x00, encoded[3].toInt() and 0xFF)
    }

    @Test
    fun `GoAwayErrorCode should have correct values`() {
        // Test error code values
        assertEquals(0, YamuxFrame.GoAwayErrorCode.NORMAL_TERMINATION.code)
        assertEquals(1, YamuxFrame.GoAwayErrorCode.PROTOCOL_ERROR.code)
        assertEquals(2, YamuxFrame.GoAwayErrorCode.RECEIVED_GO_AWAY.code)
        assertEquals(3, YamuxFrame.GoAwayErrorCode.INTERNAL_ERROR.code)
    }
}
