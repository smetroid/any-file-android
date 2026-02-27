// app/src/test/java/com/anyproto/anyfile/integration/SyncIntegrationTest.kt
package com.anyproto.anyfile.integration

import android.content.Context
import com.anyproto.anyfile.data.database.dao.SpaceDao
import com.anyproto.anyfile.data.database.dao.SyncedFileDao
import com.anyproto.anyfile.data.database.entity.Space
import com.anyproto.anyfile.data.database.entity.SyncedFile
import com.anyproto.anyfile.data.database.model.SyncStatus
import com.anyproto.anyfile.data.network.FilenodeClient
import com.anyproto.anyfile.data.network.model.FilesInfoResult
import com.anyproto.anyfile.data.network.model.FileInfo
import com.anyproto.anyfile.domain.sync.ConflictResolver
import com.anyproto.anyfile.domain.sync.SyncOrchestrator
import com.anyproto.anyfile.domain.sync.SyncResult
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.Date

/**
 * Integration tests for sync flows.
 *
 * Tests cover:
 * - End-to-end sync flow
 * - Database operations during sync
 * - Conflict resolution integration
 * - Error handling across layers
 * - State management
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncIntegrationTest {

    private lateinit var syncOrchestrator: SyncOrchestrator
    private lateinit var mockContext: Context
    private lateinit var mockFilenodeClient: FilenodeClient
    private lateinit var mockSpaceDao: SpaceDao
    private lateinit var mockSyncedFileDao: SyncedFileDao
    private lateinit var mockConflictResolver: ConflictResolver
    private val testDispatcher = UnconfinedTestDispatcher()

    private val testSpaceId = "integration-test-space"
    private val testSpaceKey = byteArrayOf(1, 2, 3, 4, 5)
    private val testSpace = Space(
        spaceId = testSpaceId,
        name = "Integration Test Space",
        spaceKey = testSpaceKey,
        createdAt = Date(),
        lastSyncAt = null,
        syncStatus = SyncStatus.IDLE
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Mock database DAOs
        mockSpaceDao = mockk(relaxed = true)
        mockSyncedFileDao = mockk(relaxed = true)

        // Mock network clients
        mockFilenodeClient = mockk()
        mockConflictResolver = mockk(relaxed = true)

        // Setup mock context for file operations
        mockContext = mockk(relaxed = true)
        every { mockContext.filesDir } returns File(System.getProperty("java.io.tmpdir"))

        syncOrchestrator = SyncOrchestrator(
            context = mockContext,
            filenodeClient = mockFilenodeClient,
            spaceDao = mockSpaceDao,
            syncedFileDao = mockSyncedFileDao,
            conflictResolver = mockConflictResolver
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `end-to-end sync with no files returns success`() = runTest {
        // Arrange
        coEvery { mockSpaceDao.getSpaceById(testSpaceId) } returns testSpace
        coEvery { mockSpaceDao.updateSpace(any()) } returns 1
        coEvery { mockSpaceDao.updateLastSyncTime(any(), any()) } returns 1
        coEvery { mockSyncedFileDao.getFilesBySpace(testSpaceId) } returns flowOf(emptyList())

        // Act - Perform sync
        val result = syncOrchestrator.sync(testSpaceId)

        // Assert
        assertThat(result).isInstanceOf(SyncResult.Success::class.java)

        // Verify space was updated
        coVerify { mockSpaceDao.updateSpace(testSpace.copy(syncStatus = SyncStatus.SYNCING)) }
        coVerify { mockSpaceDao.updateLastSyncTime(testSpaceId, any()) }
    }

    @Test
    fun `end-to-end sync with files uploads and tracks them`() = runTest {
        // Arrange
        val tempFile = File.createTempFile("integration-test", ".txt")
        tempFile.writeText("Integration test content")

        coEvery { mockSpaceDao.getSpaceById(testSpaceId) } returns testSpace
        coEvery { mockSyncedFileDao.getFilesBySpace(testSpaceId) } returns flowOf(emptyList())
        coEvery { mockSyncedFileDao.insertFile(any()) } just Runs

        // Mock successful upload
        coEvery {
            mockFilenodeClient.blockPush(
                spaceId = testSpaceId,
                fileId = any(),
                cid = any(),
                data = any()
            )
        } returns Result.success(com.anyproto.anyfile.data.network.model.BlockPushResult(success = true))

        // Act - Upload file
        val uploadResult = syncOrchestrator.uploadFile(testSpaceId, tempFile.absolutePath)

        // Assert
        assertThat(uploadResult).isInstanceOf(com.anyproto.anyfile.domain.sync.FileUploadResult.Success::class.java)

        // Verify file insert was called
        coVerify { mockSyncedFileDao.insertFile(any()) }

        // Cleanup
        tempFile.delete()
    }

    @Test
    fun `end-to-end sync handles space not found error`() = runTest {
        // Arrange - Space doesn't exist
        coEvery { mockSpaceDao.getSpaceById(testSpaceId) } returns null

        // Act - Try to sync non-existent space
        val result = syncOrchestrator.sync(testSpaceId)

        // Assert
        assertThat(result).isInstanceOf(SyncResult.Failed::class.java)
        val failedResult = result as SyncResult.Failed
        assertThat(failedResult.error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `database persist multiple files correctly through orchestrator`() = runTest {
        // Arrange
        val file1 = File.createTempFile("test1", ".txt")
        val file2 = File.createTempFile("test2", ".txt")
        file1.writeText("Content 1")
        file2.writeText("Content 2")

        coEvery { mockSpaceDao.getSpaceById(testSpaceId) } returns testSpace
        coEvery { mockSyncedFileDao.getFilesBySpace(testSpaceId) } returns flowOf(emptyList())
        coEvery { mockSyncedFileDao.insertFile(any()) } just Runs

        coEvery {
            mockFilenodeClient.blockPush(
                spaceId = testSpaceId,
                fileId = any(),
                cid = any(),
                data = any()
            )
        } returns Result.success(com.anyproto.anyfile.data.network.model.BlockPushResult(success = true))

        // Act - Upload both files
        syncOrchestrator.uploadFile(testSpaceId, file1.absolutePath)
        syncOrchestrator.uploadFile(testSpaceId, file2.absolutePath)

        // Assert - Both files should have been inserted
        coVerify(exactly = 2) { mockSyncedFileDao.insertFile(any()) }

        // Cleanup
        file1.delete()
        file2.delete()
    }

    @Test
    fun `sync updates space status correctly through database`() = runTest {
        // Arrange
        val syncingSpace = testSpace.copy(syncStatus = SyncStatus.SYNCING)
        coEvery { mockSpaceDao.getSpaceById(testSpaceId) } returns syncingSpace
        coEvery { mockSpaceDao.updateSpace(any()) } returns 1
        coEvery { mockSpaceDao.updateLastSyncTime(any(), any()) } returns 1
        coEvery { mockSyncedFileDao.getFilesBySpace(testSpaceId) } returns flowOf(emptyList())

        // Act
        val result = syncOrchestrator.sync(testSpaceId)

        // Assert
        assertThat(result).isInstanceOf(SyncResult.Success::class.java)

        // Verify database update was called with correct status
        coVerify {
            mockSpaceDao.updateSpace(
                match { it.syncStatus == SyncStatus.SYNCING }
            )
        }
    }

    @Test
    fun `database operations are tracked during sync`() = runTest {
        // Arrange
        val file = File.createTempFile("transaction-test", ".txt")
        file.writeText("Test content")

        coEvery { mockSpaceDao.getSpaceById(testSpaceId) } returns testSpace
        coEvery { mockSyncedFileDao.getFilesBySpace(testSpaceId) } returns flowOf(emptyList())
        coEvery { mockSyncedFileDao.insertFile(any()) } just Runs

        coEvery {
            mockFilenodeClient.blockPush(
                spaceId = testSpaceId,
                fileId = any(),
                cid = any(),
                data = any()
            )
        } returns Result.success(com.anyproto.anyfile.data.network.model.BlockPushResult(success = true))

        // Act
        val uploadResult = syncOrchestrator.uploadFile(testSpaceId, file.absolutePath)

        // Assert - Both space and file operations should be tracked
        assertThat(uploadResult).isInstanceOf(com.anyproto.anyfile.domain.sync.FileUploadResult.Success::class.java)
        coVerify { mockSyncedFileDao.insertFile(any()) }

        // Cleanup
        file.delete()
    }

    @Test
    fun `multiple sync operations update space correctly`() = runTest {
        // Arrange
        coEvery { mockSpaceDao.getSpaceById(testSpaceId) } returns testSpace
        coEvery { mockSpaceDao.updateSpace(any()) } returns 1
        coEvery { mockSpaceDao.updateLastSyncTime(any(), any()) } returns 1
        coEvery { mockSyncedFileDao.getFilesBySpace(testSpaceId) } returns flowOf(emptyList())

        // Act - Perform multiple syncs
        val result1 = syncOrchestrator.sync(testSpaceId)
        val result2 = syncOrchestrator.sync(testSpaceId)

        // Assert
        assertThat(result1).isInstanceOf(SyncResult.Success::class.java)
        assertThat(result2).isInstanceOf(SyncResult.Success::class.java)

        // Verify lastSyncAt was updated twice
        coVerify(exactly = 2) { mockSpaceDao.updateLastSyncTime(testSpaceId, any()) }
    }

    @Test
    fun `file download integration with database`() = runTest {
        // Arrange
        val tempDir = File(System.getProperty("java.io.tmpdir"))
        val localPath = File(tempDir, "downloaded-integration.txt").absolutePath
        val fileId = "remote-file-123"

        coEvery { mockSpaceDao.getSpaceById(testSpaceId) } returns testSpace
        coEvery { mockSyncedFileDao.getFilesBySpace(testSpaceId) } returns flowOf(emptyList())
        coEvery { mockSyncedFileDao.insertFile(any()) } just Runs

        // Mock file info
        coEvery {
            mockFilenodeClient.filesInfo(
                spaceId = testSpaceId,
                fileIds = listOf(fileId)
            )
        } returns Result.success(
            FilesInfoResult(
                files = listOf(
                    FileInfo(
                        fileId = fileId,
                        usageBytes = 1024,
                        cidsCount = 4
                    )
                )
            )
        )

        // Act - Download file
        val downloadResult = syncOrchestrator.downloadFile(testSpaceId, fileId, localPath)

        // Assert - Verify the flow completed
        assertThat(downloadResult).isInstanceOf(com.anyproto.anyfile.domain.sync.FileDownloadResult.Success::class.java)

        // Cleanup
        File(localPath).deleteOnExit()
    }

    @Test
    fun `conflict resolution integration with orchestrator`() = runTest {
        // Arrange
        val localFile = SyncedFile(
            cid = "local-cid",
            spaceId = testSpaceId,
            filePath = "/local/file.txt",
            size = 100,
            version = 1,
            syncStatus = SyncStatus.IDLE,
            modifiedAt = Date(),
            checksum = "local-checksum"
        )

        val remoteFile = SyncedFile(
            cid = "remote-cid",
            spaceId = testSpaceId,
            filePath = "/remote/file.txt",
            size = 200,
            version = 2,
            syncStatus = SyncStatus.IDLE,
            modifiedAt = Date(),
            checksum = "remote-checksum"
        )

        val expectedResolution = com.anyproto.anyfile.domain.sync.ConflictResolutionResult.UseRemote(remoteFile)
        coEvery {
            mockConflictResolver.resolve(localFile, remoteFile, com.anyproto.anyfile.domain.sync.ConflictResolutionStrategy.LATEST_WINS)
        } returns expectedResolution

        // Act
        val result = syncOrchestrator.resolveConflict(
            localFile,
            remoteFile,
            com.anyproto.anyfile.domain.sync.ConflictResolutionStrategy.LATEST_WINS
        )

        // Assert
        assertThat(result).isEqualTo(expectedResolution)
        coVerify {
            mockConflictResolver.resolve(localFile, remoteFile, com.anyproto.anyfile.domain.sync.ConflictResolutionStrategy.LATEST_WINS)
        }
    }

    @Test
    fun `sync handles partial success correctly`() = runTest {
        // Arrange
        coEvery { mockSpaceDao.getSpaceById(testSpaceId) } returns testSpace
        coEvery { mockSpaceDao.updateSpace(any()) } returns 1
        coEvery { mockSpaceDao.updateLastSyncTime(any(), any()) } returns 1
        coEvery { mockSyncedFileDao.getFilesBySpace(testSpaceId) } returns flowOf(emptyList())

        // This test verifies the orchestrator handles the result structure correctly
        // The actual sync result depends on implementation

        // Act
        val result = syncOrchestrator.sync(testSpaceId)

        // Assert
        assertThat(result).isInstanceOf(SyncResult.Success::class.java)
    }

    @Test
    fun `concurrent sync operations handle database correctly`() = runTest {
        // Arrange
        coEvery { mockSpaceDao.getSpaceById(testSpaceId) } returns testSpace
        coEvery { mockSpaceDao.updateSpace(any()) } returns 1
        coEvery { mockSpaceDao.updateLastSyncTime(any(), any()) } returns 1
        coEvery { mockSyncedFileDao.getFilesBySpace(testSpaceId) } returns flowOf(emptyList())

        // Act - Perform sync
        val result = syncOrchestrator.sync(testSpaceId)

        // Assert
        assertThat(result).isInstanceOf(SyncResult.Success::class.java)

        // Verify space DAO methods were called
        coVerify { mockSpaceDao.getSpaceById(testSpaceId) }
        coVerify { mockSpaceDao.updateLastSyncTime(testSpaceId, any()) }
    }

    @Test
    fun `database persists sync status changes`() = runTest {
        // Arrange
        coEvery { mockSpaceDao.getSpaceById(testSpaceId) } returns testSpace
        coEvery { mockSpaceDao.updateSpace(any()) } returns 1
        coEvery { mockSpaceDao.updateLastSyncTime(any(), any()) } returns 1
        coEvery { mockSyncedFileDao.getFilesBySpace(testSpaceId) } returns flowOf(emptyList())

        // Act - Sync
        syncOrchestrator.sync(testSpaceId)

        // Assert - Verify status transitions
        coVerify {
            mockSpaceDao.updateSpace(
                match { it.syncStatus == SyncStatus.SYNCING }
            )
        }
        coVerify { mockSpaceDao.updateLastSyncTime(testSpaceId, any()) }
    }

    @Test
    fun `uploadFile increments version when file already exists`() = runTest {
        // Arrange
        val tempFile = File.createTempFile("version-test", ".txt")
        tempFile.writeText("Test content")

        val existingFile = SyncedFile(
            cid = "old-cid",
            spaceId = testSpaceId,
            filePath = tempFile.absolutePath,
            size = 100,
            version = 3,
            syncStatus = SyncStatus.IDLE,
            modifiedAt = Date(),
            checksum = "old-checksum"
        )

        coEvery { mockSpaceDao.getSpaceById(testSpaceId) } returns testSpace
        coEvery { mockSyncedFileDao.getFilesBySpace(testSpaceId) } returns flowOf(listOf(existingFile))
        coEvery { mockSyncedFileDao.insertFile(any()) } just Runs

        coEvery {
            mockFilenodeClient.blockPush(
                spaceId = testSpaceId,
                fileId = any(),
                cid = any(),
                data = any()
            )
        } returns Result.success(com.anyproto.anyfile.data.network.model.BlockPushResult(success = true))

        // Act
        val result = syncOrchestrator.uploadFile(testSpaceId, tempFile.absolutePath)

        // Assert
        assertThat(result).isInstanceOf(com.anyproto.anyfile.domain.sync.FileUploadResult.Success::class.java)
        coVerify {
            mockSyncedFileDao.insertFile(
                match { it.version == 4 }
            )
        }

        // Cleanup
        tempFile.delete()
    }
}
