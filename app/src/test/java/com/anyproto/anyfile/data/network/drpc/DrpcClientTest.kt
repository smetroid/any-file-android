package com.anyproto.anyfile.data.network.drpc

import com.anyproto.anyfile.data.network.yamux.YamuxFrame
import com.anyproto.anyfile.data.network.yamux.YamuxSession
import com.anyproto.anyfile.data.network.yamux.YamuxStream
import com.anyproto.anyfile.data.network.yamux.YamuxStreamException
import com.anyproto.anyfile.protos.SpaceSignRequest
import com.anyproto.anyfile.protos.SpaceSignResponse
import com.google.protobuf.ByteString
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for DrpcClient.
 */
class DrpcClientTest {

    private lateinit var mockSession: YamuxSession
    private lateinit var mockStream: YamuxStream
    private lateinit var drpcClient: DrpcClient

    @Before
    fun setup() {
        mockSession = mockk()
        mockStream = mockk()
        drpcClient = DrpcClient(mockSession)

        // Default mock behaviors
        every { mockSession.isActive() } returns true
        every { mockSession.getStreamCount() } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `test isActive returns session active status`() {
        every { mockSession.isActive() } returns true
        assertTrue(drpcClient.isActive())

        every { mockSession.isActive() } returns false
        assertTrue(!drpcClient.isActive())
    }

    @Test
    fun `test getStreamCount returns session stream count`() {
        every { mockSession.getStreamCount() } returns 5
        assertEquals(5, drpcClient.getStreamCount())
    }

    @Test
    fun `test successful RPC call`() = runTest {
        // Setup test request
        val request = SpaceSignRequest.newBuilder()
            .setSpaceId("test-space-id")
            .setHeader(ByteString.copyFrom(byteArrayOf(1, 2, 3)))
            .setForceRequest(false)
            .build()

        val expectedReceipt = com.anyproto.anyfile.protos.SpaceReceiptWithSignature.newBuilder()
            .setSpaceReceiptPayload(ByteString.copyFrom(byteArrayOf(4, 5, 6)))
            .setSignature(ByteString.copyFrom(byteArrayOf(7, 8, 9)))
            .build()

        val expectedResponse = SpaceSignResponse.newBuilder()
            .setReceipt(expectedReceipt)
            .build()

        // Mock stream behavior
        coEvery { mockSession.openStream() } returns mockStream
        coEvery { mockStream.write(any()) } just Runs
        coEvery { mockStream.closeWrite() } just Runs
        coEvery { mockStream.close() } just Runs

        // Simulate response: encode a successful response
        val responsePayload = expectedResponse.toByteArray()
        val drpcResponse = DrpcResponse(success = true, response = expectedResponse)
        val encodedResponse = drpcResponse.encode()
        val framedResponse = DrpcEncoding.encodeMessageWithLength(encodedResponse)

        // Mock read to return response data then null (stream closed)
        coEvery { mockStream.read(any()) } returnsMany listOf(framedResponse, null)
        every { mockStream.state } returns YamuxStream.State.CLOSED

        // Execute call
        val actualResponse = drpcClient.callAsync(
            service = "coordinator.Coordinator",
            method = "SpaceSign",
            request = request,
            responseParser = SpaceSignResponse.parser()
        )

        // Verify response
        assertNotNull(actualResponse)
        assertEquals(expectedReceipt.spaceReceiptPayload, actualResponse.receipt.spaceReceiptPayload)
        assertEquals(expectedReceipt.signature, actualResponse.receipt.signature)

        // Verify interactions
        coVerify { mockSession.openStream() }
        coVerify { mockStream.write(any()) }
        coVerify { mockStream.closeWrite() }
    }

    @Test
    fun `test RPC call with server error`() = runTest {
        // Setup test request
        val request = SpaceSignRequest.newBuilder()
            .setSpaceId("test-space-id")
            .build()

        // Mock stream behavior
        coEvery { mockSession.openStream() } returns mockStream
        coEvery { mockStream.write(any()) } just Runs
        coEvery { mockStream.closeWrite() } just Runs
        coEvery { mockStream.close() } just Runs

        // Simulate error response
        val errorMsg = "Space not found"
        val drpcResponse = DrpcResponse(
            success = false,
            errorCode = DrpcMessage.ErrorCode.METHOD_NOT_FOUND,
            errorMessage = errorMsg
        )
        val encodedResponse = drpcResponse.encode()
        val framedResponse = DrpcEncoding.encodeMessageWithLength(encodedResponse)

        coEvery { mockStream.read(any()) } returnsMany listOf(framedResponse, null)
        every { mockStream.state } returns YamuxStream.State.CLOSED

        // Execute and verify exception
        val exception = assertFailsWith<DrpcRpcException> {
            drpcClient.callAsync(
                service = "coordinator.Coordinator",
                method = "SpaceSign",
                request = request,
                responseParser = SpaceSignResponse.parser()
            )
        }

        assertEquals(DrpcMessage.ErrorCode.METHOD_NOT_FOUND, exception.code)
        assertTrue(exception.message?.contains(errorMsg) == true)
    }

    @Test
    fun `test RPC call timeout`() = runTest {
        // Setup test request
        val request = SpaceSignRequest.newBuilder()
            .setSpaceId("test-space-id")
            .build()

        // Mock stream behavior
        coEvery { mockSession.openStream() } returns mockStream
        coEvery { mockStream.write(any()) } just Runs
        coEvery { mockStream.closeWrite() } just Runs
        coEvery { mockStream.close() } just Runs

        // Simulate timeout - use relaxed mock for stream
        val relaxedStream = mockk<YamuxStream>(relaxed = true)
        coEvery { relaxedStream.write(any()) } just Runs
        coEvery { relaxedStream.closeWrite() } just Runs
        coEvery { relaxedStream.close() } just Runs
        coEvery { relaxedStream.read(any()) } throws YamuxStreamException("timeout")

        // We need to also mock the state
        every { relaxedStream.state } returns YamuxStream.State.OPEN

        // Override session to return relaxed stream
        coEvery { mockSession.openStream() } returns relaxedStream

        // Execute and verify timeout exception
        val exception = assertFailsWith<DrpcTimeoutException> {
            drpcClient.callAsync(
                service = "coordinator.Coordinator",
                method = "SpaceSign",
                request = request,
                responseParser = SpaceSignResponse.parser(),
                timeoutMs = 100
            )
        }

        assertTrue(exception.message?.contains("Timeout while reading response") == true)
    }

    @Test
    fun `test RPC call with connection failure`() = runTest {
        // Setup test request
        val request = SpaceSignRequest.newBuilder()
            .setSpaceId("test-space-id")
            .build()

        // Mock session to throw exception
        coEvery { mockSession.openStream() } throws RuntimeException("Connection refused")

        // Execute and verify exception
        val exception = assertFailsWith<DrpcConnectionException> {
            drpcClient.callAsync(
                service = "coordinator.Coordinator",
                method = "SpaceSign",
                request = request,
                responseParser = SpaceSignResponse.parser()
            )
        }

        assertTrue(exception.message?.contains("Failed to open") == true)
    }

    @Test
    fun `test coordinatorCall extension function`() = runTest {
        val request = SpaceSignRequest.newBuilder()
            .setSpaceId("test-space-id")
            .build()

        val expectedResponse = SpaceSignResponse.newBuilder().build()

        coEvery { mockSession.openStream() } returns mockStream
        coEvery { mockStream.write(any()) } just Runs
        coEvery { mockStream.closeWrite() } just Runs
        coEvery { mockStream.close() } just Runs

        val responsePayload = expectedResponse.toByteArray()
        val drpcResponse = DrpcResponse(success = true, response = expectedResponse)
        val encodedResponse = drpcResponse.encode()
        val framedResponse = DrpcEncoding.encodeMessageWithLength(encodedResponse)

        coEvery { mockStream.read(any()) } returnsMany listOf(framedResponse, null)
        every { mockStream.state } returns YamuxStream.State.CLOSED

        val actualResponse = drpcClient.coordinatorCall(
            method = "SpaceSign",
            request = request,
            responseParser = SpaceSignResponse.parser()
        )

        assertNotNull(actualResponse)
        coVerify { mockSession.openStream() }
    }
}

/**
 * Unit tests for DrpcMessage encoding/decoding.
 */
class DrpcMessageTest {

