package com.anyproto.anyfile.service

import com.anyproto.anyfile.domain.sync.FileUploadResult
import com.anyproto.anyfile.domain.sync.SyncOrchestrator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FileUploadCoordinatorTest {

    private val mockOrchestrator: SyncOrchestrator = mockk()
    private val coordinator = FileUploadCoordinator(mockOrchestrator)

    @Test
    fun `upload delegates to SyncOrchestrator with default-space and given path`() = runTest {
        coEvery { mockOrchestrator.uploadFile(any(), any()) } returns
            FileUploadResult.Success("file-id", "abc123", 256, 1)

        coordinator.upload("/sdcard/AnyFileSync/test.txt")

        coVerify(exactly = 1) {
            mockOrchestrator.uploadFile("default-space", "/sdcard/AnyFileSync/test.txt")
        }
    }

    @Test
    fun `upload with different path calls orchestrator with that path`() = runTest {
        coEvery { mockOrchestrator.uploadFile(any(), any()) } returns
            FileUploadResult.Success("file-id-2", "def456", 512, 2)

        coordinator.upload("/data/user/0/com.anyproto.anyfile/files/doc.pdf")

        coVerify(exactly = 1) {
            mockOrchestrator.uploadFile(
                "default-space",
                "/data/user/0/com.anyproto.anyfile/files/doc.pdf"
            )
        }
    }

    @Test
    fun `upload does not throw when orchestrator throws`() = runTest {
        coEvery { mockOrchestrator.uploadFile(any(), any()) } throws
            RuntimeException("network unavailable")

        // Should not propagate — SyncService must keep running
        coordinator.upload("/sdcard/AnyFileSync/file.txt")

        coVerify(exactly = 1) { mockOrchestrator.uploadFile(any(), any()) }
    }
}
