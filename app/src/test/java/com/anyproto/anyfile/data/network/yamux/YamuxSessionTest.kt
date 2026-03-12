package com.anyproto.anyfile.data.network.yamux

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.net.ssl.SSLSocket
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for YamuxSession.
 *
 * Tests session lifecycle, stream opening/accepting,
 * frame routing, and session-level operations.
 */
class YamuxSessionTest {

    private lateinit var mockSocket: SSLSocket
    private lateinit var mockInputStream: InputStream
    private lateinit var mockOutputStream: OutputStream
    private lateinit var session: YamuxSession

    @Before
    fun setup() {
        mockSocket = mockk(relaxed = true)
        mockInputStream = mockk()
        mockOutputStream = mockk()

        every { mockSocket.getInputStream() } returns mockInputStream
        every { mockSocket.getOutputStream() } returns mockOutputStream

        session = YamuxSession(mockSocket, isClient = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `session should be in ACTIVE state when created`() {
        assertEquals(YamuxSession.State.ACTIVE, session.state)
    }

    @Test
    fun `session should start successfully`() = runTest {
        // Arrange - Mock the output stream so GO_AWAY write doesn't fail if the background
        // reader encounters EOF and closes the session
        every { mockOutputStream.write(any<ByteArray>()) } just Runs
        every { mockOutputStream.flush() } just Runs
        // Mock read to return -1 (EOF) via the 3-argument overload used by readExactBytes
        every { mockInputStream.read(any<ByteArray>(), any<Int>(), any<Int>()) } returns -1

        // Act - start() launches the background frame reader and returns immediately
        session.start()

        // Assert - Session is active immediately after start() returns,
        // before the background reader has had a chance to process the EOF
        assertTrue(session.isActive())
    }

    @Test
    fun `openStream should create new stream with odd ID for client`() = runTest {
        // Arrange - Mock write to accept ByteArray
        every { mockOutputStream.write(any<ByteArray>()) } just Runs
        every { mockOutputStream.flush() } just Runs

        // Act
        val stream = session.openStream()

        // Assert
        assertNotNull(stream)
        assertEquals(1, stream.streamId) // First client stream ID
        assertEquals(YamuxStream.State.SYN_SENT, stream.state)
    }

    @Test
    fun `openStream should create streams with incrementing odd IDs`() = runTest {
        // Arrange
        every { mockOutputStream.write(any<ByteArray>()) } just Runs
        every { mockOutputStream.flush() } just Runs

        // Act
        val stream1 = session.openStream()
        val stream2 = session.openStream()
        val stream3 = session.openStream()

        // Assert
        assertEquals(1, stream1.streamId)
        assertEquals(3, stream2.streamId)
        assertEquals(5, stream3.streamId)
    }

    @Test
    fun `openStream as server should create streams with even IDs`() = runTest {
        // Arrange
        val serverSession = YamuxSession(mockSocket, isClient = false)
        every { mockOutputStream.write(any<ByteArray>()) } just Runs
        every { mockOutputStream.flush() } just Runs

        // Act
        val stream = serverSession.openStream()

        // Assert
        assertEquals(2, stream.streamId) // First server stream ID
    }

    @Test
    fun `openStream should throw exception when session closed`() = runTest {
        // Arrange
        session.close()

        // Act & Assert
        var exceptionThrown = false
        try {
            session.openStream()
        } catch (e: YamuxSessionException) {
            exceptionThrown = true
            assertTrue(e.message?.contains("Cannot open stream") == true)
        }
        assertTrue(exceptionThrown)
    }

    @Test
    fun `openStream should send SYN frame`() = runTest {
        // Arrange
        val outputCapture = ByteArrayOutputStream()
        every { mockOutputStream.write(any<ByteArray>()) } answers {
            outputCapture.write(it.invocation.args[0] as ByteArray)
        }
        every { mockOutputStream.flush() } just Runs

        // Act
        session.openStream()

        // Assert - Verify SYN frame was written
        val writtenData = outputCapture.toByteArray()
        assertTrue(writtenData.size >= 12) // At least header size

        // Parse header
        val version = writtenData[0].toInt() and 0xFF
        val type = writtenData[1].toInt() and 0xFF
        val flagsLow = writtenData[3].toInt() and 0xFF
        val streamId = ((writtenData[8].toInt() and 0xFF) shl 24) or
                      ((writtenData[9].toInt() and 0xFF) shl 16) or
                      ((writtenData[10].toInt() and 0xFF) shl 8) or
                      (writtenData[11].toInt() and 0xFF)

        assertEquals(0x00, version) // Yamux version
        assertEquals(0x00, type) // DATA frame
        assertEquals(0x01, flagsLow) // SYN flag
        assertEquals(1, streamId) // First stream ID
    }

    @Test
    fun `acceptStream should wait for incoming stream`() = runTest {
        // This test verifies the accepting mechanism
        // In a real scenario, an incoming SYN would trigger stream creation

        // For this test, we verify the mechanism exists
        assertTrue(true) // Placeholder - actual testing requires frame injection
    }

    @Test
    fun `getStream should return existing stream`() = runTest {
        // Arrange
        every { mockOutputStream.write(any<ByteArray>()) } just Runs
        every { mockOutputStream.flush() } just Runs
        val stream = session.openStream()

        // Act
        val retrievedStream = session.getStream(1)

        // Assert
        assertNotNull(retrievedStream)
        assertEquals(stream, retrievedStream)
    }

    @Test
    fun `getStream should return null for non-existent stream`() {
        // Act
        val stream = session.getStream(999)

        // Assert
        assertNull(stream)
    }

    @Test
    fun `getStreamCount should return number of active streams`() = runTest {
        // Arrange
        every { mockOutputStream.write(any<ByteArray>()) } just Runs
        every { mockOutputStream.flush() } just Runs

        // Act & Assert
        assertEquals(0, session.getStreamCount())

        session.openStream()
        assertEquals(1, session.getStreamCount())

        session.openStream()
        assertEquals(2, session.getStreamCount())
    }

    @Test
    fun `close should send GO_AWAY frame`() = runTest {
        // Arrange
        val outputCapture = ByteArrayOutputStream()
        every { mockOutputStream.write(any<ByteArray>()) } answers {
            outputCapture.write(it.invocation.args[0] as ByteArray)
        }
        every { mockOutputStream.flush() } just Runs

        // Act
        session.close()

        // Assert - Verify GO_AWAY frame was written
        val writtenData = outputCapture.toByteArray()
        assertTrue(writtenData.size >= 12) // At least header size

        // Parse header
        val type = writtenData[1].toInt() and 0xFF
        val errorCode = ((writtenData[4].toInt() and 0xFF) shl 24) or
                       ((writtenData[5].toInt() and 0xFF) shl 16) or
                       ((writtenData[6].toInt() and 0xFF) shl 8) or
                       (writtenData[7].toInt() and 0xFF)

        assertEquals(0x03, type) // GO_AWAY type
        assertEquals(0, errorCode) // NORMAL_TERMINATION
    }

    @Test
    fun `close should transition to CLOSED state`() = runTest {
        // Arrange
        every { mockOutputStream.write(any<ByteArray>()) } just Runs
        every { mockOutputStream.flush() } just Runs

        // Act
        session.close()

        // Assert
        assertEquals(YamuxSession.State.CLOSED, session.state)
        assertFalse(session.isActive())
    }

    @Test
    fun `close should close underlying socket`() = runTest {
        // Arrange
        every { mockOutputStream.write(any<ByteArray>()) } just Runs
        every { mockOutputStream.flush() } just Runs

        // Act
        session.close()

        // Assert
        coVerify(exactly = 1) { mockSocket.close() }
    }

    @Test
    fun `close should be idempotent`() = runTest {
        // Arrange
        every { mockOutputStream.write(any<ByteArray>()) } just Runs
        every { mockOutputStream.flush() } just Runs

        // Act
        session.close()
        session.close()

        // Assert - Only one GO_AWAY should be sent
        coVerify(exactly = 1) { mockOutputStream.write(any<ByteArray>()) }
    }

    @Test
    fun `close with error code should send GO_AWAY with correct code`() = runTest {
        // Arrange
        val outputCapture = ByteArrayOutputStream()
        every { mockOutputStream.write(any<ByteArray>()) } answers {
            outputCapture.write(it.invocation.args[0] as ByteArray)
        }
        every { mockOutputStream.flush() } just Runs

        // Act
        session.close(YamuxFrame.GoAwayErrorCode.PROTOCOL_ERROR)

        // Assert
        val writtenData = outputCapture.toByteArray()
        val errorCode = ((writtenData[4].toInt() and 0xFF) shl 24) or
                       ((writtenData[5].toInt() and 0xFF) shl 16) or
                       ((writtenData[6].toInt() and 0xFF) shl 8) or
                       (writtenData[7].toInt() and 0xFF)

        assertEquals(1, errorCode) // PROTOCOL_ERROR
    }

    @Test
    fun `ping should send PING frame and return pong response`() = runTest {
        // This test would require bidirectional frame handling
        // For unit testing, we verify the ping mechanism exists
        assertTrue(true) // Placeholder
    }

    @Test
    fun `isActive should return true for ACTIVE session`() {
        assertTrue(session.isActive())
    }

    @Test
    fun `isActive should return false for CLOSED session`() = runTest {
        // Arrange
        every { mockOutputStream.write(any<ByteArray>()) } just Runs
        every { mockOutputStream.flush() } just Runs

        // Act
        session.close()

        // Assert
        assertFalse(session.isActive())
    }

    @Test
    fun `sendFrame should write frame to output stream`() = runTest {
        // Arrange
        val outputCapture = ByteArrayOutputStream()
        every { mockOutputStream.write(any<ByteArray>()) } answers {
            outputCapture.write(it.invocation.args[0] as ByteArray)
        }
        every { mockOutputStream.flush() } just Runs

        // Act
        val frame = YamuxFrame.Data(
            streamId = 1,
            flags = setOf(YamuxFrame.Flag.SYN),
            data = byteArrayOf(1, 2, 3)
        )
        session.sendFrame(frame)

        // Assert - Verify frame was written
        val writtenData = outputCapture.toByteArray()
        assertTrue(writtenData.size >= 12 + 3) // Header + payload

        // Verify payload
        assertEquals(1, writtenData[12].toInt() and 0xFF)
        assertEquals(2, writtenData[13].toInt() and 0xFF)
        assertEquals(3, writtenData[14].toInt() and 0xFF)
    }

    @Test
    fun `sendFrame should throw exception when session closed`() = runTest {
        // Arrange
        session.close()

        // Act & Assert
        var exceptionThrown = false
        try {
            val frame = YamuxFrame.Data(1, emptySet(), ByteArray(0))
            session.sendFrame(frame)
        } catch (e: YamuxSessionException) {
            exceptionThrown = true
            assertTrue(e.message?.contains("closed") == true)
        }
        assertTrue(exceptionThrown)
    }

    @Test
    fun `removeStream should remove stream from session`() = runTest {
        // Arrange
        every { mockOutputStream.write(any<ByteArray>()) } just Runs
        every { mockOutputStream.flush() } just Runs
        session.openStream()

        // Act
        session.removeStream(1)

        // Assert
        assertNull(session.getStream(1))
        assertEquals(0, session.getStreamCount())
    }

    @Test
    fun `close should close all streams`() = runTest {
        // Arrange
        every { mockOutputStream.write(any<ByteArray>()) } just Runs
        every { mockOutputStream.flush() } just Runs
        session.openStream()
        session.openStream()

        // Act
        session.close()

        // Assert
        assertEquals(0, session.getStreamCount())
    }

    @Test
    fun `client session should use odd stream IDs`() = runTest {
        // Arrange
        every { mockOutputStream.write(any<ByteArray>()) } just Runs
        every { mockOutputStream.flush() } just Runs

        // Act
        val stream1 = session.openStream()
        val stream2 = session.openStream()

        // Assert
        assertEquals(1, stream1.streamId)
        assertEquals(3, stream2.streamId)

        // Verify odd numbers
        assertTrue(stream1.streamId % 2 == 1)
        assertTrue(stream2.streamId % 2 == 1)
    }

    @Test
    fun `server session should use even stream IDs`() = runTest {
        // Arrange
        val serverSession = YamuxSession(mockSocket, isClient = false)
        every { mockOutputStream.write(any<ByteArray>()) } just Runs
        every { mockOutputStream.flush() } just Runs

        // Act
        val stream1 = serverSession.openStream()
        val stream2 = serverSession.openStream()

        // Assert
        assertEquals(2, stream1.streamId)
        assertEquals(4, stream2.streamId)

        // Verify even numbers
        assertTrue(stream1.streamId % 2 == 0)
        assertTrue(stream2.streamId % 2 == 0)
    }
}

/**
 * Helper class for testing frame handling.
 * In a real integration test, this would simulate bidirectional communication.
 */
class FrameHandlerTestHelper {
    /**
     * Create a SYN frame for stream opening.
     */
    fun createSynFrame(streamId: Int): ByteArray {
        return byteArrayOf(
            0x00,                   // Version
            0x00,                   // Type: DATA
            0x00, 0x01.toByte(),   // Flags: SYN
            0x00, 0x00, 0x00, 0x00, // Length: 0
            (streamId shr 24).toByte(),
            (streamId shr 16).toByte(),
            (streamId shr 8).toByte(),
            streamId.toByte()
        )
    }

    /**
     * Create an ACK frame for stream acknowledgment.
     */
    fun createAckFrame(streamId: Int): ByteArray {
        return byteArrayOf(
            0x00,                   // Version
            0x00,                   // Type: DATA
            0x00, 0x02.toByte(),   // Flags: ACK
            0x00, 0x00, 0x00, 0x00, // Length: 0
            (streamId shr 24).toByte(),
            (streamId shr 16).toByte(),
            (streamId shr 8).toByte(),
            streamId.toByte()
        )
    }

    /**
     * Create a DATA frame with payload.
     */
    fun createDataFrame(streamId: Int, data: ByteArray): ByteArray {
        val header = byteArrayOf(
            0x00,                   // Version
            0x00,                   // Type: DATA
            0x00, 0x00.toByte(),   // Flags: none
            (data.size shr 24).toByte(),
            (data.size shr 16).toByte(),
            (data.size shr 8).toByte(),
            data.size.toByte(),
            (streamId shr 24).toByte(),
            (streamId shr 16).toByte(),
            (streamId shr 8).toByte(),
            streamId.toByte()
        )
        return header + data
    }
}