    @Test
    fun `test encode and decode DRPC request`() {
        val request = SpaceSignRequest.newBuilder()
            .setSpaceId("test-space")
            .setHeader(ByteString.copyFrom(byteArrayOf(1, 2, 3)))
            .build()

        val drpcRequest = DrpcRequest(
            serviceId = "coordinator.Coordinator",
            methodId = "SpaceSign",
            request = request
        )

        val encoded = drpcRequest.encode()
        val decoded = DrpcRequest.decode(encoded)

        assertEquals("coordinator.Coordinator", decoded.serviceId)
        assertEquals("SpaceSign", decoded.methodId)
        assertTrue(decoded.requestPayload.isNotEmpty())
    }

    @Test
    fun `test encode and decode DRPC response`() {
        val response = SpaceSignResponse.newBuilder().build()

        val drpcResponse = DrpcResponse(
            success = true,
            response = response
        )

        val encoded = drpcResponse.encode()
        val decoded = DrpcResponse.decode(encoded)

        assertTrue(decoded.success)
        // Empty protobuf has 0 bytes payload
        assertTrue(decoded.payload.isEmpty())
    }

    @Test
    fun `test encode and decode error response`() {
        val drpcResponse = DrpcResponse(
            success = false,
            errorCode = DrpcMessage.ErrorCode.METHOD_NOT_FOUND,
            errorMessage = "Method not found"
        )

        val encoded = drpcResponse.encode()
        val decoded = DrpcResponse.decode(encoded)

        assertTrue(!decoded.success)
        assertEquals(DrpcMessage.ErrorCode.METHOD_NOT_FOUND, decoded.errorCode)
    }

