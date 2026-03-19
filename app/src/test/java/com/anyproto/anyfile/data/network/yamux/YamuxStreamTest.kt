package com.anyproto.anyfile.data.network.yamux

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for YamuxStream.
 *
 * Tests stream state management, read/write operations,
 * flow control, and error handling.
 */
class YamuxStreamTest {

    private lateinit var mockSession: YamuxSession
    private lateinit var stream: YamuxStream

    @Before
    fun setup() {
        mockSession = mockk()
        coEvery { mockSession.sendFrame(any()) } just Runs

        // Create a stream with ID 1
        stream = YamuxStream(streamId = 1, session = mockSession, initialWindowSize = 1024)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `stream should be in INIT state when created`() {
        assertEquals(YamuxStream.State.INIT, stream.state)
    }

    @Test
    fun `initialize should send SYN frame and transition to SYN_SENT`() = runTest {
        // Act
        stream.initialize()

        // Assert
        coVerify { mockSession.sendFrame(match { frame ->
            frame is YamuxFrame.Data &&
                frame.streamId == 1 &&
                frame.flags.contains(YamuxFrame.Flag.SYN) &&
                frame.data.isEmpty()
        }) }
        assertEquals(YamuxStream.State.SYN_SENT, stream.state)
    }

    @Test
    fun `accept should send ACK frame and transition to OPEN`() = runTest {
        // Act
        stream.accept()

        // Assert
        coVerify { mockSession.sendFrame(match { frame ->
            frame is YamuxFrame.Data &&
                frame.streamId == 1 &&
                frame.flags.contains(YamuxFrame.Flag.ACK) &&
                frame.data.isEmpty()
        }) }
        assertEquals(YamuxStream.State.OPEN, stream.state)
    }

    @Test
    fun `initialize should throw exception if already initialized`() = runTest {
        // Arrange
        stream.initialize()

        // Act & Assert
        var exceptionThrown = false
        try {
            stream.initialize()
        } catch (e: IllegalStateException) {
            exceptionThrown = true
            assertTrue(e.message?.contains("already initialized") == true)
        }
        assertTrue(exceptionThrown)
    }

    @Test
    fun `handleDataFrame with ACK should transition SYN_SENT to OPEN`() = runTest {
        // Arrange
        stream.initialize()

        // Act
        val ackFrame = YamuxFrame.Data(
            streamId = 1,
            flags = setOf(YamuxFrame.Flag.ACK),
            data = ByteArray(0)
        )
        stream.handleDataFrame(ackFrame)

        // Assert
        assertEquals(YamuxStream.State.OPEN, stream.state)
    }

    @Test
    fun `handleDataFrame with data should queue data for reading`() = runTest {
        // Arrange
        stream.initialize()
        val ackWithData = YamuxFrame.Data(
            streamId = 1,
            flags = setOf(YamuxFrame.Flag.ACK),
            data = byteArrayOf(1, 2, 3, 4, 5)
        )

        // Act
        stream.handleDataFrame(ackWithData)

        // Assert
        val readData = stream.read()
        assertNotNull(readData)
        assertEquals(5, readData.size)
        assertTrue(readData.contentEquals(byteArrayOf(1, 2, 3, 4, 5)))
    }

    @Test
    fun `handleDataFrame with FIN should transition to RECEIVING`() = runTest {
        // Arrange
        stream.initialize()

        // Act
        val finFrame = YamuxFrame.Data(
            streamId = 1,
            flags = setOf(YamuxFrame.Flag.ACK, YamuxFrame.Flag.FIN),
            data = ByteArray(0)
        )
        stream.handleDataFrame(finFrame)

        // Assert
        assertEquals(YamuxStream.State.RECEIVING, stream.state)
        // In RECEIVING state, we can still write (send data to remote)
        assertTrue(stream.canWrite())
        // After FIN, remote won't send more data, so canRead is false
        assertFalse(stream.canRead())
    }

    @Test
    fun `handleDataFrame with RST should transition to RESET`() = runTest {
        // Arrange
        stream.initialize()

        // Act
        val rstFrame = YamuxFrame.Data(
            streamId = 1,
            flags = setOf(YamuxFrame.Flag.RST),
            data = ByteArray(0)
        )
        stream.handleDataFrame(rstFrame)

        // Assert
        assertEquals(YamuxStream.State.RESET, stream.state)
        assertTrue(stream.isClosed())
    }

    @Test
    fun `handleWindowUpdate should increase send window`() = runTest {
        // Arrange - Initialize and get ACK to open the stream
        stream.initialize()
        stream.handleDataFrame(YamuxFrame.Data(1, setOf(YamuxFrame.Flag.ACK), ByteArray(0)))

        // Act
        stream.handleWindowUpdate(512)

        // Assert
        assertEquals(1024 + 512, stream.getSendWindowSize())
    }

    @Test
    fun `handleReset should close stream`() = runTest {
        // Arrange
        stream.initialize()

        // Act
        stream.handleReset()

        // Assert
        assertEquals(YamuxStream.State.RESET, stream.state)
        assertTrue(stream.isClosed())
    }

    @Test
    fun `write should send DATA frame`() = runTest {
        // Arrange - Open the stream
        stream.initialize()
        stream.handleDataFrame(YamuxFrame.Data(1, setOf(YamuxFrame.Flag.ACK), ByteArray(0)))

        // Act
        val testData = byteArrayOf(10, 20, 30)
        stream.write(testData)

        // Assert
        coVerify { mockSession.sendFrame(match { frame ->
            frame is YamuxFrame.Data &&
                frame.streamId == 1 &&
                frame.data.contentEquals(testData)
        }) }
    }

    @Test
    fun `write should decrease send window`() = runTest {
        // Arrange - Open the stream
        stream.initialize()
        stream.handleDataFrame(YamuxFrame.Data(1, setOf(YamuxFrame.Flag.ACK), ByteArray(0)))

        // Act
        val testData = byteArrayOf(1, 2, 3)
        stream.write(testData)

        // Assert
        assertEquals(1024 - 3, stream.getSendWindowSize())
    }

    @Test
    fun `write should throw exception if window exhausted`() = runTest {
        // Arrange - Create stream with small window
        val smallWindowStream = YamuxStream(1, mockSession, initialWindowSize = 2)
        smallWindowStream.initialize()
        smallWindowStream.handleDataFrame(YamuxFrame.Data(1, setOf(YamuxFrame.Flag.ACK), ByteArray(0)))

        // Act & Assert
        var exceptionThrown = false
        try {
            smallWindowStream.write(byteArrayOf(1, 2, 3)) // 3 bytes > 2 byte window
        } catch (e: YamuxStreamException) {
            exceptionThrown = true
            assertTrue(e.message?.contains("window exhausted") == true)
        }
        assertTrue(exceptionThrown)
    }

    @Test
    fun `write should throw exception if stream closed`() = runTest {
        // Arrange
        stream.close()

        // Act & Assert
        var exceptionThrown = false
        try {
            stream.write(byteArrayOf(1, 2, 3))
        } catch (e: IllegalStateException) {
            exceptionThrown = true
            assertTrue(e.message?.contains("closed") == true)
        }
        assertTrue(exceptionThrown)
    }

    @Test
    fun `read should return queued data`() = runTest {
        // Arrange
        stream.initialize()
        val testData = byteArrayOf(5, 6, 7)
        stream.handleDataFrame(YamuxFrame.Data(1, setOf(YamuxFrame.Flag.ACK), testData))

        // Act
        val readData = stream.read()

        // Assert
        assertNotNull(readData)
        assertTrue(readData.contentEquals(testData))
    }

    @Test
    fun `read should return null when stream is closed remotely`() = runTest {
        // Arrange
        stream.initialize()
        stream.handleDataFrame(YamuxFrame.Data(1, setOf(YamuxFrame.Flag.FIN), ByteArray(0)))

        // Act
        val readData = stream.read(timeoutMs = 100)

        // Assert
        assertEquals(null, readData)
    }

    @Test
    fun `read should decrease receive window`() = runTest {
        // Arrange
        stream.initialize()
        val testData = ByteArray(100)
        stream.handleDataFrame(YamuxFrame.Data(1, setOf(YamuxFrame.Flag.ACK), testData))

        // Act - Read the data
        stream.read()

        // Assert - Window decreased by data size (100 bytes); stream was created with initialWindowSize=1024
        assertEquals(1024 - 100, stream.getReceiveWindowSize())
    }

    @Test
    fun `closeWrite should send FIN frame and transition to SENDING`() = runTest {
        // Arrange - Open the stream
        stream.initialize()
        stream.handleDataFrame(YamuxFrame.Data(1, setOf(YamuxFrame.Flag.ACK), ByteArray(0)))

        // Act
        stream.closeWrite()

        // Assert
        coVerify { mockSession.sendFrame(match { frame ->
            frame is YamuxFrame.Data &&
                frame.streamId == 1 &&
                frame.flags.contains(YamuxFrame.Flag.FIN)
        }) }
        assertEquals(YamuxStream.State.SENDING, stream.state)
        assertFalse(stream.canWrite())
    }

    @Test
    fun `closeRead should transition to RECEIVING`() = runTest {
        // Arrange - Open the stream
        stream.initialize()
        stream.handleDataFrame(YamuxFrame.Data(1, setOf(YamuxFrame.Flag.ACK), ByteArray(0)))

        // Act
        stream.closeRead()

        // Assert
        assertEquals(YamuxStream.State.RECEIVING, stream.state)
        assertFalse(stream.canRead())
    }

    @Test
    fun `close should send RST frame and close stream`() = runTest {
        // Arrange - Open the stream
        stream.initialize()
        stream.handleDataFrame(YamuxFrame.Data(1, setOf(YamuxFrame.Flag.ACK), ByteArray(0)))

        // Act
        stream.close()

        // Assert
        coVerify { mockSession.sendFrame(match { frame ->
            frame is YamuxFrame.Data &&
                frame.streamId == 1 &&
                frame.flags.contains(YamuxFrame.Flag.RST)
        }) }
        assertTrue(stream.isClosed())
        assertEquals(YamuxStream.State.CLOSED, stream.state)
    }

    @Test
    fun `close with cause should transition to RESET`() = runTest {
        // Arrange
        stream.initialize()

        // Act
        stream.close(IllegalStateException("Test error"))

        // Assert
        assertEquals(YamuxStream.State.RESET, stream.state)
        assertTrue(stream.isClosed())
    }

    @Test
    fun `isClosed should return true when stream is closed`() = runTest {
        // Arrange & Act
        stream.close()

        // Assert
        assertTrue(stream.isClosed())
    }

    @Test
    fun `canRead should return true when stream is open`() = runTest {
        // Arrange - Open the stream
        stream.initialize()
        stream.handleDataFrame(YamuxFrame.Data(1, setOf(YamuxFrame.Flag.ACK), ByteArray(0)))

        // Act & Assert
        assertTrue(stream.canRead())
    }

    @Test
    fun `canRead should return false when FIN received`() = runTest {
        // Arrange
        stream.initialize()
        stream.handleDataFrame(YamuxFrame.Data(1, setOf(YamuxFrame.Flag.FIN), ByteArray(0)))

        // Act & Assert
        assertFalse(stream.canRead())
    }

    @Test
    fun `canWrite should return true when stream is open`() = runTest {
        // Arrange - Open the stream
        stream.initialize()
        stream.handleDataFrame(YamuxFrame.Data(1, setOf(YamuxFrame.Flag.ACK), ByteArray(0)))

        // Act & Assert
        assertTrue(stream.canWrite())
    }

    @Test
    fun `canWrite should return false after closeWrite`() = runTest {
        // Arrange
        stream.initialize()
        stream.handleDataFrame(YamuxFrame.Data(1, setOf(YamuxFrame.Flag.ACK), ByteArray(0)))

        // Act
        stream.closeWrite()

        // Assert
        assertFalse(stream.canWrite())
    }

    @Test
    fun `stream should transition to CLOSED when both FIN sent and received`() = runTest {
        // Arrange - Open the stream
        stream.initialize()
        stream.handleDataFrame(YamuxFrame.Data(1, setOf(YamuxFrame.Flag.ACK), ByteArray(0)))

        // Act - Close write first
        stream.closeWrite()
        // Then receive FIN
        stream.handleDataFrame(YamuxFrame.Data(1, setOf(YamuxFrame.Flag.FIN), ByteArray(0)))

        // Assert
        assertEquals(YamuxStream.State.CLOSED, stream.state)
    }

    @Test
    fun `stream should transition to CLOSED when FIN received first then closeWrite`() = runTest {
        // Arrange - Open the stream
        stream.initialize()
        stream.handleDataFrame(YamuxFrame.Data(1, setOf(YamuxFrame.Flag.ACK), ByteArray(0)))

        // Act - Receive FIN first
        stream.handleDataFrame(YamuxFrame.Data(1, setOf(YamuxFrame.Flag.FIN), ByteArray(0)))
        // Then close write
        stream.closeWrite()

        // Assert
        assertEquals(YamuxStream.State.CLOSED, stream.state)
    }

    @Test
    fun `stream should ignore data frames after close`() = runTest {
        // Arrange
        stream.close()
        // Clear the mock - close() already sent a RST frame
        clearMocks(mockSession)

        // Act - Try to handle data frame after close
        stream.handleDataFrame(YamuxFrame.Data(
            streamId = 1,
            flags = setOf(YamuxFrame.Flag.ACK),
            data = byteArrayOf(1, 2, 3)
        ))

        // Assert - No exception should be thrown, data should be ignored
        // After clearing mocks, no additional sendFrame calls should be made
        coVerify(exactly = 0) { mockSession.sendFrame(any()) }
    }

    @Test
    fun `closeWrite should be idempotent`() = runTest {
        // Arrange
        stream.initialize()
        stream.handleDataFrame(YamuxFrame.Data(1, setOf(YamuxFrame.Flag.ACK), ByteArray(0)))

        // Act - Close write twice
        stream.closeWrite()
        stream.closeWrite()

        // Assert - Only one FIN should be sent
        coVerify(exactly = 1) { mockSession.sendFrame(match {
            it is YamuxFrame.Data && it.flags.contains(YamuxFrame.Flag.FIN)
        }) }
    }

    @Test
    fun `close should be idempotent`() = runTest {
        // Arrange
        stream.initialize()

        // Act - Close twice
        stream.close()
        stream.close()

        // Assert - Only one RST should be sent
        coVerify(exactly = 1) { mockSession.sendFrame(match {
            it is YamuxFrame.Data && it.flags.contains(YamuxFrame.Flag.RST)
        }) }
    }

    @Test
    fun `getReceiveWindowSize should return current window size`() {
        // Act & Assert
        assertEquals(1024, stream.getReceiveWindowSize())
    }

    @Test
    fun `getSendWindowSize should return current window size`() {
        // Act & Assert
        assertEquals(1024, stream.getSendWindowSize())
    }
}
