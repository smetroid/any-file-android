package com.anyproto.anyfile.data.network

import com.anyproto.anyfile.data.network.model.AccountInfo
import com.anyproto.anyfile.data.network.model.BlockGetResult
import com.anyproto.anyfile.data.network.model.BlockPushResult
import com.anyproto.anyfile.data.network.model.FileInfo
import com.anyproto.anyfile.data.network.model.FilesGetResult
import com.anyproto.anyfile.data.network.model.FilesInfoResult
import com.anyproto.anyfile.data.network.model.SpaceUsageInfo
import com.anyproto.anyfile.protos.AccountInfoResponse
import com.anyproto.anyfile.protos.BlockGetResponse
import com.anyproto.anyfile.protos.FileInfo as ProtoFileInfo
import com.anyproto.anyfile.protos.FilesGetResponse
import com.anyproto.anyfile.protos.FilesInfoResponse
import com.anyproto.anyfile.protos.Ok
import com.anyproto.anyfile.protos.SpaceInfoResponse
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Unit tests for FilenodeClient.
 *
 * Uses MockWebServer to simulate HTTP responses from the filenode service.
 */
class FilenodeClientTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var filenodeClient: FilenodeClient
    private lateinit var okHttpClient: OkHttpClient

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        okHttpClient = OkHttpClient()
        filenodeClient = FilenodeClient(okHttpClient)
        filenodeClient.initialize(mockWebServer.url("/").toString())
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `blockPush should return success result`() = runTest {
        // Arrange
        val spaceId = "test-space-id"
        val fileId = "test-file-id"
        val cid = byteArrayOf(1, 2, 3, 4, 5)
        val data = byteArrayOf(10, 20, 30, 40, 50)

        val okResponse = Ok.newBuilder().build()
        val responseBytes = okResponse.toByteArray()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(responseBytes))
                .addHeader("Content-Type", "application/x-protobuf")
        )

        // Act
        val result = filenodeClient.blockPush(spaceId, fileId, cid, data)

        // Assert
        assertTrue(result.isSuccess)
        val pushResult = result.getOrThrow()
        assertEquals(true, pushResult.success)
    }

    @Test
    fun `blockGet should return block data`() = runTest {
        // Arrange
        val spaceId = "test-space-id"
        val cid = byteArrayOf(1, 2, 3, 4, 5)
        val data = byteArrayOf(10, 20, 30, 40, 50)

        val blockGetResponse = BlockGetResponse.newBuilder()
            .setCid(com.google.protobuf.ByteString.copyFrom(cid))
            .setData(com.google.protobuf.ByteString.copyFrom(data))
            .build()

        val responseBytes = blockGetResponse.toByteArray()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(responseBytes))
                .addHeader("Content-Type", "application/x-protobuf")
        )

        // Act
        val result = filenodeClient.blockGet(spaceId, cid)

        // Assert
        assertTrue(result.isSuccess)
        val blockResult = result.getOrThrow()
        assertTrue(blockResult.cid.contentEquals(cid))
        assertTrue(blockResult.data.contentEquals(data))
    }

    @Test
    fun `filesInfo should return list of file info`() = runTest {
        // Arrange
        val spaceId = "test-space-id"
        val fileIds = listOf("file1", "file2", "file3")

        val fileInfo1 = ProtoFileInfo.newBuilder()
            .setFileId("file1")
            .setUsageBytes(1024)
            .setCidsCount(5)
            .build()

        val fileInfo2 = ProtoFileInfo.newBuilder()
            .setFileId("file2")
            .setUsageBytes(2048)
            .setCidsCount(10)
            .build()

        val fileInfo3 = ProtoFileInfo.newBuilder()
            .setFileId("file3")
            .setUsageBytes(512)
            .setCidsCount(3)
            .build()

        val filesInfoResponse = FilesInfoResponse.newBuilder()
            .addFilesInfo(fileInfo1)
            .addFilesInfo(fileInfo2)
            .addFilesInfo(fileInfo3)
            .build()

        val responseBytes = filesInfoResponse.toByteArray()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(responseBytes))
                .addHeader("Content-Type", "application/x-protobuf")
        )

        // Act
        val result = filenodeClient.filesInfo(spaceId, fileIds)

        // Assert
        assertTrue(result.isSuccess)
        val filesInfoResult = result.getOrThrow()
        assertEquals(3, filesInfoResult.files.size)
        assertEquals("file1", filesInfoResult.files[0].fileId)
        assertEquals(1024L, filesInfoResult.files[0].usageBytes)
        assertEquals(5, filesInfoResult.files[0].cidsCount)
        assertEquals("file2", filesInfoResult.files[1].fileId)
        assertEquals(2048L, filesInfoResult.files[1].usageBytes)
        assertEquals(10, filesInfoResult.files[1].cidsCount)
    }

    @Test
    fun `filesGet should return list of file get results`() = runTest {
        // Arrange
        val spaceId = "test-space-id"
        val fileIds = listOf("file1", "file2")

        val filesGetResponse1 = FilesGetResponse.newBuilder()
            .setFileId("file1")
            .build()

        val filesGetResponse2 = FilesGetResponse.newBuilder()
            .setFileId("file2")
            .build()

        val responseBytes1 = filesGetResponse1.toByteArray()
        val responseBytes2 = filesGetResponse2.toByteArray()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(responseBytes1))
                .addHeader("Content-Type", "application/x-protobuf")
        )

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(responseBytes2))
                .addHeader("Content-Type", "application/x-protobuf")
        )

        // Act
        val result = filenodeClient.filesGet(spaceId, fileIds)

        // Assert
        assertTrue(result.isSuccess)
        val filesGetResults = result.getOrThrow()
        assertEquals(2, filesGetResults.size)
        assertEquals("file1", filesGetResults[0].fileId)
        assertEquals("file2", filesGetResults[1].fileId)
    }

    @Test
    fun `spaceInfo should return space usage info`() = runTest {
        // Arrange
        val spaceId = "test-space-id"

        val spaceInfoResponse = SpaceInfoResponse.newBuilder()
            .setSpaceId(spaceId)
            .setLimitBytes(10737418240L) // 10GB
            .setTotalUsageBytes(536870912L) // 512MB
            .setCidsCount(1000L)
            .setFilesCount(50L)
            .setSpaceUsageBytes(268435456L) // 256MB
            .build()

        val responseBytes = spaceInfoResponse.toByteArray()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(responseBytes))
                .addHeader("Content-Type", "application/x-protobuf")
        )

        // Act
        val result = filenodeClient.spaceInfo(spaceId)

        // Assert
        assertTrue(result.isSuccess)
        val spaceUsageInfo = result.getOrThrow()
        assertEquals(spaceId, spaceUsageInfo.spaceId)
        assertEquals(10737418240L, spaceUsageInfo.limitBytes)
        assertEquals(536870912L, spaceUsageInfo.totalUsageBytes)
        assertEquals(1000L, spaceUsageInfo.cidsCount)
        assertEquals(50L, spaceUsageInfo.filesCount)
        assertEquals(268435456L, spaceUsageInfo.spaceUsageBytes)
    }

    @Test
    fun `accountInfo should return account info with spaces`() = runTest {
        // Arrange
        val spaceInfo1 = SpaceInfoResponse.newBuilder()
            .setSpaceId("space1")
            .setLimitBytes(5368709120L) // 5GB
            .setTotalUsageBytes(268435456L) // 256MB
            .setCidsCount(500L)
            .setFilesCount(25L)
            .setSpaceUsageBytes(134217728L) // 128MB
            .build()

        val spaceInfo2 = SpaceInfoResponse.newBuilder()
            .setSpaceId("space2")
            .setLimitBytes(5368709120L) // 5GB
            .setTotalUsageBytes(536870912L) // 512MB
            .setCidsCount(750L)
            .setFilesCount(40L)
            .setSpaceUsageBytes(268435456L) // 256MB
            .build()

        val accountInfoResponse = AccountInfoResponse.newBuilder()
            .setLimitBytes(10737418240L) // 10GB shared limit
            .setTotalUsageBytes(805306368L) // 768MB total
            .setTotalCidsCount(1250L)
            .addSpaces(spaceInfo1)
            .addSpaces(spaceInfo2)
            .setAccountLimitBytes(16106127360L) // 15GB total limit
            .build()

        val responseBytes = accountInfoResponse.toByteArray()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(responseBytes))
                .addHeader("Content-Type", "application/x-protobuf")
        )

        // Act
        val result = filenodeClient.accountInfo()

        // Assert
        assertTrue(result.isSuccess)
        val accountInfo = result.getOrThrow()
        assertEquals(10737418240L, accountInfo.limitBytes)
        assertEquals(805306368L, accountInfo.totalUsageBytes)
        assertEquals(1250L, accountInfo.totalCidsCount)
        assertEquals(16106127360L, accountInfo.accountLimitBytes)
        assertEquals(2, accountInfo.spaces.size)
        assertEquals("space1", accountInfo.spaces[0].spaceId)
        assertEquals("space2", accountInfo.spaces[1].spaceId)
    }

    @Test
    fun `blockPush should return failure on HTTP error`() = runTest {
        // Arrange
        val spaceId = "test-space-id"
        val fileId = "test-file-id"
        val cid = byteArrayOf(1, 2, 3)
        val data = byteArrayOf(4, 5, 6)

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error")
        )

        // Act
        val result = filenodeClient.blockPush(spaceId, fileId, cid, data)

        // Assert
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is FilenodeException)
    }

    @Test
    fun `blockGet should return failure on HTTP error`() = runTest {
        // Arrange
        val spaceId = "test-space-id"
        val cid = byteArrayOf(1, 2, 3)

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("Not Found")
        )

        // Act
        val result = filenodeClient.blockGet(spaceId, cid)

        // Assert
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is FilenodeException)
    }

    @Test
    fun `filesInfo should return failure on HTTP error`() = runTest {
        // Arrange
        val spaceId = "test-space-id"
        val fileIds = listOf("file1", "file2")

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("Forbidden")
        )

        // Act
        val result = filenodeClient.filesInfo(spaceId, fileIds)

        // Assert
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is FilenodeException)
    }

    @Test
    fun `spaceInfo should return failure on HTTP error`() = runTest {
        // Arrange
        val spaceId = "test-space-id"

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error")
        )

        // Act
        val result = filenodeClient.spaceInfo(spaceId)

        // Assert
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is FilenodeException)
    }

    @Test
    fun `accountInfo should return failure on HTTP error`() = runTest {
        // Arrange
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setBody("Service Unavailable")
        )

        // Act
        val result = filenodeClient.accountInfo()

        // Assert
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is FilenodeException)
    }

    @Test
    fun `blockPush should throw exception when not initialized`() = runTest {
        // Arrange
        val uninitializedClient = FilenodeClient(okHttpClient)
        val spaceId = "test-space-id"
        val fileId = "test-file-id"
        val cid = byteArrayOf(1, 2, 3)
        val data = byteArrayOf(4, 5, 6)

        // Act
        val result = uninitializedClient.blockPush(spaceId, fileId, cid, data)

        // Assert
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("not initialized") == true)
    }

    @Test
    fun `blockGet should throw exception when not initialized`() = runTest {
        // Arrange
        val uninitializedClient = FilenodeClient(okHttpClient)
        val spaceId = "test-space-id"
        val cid = byteArrayOf(1, 2, 3)

        // Act
        val result = uninitializedClient.blockGet(spaceId, cid)

        // Assert
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("not initialized") == true)
    }

    @Test
    fun `spaceInfo should throw exception when not initialized`() = runTest {
        // Arrange
        val uninitializedClient = FilenodeClient(okHttpClient)
        val spaceId = "test-space-id"

        // Act
        val result = uninitializedClient.spaceInfo(spaceId)

        // Assert
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("not initialized") == true)
    }

    @Test
    fun `accountInfo should throw exception when not initialized`() = runTest {
        // Arrange
        val uninitializedClient = FilenodeClient(okHttpClient)

        // Act
        val result = uninitializedClient.accountInfo()

        // Assert
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("not initialized") == true)
    }
}