    @Test
    fun `test varint encoding and decoding`() {
        // Test various values
        val testValues = listOf(0, 1, 127, 128, 16383, 16384, Int.MAX_VALUE)

        for (value in testValues) {
            val encoded = ByteArrayOutputStream()
            DrpcEncoding.writeVarInt32(encoded, value)
            val decoded = DrpcEncoding.readVarInt32(encoded.toByteArray(), 0)

            assertEquals(value, decoded.first)
        }
    }

    @Test
    fun `test message with length prefix`() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val framed = DrpcEncoding.encodeMessageWithLength(data)
        val (unframed, bytesRead) = DrpcEncoding.decodeMessageWithLength(framed)

        assertEquals(data.toList(), unframed.toList())
        assertEquals(framed.size, bytesRead)
    }

    @Test
    fun `test empty request encoding`() {
        val request = SpaceSignRequest.newBuilder()
            .setSpaceId("")
            .build()

        val drpcRequest = DrpcRequest(
            serviceId = "coordinator.Coordinator",
            methodId = "SpaceSign",
            request = request
        )

        val encoded = drpcRequest.encode()
        val decoded = DrpcRequest.decode(encoded)

        assertEquals("coordinator.Coordinator", decoded.serviceId)
        assertEquals("SpaceSign", decoded.methodId)
    }

    @Test
    fun `test large message encoding`() {
        // Create a large payload
        val largeData = ByteArray(1024 * 100) // 100KB
        val request = SpaceSignRequest.newBuilder()
            .setSpaceId("test-space")
            .setHeader(ByteString.copyFrom(largeData))
            .build()

        val drpcRequest = DrpcRequest(
            serviceId = "coordinator.Coordinator",
            methodId = "SpaceSign",
            request = request
        )

        val encoded = drpcRequest.encode()
        val decoded = DrpcRequest.decode(encoded)

        assertEquals("coordinator.Coordinator", decoded.serviceId)
        assertEquals("SpaceSign", decoded.methodId)
        // The payload is protobuf encoded, not raw data, so just verify it exists
        assertTrue(decoded.requestPayload.isNotEmpty())
    }

    @Test
    fun `test message size limit`() {
        val largeData = ByteArray(DrpcMessage.MAX_MESSAGE_SIZE + 1)
        val request = SpaceSignRequest.newBuilder()
            .setSpaceId("test-space")
            .setHeader(ByteString.copyFrom(largeData))
            .build()

        val drpcRequest = DrpcRequest(
            serviceId = "coordinator.Coordinator",
            methodId = "SpaceSign",
            request = request
        )

        val exception = assertFailsWith<DrpcEncodingException> {
            drpcRequest.encode()
        }

        assertTrue(exception.message?.contains("exceeds maximum") == true)
    }

    @Test
    fun `test empty response payload`() {
        val drpcResponse = DrpcResponse(
            success = true,
            response = null
        )

        val encoded = drpcResponse.encode()
        val decoded = DrpcResponse.decode(encoded)

        assertTrue(decoded.success)
        assertTrue(decoded.payload.isEmpty())
    }
}

/**
 * Unit tests for DrpcException hierarchy.
 */
class DrpcExceptionTest {

    @Test
    fun `test DrpcTimeoutException is DrpcException`() {
        val exception = DrpcTimeoutException()
        assertTrue(exception is DrpcException)
        assertEquals("DRPC call timed out", exception.message)
    }

    @Test
    fun `test DrpcParseException is DrpcException`() {
        val exception = DrpcParseException("Parse error")
        assertTrue(exception is DrpcException)
        assertEquals("Parse error", exception.message)
    }

    @Test
    fun `test DrpcRpcException has error code`() {
        val exception = DrpcRpcException(404, "Not found")
        assertTrue(exception is DrpcException)
        assertEquals(404, exception.code)
        assertTrue(exception.message?.contains("404") == true)
    }

    @Test
    fun `test DrpcConnectionException is DrpcException`() {
        val exception = DrpcConnectionException("Connection failed")
        assertTrue(exception is DrpcException)
        assertEquals("Connection failed", exception.message)
    }

    @Test
    fun `test DrpcEncodingException is DrpcException`() {
        val exception = DrpcEncodingException("Encoding failed")
        assertTrue(exception is DrpcException)
        assertEquals("Encoding failed", exception.message)
    }

    @Test
    fun `test DrpcInvalidResponseException is DrpcException`() {
        val exception = DrpcInvalidResponseException("Invalid response")
        assertTrue(exception is DrpcException)
        assertEquals("Invalid response", exception.message)
    }

    @Test
    fun `test DrpcStreamClosedException is DrpcException`() {
        val exception = DrpcStreamClosedException()
        assertTrue(exception is DrpcException)
        assertEquals("DRPC stream closed unexpectedly", exception.message)
    }
}
