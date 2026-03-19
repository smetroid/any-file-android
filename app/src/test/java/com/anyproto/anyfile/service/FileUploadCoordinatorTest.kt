package com.anyproto.anyfile.service

import com.anyproto.anyfile.data.config.NetworkConfigRepository
import com.anyproto.anyfile.data.network.Base58Btc
import com.anyproto.anyfile.data.network.CidUtils
import com.anyproto.anyfile.data.network.model.BlockPushResult
import com.anyproto.anyfile.data.network.p2p.P2PFilenodeClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File

class FileUploadCoordinatorTest {

    private val mockFilenodeClient: P2PFilenodeClient = mockk(relaxed = true)
    private val mockConfig: NetworkConfigRepository = mockk(relaxed = true)
    private val coordinator = FileUploadCoordinator(mockFilenodeClient, mockConfig)

    @Test
    fun `upload skips when no space ID configured`() = runTest {
        every { mockConfig.spaceId } returns null

        coordinator.upload("/any/path/file.txt")

        coVerify(exactly = 0) { mockFilenodeClient.blockPush(any(), any(), any(), any()) }
    }

    @Test
    fun `upload calls blockPush with base58 CID as fileId`() = runTest {
        val tempFile = File.createTempFile("anyfile_test", ".txt")
        val content = "hello world"
        tempFile.writeText(content)

        val expectedCid = CidUtils.computeBlake3Cid(content.toByteArray())
        val expectedFileId = "${tempFile.name}|${Base58Btc.encode(expectedCid)}"

        every { mockConfig.spaceId } returns "my-space-id"
        coEvery { mockFilenodeClient.blockPush(any(), any(), any(), any()) } returns
            Result.success(BlockPushResult(true))

        coordinator.upload(tempFile.absolutePath)

        coVerify(exactly = 1) {
            mockFilenodeClient.blockPush(
                spaceId = "my-space-id",
                fileId = expectedFileId,
                cid = expectedCid,
                data = any(),
            )
        }
        tempFile.delete()
    }

    @Test
    fun `upload does not throw when file does not exist`() = runTest {
        every { mockConfig.spaceId } returns "my-space-id"

        coordinator.upload("/nonexistent/path/file.txt")
        // exception caught internally — should not propagate
    }

    @Test
    fun `upload does not throw when blockPush fails`() = runTest {
        val tempFile = File.createTempFile("anyfile_test2", ".txt")
        tempFile.writeText("data")

        every { mockConfig.spaceId } returns "my-space-id"
        coEvery { mockFilenodeClient.blockPush(any(), any(), any(), any()) } returns
            Result.failure(RuntimeException("network error"))

        coordinator.upload(tempFile.absolutePath)
        // should not propagate
        tempFile.delete()
    }
}
