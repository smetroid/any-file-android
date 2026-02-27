// app/src/test/java/com/anyproto/anyfile/domain/sync/SyncOrchestratorTest.kt
package com.anyproto.anyfile.domain.sync

import android.content.Context
import com.anyproto.anyfile.data.crypto.Blake3Hash
import com.anyproto.anyfile.data.database.dao.SpaceDao
import com.anyproto.anyfile.data.database.dao.SyncedFileDao
import com.anyproto.anyfile.data.database.entity.Space
import com.anyproto.anyfile.data.database.entity.SyncedFile
import com.anyproto.anyfile.data.database.model.SyncStatus
import com.anyproto.anyfile.data.network.FilenodeClient
import com.anyproto.anyfile.data.network.model.BlockPushResult
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Date
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk

/**
 * Unit tests for SyncOrchestrator.
 *
 * Tests cover:
 * - Sync flow
 * - File upload
 * - File download
 * - Conflict resolution
 * - Error handling
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncOrchestratorTest {

    private lateinit var syncOrchestrator: SyncOrchestrator
    private lateinit var mockContext: Context
    private lateinit var mockFilenodeClient: FilenodeClient
    private lateinit var mockSpaceDao: SpaceDao
    private lateinit var mockSyncedFileDao: SyncedFileDao
    private lateinit var mockConflictResolver: ConflictResolver
    private val testDispatcher = UnconfinedTestDispatcher()

    private val testSpaceId = "test-space-123"
    private val testFilePath = "/test/path/file.txt"
    private val testFileId = "test-file-456"

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockContext = mockk()
        mockFilenodeClient = mockk()
        mockSpaceDao = mockk()
        mockSyncedFileDao = mockk()
        mockConflictResolver = mockk()

        // Mock Context for file operations
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
    fun `sync returns success when no files to sync`() = runTest {
        // Arrange
        val testSpace = Space(
            spaceId = testSpaceId,
            name = "Test Space",
            spaceKey = byteArrayOf(1, 2, 3),
            createdAt = Date(),
            lastSyncAt = null,
            syncStatus = SyncStatus.IDLE
        )
        coEvery { mockSpaceDao.getSpaceById(testSpaceId) } returns testSpace
        coEvery { mockSpaceDao.updateSpace(any()) } returns 1
        coEvery { mockSpaceDao.updateLastSyncTime(any(), any()) } returns 1
        coEvery { mockSyncedFileDao.getFilesBySpace(testSpaceId) } returns flowOf(emptyList())

        // Act
        val result = syncOrchestrator.sync(testSpaceId)

        // Assert
        assertThat(result).isInstanceOf(SyncResult.Success::class.java)
        coVerify { mockSpaceDao.updateSpace(testSpace.copy(syncStatus = SyncStatus.SYNCING)) }
        coVerify { mockSpaceDao.updateLastSyncTime(testSpaceId, any()) }
    }

    @Test
    fun `sync returns failed when space does not exist`() = runTest {
        // Arrange
        coEvery { mockSpaceDao.getSpaceById(testSpaceId) } returns null

        // Act
        val result = syncOrchestrator.sync(testSpaceId)

        // Assert
        assertThat(result).isInstanceOf(SyncResult.Failed::class.java)
        val failedResult = result as SyncResult.Failed
        assertThat(failedResult.error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `uploadFile returns success when file exists and upload succeeds`() = runTest {
        // Arrange
        val tempFile = File.createTempFile("test", ".txt")
        tempFile.writeText("Test content")

        val checksum = Blake3Hash.hashFile(tempFile)
        val checksumString = Blake3Hash.hashToString(checksum)

        coEvery {
            mockFilenodeClient.blockPush(
                spaceId = testSpaceId,
                fileId = any(),
                cid = any(),
                data = any()
            )
        } returns Result.success(BlockPushResult(success = true))

        coEvery { mockSyncedFileDao.getFilesBySpace(testSpaceId) } returns flowOf(emptyList())
        coEvery { mockSyncedFileDao.insertFile(any()) } just Runs

        // Act
        val result = syncOrchestrator.uploadFile(testSpaceId, tempFile.absolutePath)

        // Assert
        assertThat(result).isInstanceOf(FileUploadResult.Success::class.java)
        val successResult = result as FileUploadResult.Success
        assertThat(successResult.checksum).isEqualTo(checksumString)
        assertThat(successResult.blockCount).isGreaterThan(0)

        // Cleanup
        tempFile.delete()
    }

    @Test
    fun `uploadFile returns failed when file does not exist`() = runTest {
        // Arrange
        val nonExistentPath = "/non/existent/path/file.txt"

        // Act
        val result = syncOrchestrator.uploadFile(testSpaceId, nonExistentPath)

        // Assert
        assertThat(result).isInstanceOf(FileUploadResult.Failed::class.java)
        val failedResult = result as FileUploadResult.Failed
        assertThat(failedResult.error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `uploadFile updates database with new file metadata`() = runTest {
        // Arrange
        val tempFile = File.createTempFile("test", ".txt")
        tempFile.writeText("Test content")

        coEvery {
            mockFilenodeClient.blockPush(
                spaceId = testSpaceId,
                fileId = any(),
                cid = any(),
                data = any()
            )
        } returns Result.success(BlockPushResult(success = true))

        coEvery { mockSyncedFileDao.getFilesBySpace(testSpaceId) } returns flowOf(emptyList())
        coEvery { mockSyncedFileDao.insertFile(any()) } just Runs

        // Act
        syncOrchestrator.uploadFile(testSpaceId, tempFile.absolutePath)

        // Assert
        coVerify { mockSyncedFileDao.insertFile(any()) }

        // Cleanup
        tempFile.delete()
    }

    @Test
    fun `uploadFile increments version when file already exists`() = runTest {
        // Arrange
        val tempFile = File.createTempFile("test", ".txt")
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

        coEvery {
            mockFilenodeClient.blockPush(
                spaceId = testSpaceId,
                fileId = any(),
                cid = any(),
                data = any()
            )
        } returns Result.success(BlockPushResult(success = true))

        coEvery { mockSyncedFileDao.getFilesBySpace(testSpaceId) } returns flowOf(listOf(existingFile))
        coEvery { mockSyncedFileDao.insertFile(any()) } just Runs

        // Act
        val result = syncOrchestrator.uploadFile(testSpaceId, tempFile.absolutePath)

        // Assert
        coVerify {
            mockSyncedFileDao.insertFile(
                match { it.version == 4 }
            )
        }

        // Cleanup
        tempFile.delete()
    }

    @Test
    fun `downloadFile returns success when download succeeds`() = runTest {
        // Arrange
        val tempDir = File(System.getProperty("java.io.tmpdir"))
        val localPath = File(tempDir, "downloaded.txt").absolutePath

        coEvery {
            mockFilenodeClient.filesInfo(spaceId = testSpaceId, fileIds = listOf(testFileId))
        } returns Result.success(
            com.anyproto.anyfile.data.network.model.FilesInfoResult(
                files = listOf(
                    com.anyproto.anyfile.data.network.model.FileInfo(
                        fileId = testFileId,
                        usageBytes = 1024,
                        cidsCount = 4
                    )
                )
            )
        )

        coEvery { mockSyncedFileDao.getFilesBySpace(testSpaceId) } returns flowOf(emptyList())
        coEvery { mockSyncedFileDao.insertFile(any()) } just Runs

        // Act
        val result = syncOrchestrator.downloadFile(testSpaceId, testFileId, localPath)

        // Assert
        assertThat(result).isInstanceOf(FileDownloadResult.Success::class.java)

        // Cleanup
        File(localPath).delete()
    }

    @Test
    fun `downloadFile returns failed when file not found remotely`() = runTest {
        // Arrange
        val localPath = "/local/path/file.txt"

        coEvery {
            mockFilenodeClient.filesInfo(spaceId = testSpaceId, fileIds = listOf(testFileId))
        } returns Result.success(
            com.anyproto.anyfile.data.network.model.FilesInfoResult(files = emptyList())
        )

        // Act
        val result = syncOrchestrator.downloadFile(testSpaceId, testFileId, localPath)

        // Assert
        assertThat(result).isInstanceOf(FileDownloadResult.Failed::class.java)
        val failedResult = result as FileDownloadResult.Failed
        assertThat(failedResult.error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `resolveConflict delegates to ConflictResolver`() = runTest {
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

        val expectedResolution = ConflictResolutionResult.UseRemote(remoteFile)
        coEvery {
            mockConflictResolver.resolve(localFile, remoteFile, ConflictResolutionStrategy.LATEST_WINS)
        } returns expectedResolution

        // Act
        val result = syncOrchestrator.resolveConflict(
            localFile,
            remoteFile,
            ConflictResolutionStrategy.LATEST_WINS
        )

        // Assert
        assertThat(result).isEqualTo(expectedResolution)
        coVerify {
            mockConflictResolver.resolve(localFile, remoteFile, ConflictResolutionStrategy.LATEST_WINS)
        }
    }

    @Test
    fun `hasConflict delegates to ConflictResolver`() = runTest {
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

        coEvery { mockConflictResolver.hasConflict(localFile, remoteFile) } returns true

        // Act
        val result = syncOrchestrator.hasConflict(localFile, remoteFile)

        // Assert
        assertThat(result).isTrue()
        coVerify { mockConflictResolver.hasConflict(localFile, remoteFile) }
    }
}
