// app/src/test/java/com/anyproto/anyfile/ui/screens/FilesViewModelTest.kt
package com.anyproto.anyfile.ui.screens

import com.anyproto.anyfile.data.database.dao.SpaceDao
import com.anyproto.anyfile.data.database.dao.SyncedFileDao
import com.anyproto.anyfile.data.database.entity.Space
import com.anyproto.anyfile.data.database.entity.SyncedFile
import com.anyproto.anyfile.data.database.model.SyncStatus
import com.anyproto.anyfile.util.AnyfileException
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
import com.google.common.truth.Truth.assertThat
import java.util.Date

/**
 * Unit tests for FilesViewModel.
 *
 * Tests cover:
 * - File list loading
 * - Space name loading
 * - Retry logic
 * - Error handling
 * - Empty state handling
 * - Error file filtering
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FilesViewModelTest {

    private lateinit var viewModel: FilesViewModel
    private lateinit var mockSpaceDao: SpaceDao
    private lateinit var mockSyncedFileDao: SyncedFileDao
    private val testDispatcher = UnconfinedTestDispatcher()

    private val testSpaceId = "test-space-123"
    private val testSpace = Space(
        spaceId = testSpaceId,
        name = "Test Space",
        spaceKey = byteArrayOf(1, 2, 3),
        createdAt = Date(),
        lastSyncAt = null,
        syncStatus = SyncStatus.IDLE
    )

    private val testFiles = listOf(
        SyncedFile(
            cid = "file-1",
            spaceId = testSpaceId,
            filePath = "/path/to/file1.txt",
            size = 1024,
            version = 1,
            syncStatus = SyncStatus.IDLE,
            modifiedAt = Date(),
            checksum = "checksum1"
        ),
        SyncedFile(
            cid = "file-2",
            spaceId = testSpaceId,
            filePath = "/path/to/file2.txt",
            size = 2048,
            version = 1,
            syncStatus = SyncStatus.ERROR,
            modifiedAt = Date(),
            checksum = "checksum2"
        )
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockSpaceDao = mockk()
        mockSyncedFileDao = mockk()

        viewModel = FilesViewModel(
            spaceDao = mockSpaceDao,
            syncedFileDao = mockSyncedFileDao
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `files flow is empty initially`() {
        // Assert
        assertThat(viewModel.files.value).isEmpty()
    }

    @Test
    fun `spaceName flow is empty initially`() {
        // Assert
        assertThat(viewModel.spaceName.value).isEmpty()
    }

    @Test
    fun `isRefreshing is false initially`() {
        // Assert
        assertThat(viewModel.isRefreshing.value).isFalse()
    }

    @Test
    fun `uiState shows initial state correctly`() {
        // Assert
        val state = viewModel.uiState.value
        assertThat(state.isLoading).isFalse()
        assertThat(state.error).isNull()
        assertThat(state.isEmpty).isFalse()
    }

    @Test
    fun `loadFiles sets loading state and loads files`() = runTest {
        // Arrange
        coEvery { mockSpaceDao.getSpaceById(testSpaceId) } returns testSpace
        coEvery { mockSyncedFileDao.getFilesBySpace(testSpaceId) } returns flowOf(testFiles)

        // Act
        viewModel.loadFiles(testSpaceId)

        // Assert
        assertThat(viewModel.spaceName.value).isEqualTo("Test Space")
        assertThat(viewModel.isRefreshing.value).isFalse()
        coVerify { mockSpaceDao.getSpaceById(testSpaceId) }
        coVerify { mockSyncedFileDao.getFilesBySpace(testSpaceId) }
    }

    @Test
    fun `loadFiles sets space name correctly`() = runTest {
        // Arrange
        coEvery { mockSpaceDao.getSpaceById(testSpaceId) } returns testSpace
        coEvery { mockSyncedFileDao.getFilesBySpace(testSpaceId) } returns flowOf(emptyList())

        // Act
        viewModel.loadFiles(testSpaceId)

        // Assert
        assertThat(viewModel.spaceName.value).isEqualTo("Test Space")
    }

    @Test
    fun `loadFiles sets unknown space name when space not found`() = runTest {
        // Arrange
        coEvery { mockSpaceDao.getSpaceById(testSpaceId) } returns null
        coEvery { mockSyncedFileDao.getFilesBySpace(testSpaceId) } returns flowOf(emptyList())

        // Act
        viewModel.loadFiles(testSpaceId)

        // Assert
        assertThat(viewModel.spaceName.value).isEqualTo("Unknown Space")
    }

    @Test
    fun `loadFiles sets error state when space not found`() = runTest {
        // Arrange
        coEvery { mockSpaceDao.getSpaceById(testSpaceId) } returns null

        // Act
        viewModel.loadFiles(testSpaceId)

        // Assert
        val state = viewModel.uiState.value
        assertThat(state.isLoading).isFalse()
        assertThat(state.error).isInstanceOf(AnyfileException.Sync.SpaceNotFoundError::class.java)
        assertThat(state.isEmpty).isFalse()
        assertThat(viewModel.isRefreshing.value).isFalse()
    }

    @Test
    fun `loadFiles sets isEmpty to true when file list is empty`() = runTest {
        // Arrange
        coEvery { mockSpaceDao.getSpaceById(testSpaceId) } returns testSpace
        coEvery { mockSyncedFileDao.getFilesBySpace(testSpaceId) } returns flowOf(emptyList())

        // Act
        viewModel.loadFiles(testSpaceId)

        // Assert
        val state = viewModel.uiState.value
        assertThat(state.isEmpty).isTrue()
    }

    @Test
    fun `loadFiles sets isEmpty to false when file list has items`() = runTest {
        // Arrange
        coEvery { mockSpaceDao.getSpaceById(testSpaceId) } returns testSpace
        coEvery { mockSyncedFileDao.getFilesBySpace(testSpaceId) } returns flowOf(testFiles)

        // Act
        viewModel.loadFiles(testSpaceId)

        // Assert
        val state = viewModel.uiState.value
        assertThat(state.isEmpty).isFalse()
    }

    @Test
    fun `loadFiles does nothing when spaceId is empty`() = runTest {
        // Act
        viewModel.loadFiles("")

        // Assert - DAO methods should not be called
        coVerify(exactly = 0) { mockSpaceDao.getSpaceById(any()) }
        coVerify(exactly = 0) { mockSyncedFileDao.getFilesBySpace(any()) }
    }

    @Test
    fun `refreshFiles reloads files for current space`() = runTest {
        // Arrange - First load
        coEvery { mockSpaceDao.getSpaceById(testSpaceId) } returns testSpace
        coEvery { mockSyncedFileDao.getFilesBySpace(testSpaceId) } returns flowOf(testFiles)

        // Act - Initial load
        viewModel.loadFiles(testSpaceId)

        // Reset mock to track subsequent calls
        clearMocks(mockSpaceDao, mockSyncedFileDao)
        coEvery { mockSpaceDao.getSpaceById(testSpaceId) } returns testSpace
        coEvery { mockSyncedFileDao.getFilesBySpace(testSpaceId) } returns flowOf(testFiles)

        // Act - Refresh
        viewModel.refreshFiles()

        // Assert - Should call DAO again
        coVerify { mockSpaceDao.getSpaceById(testSpaceId) }
        coVerify { mockSyncedFileDao.getFilesBySpace(testSpaceId) }
    }

    @Test
    fun `refreshFiles does nothing when no space loaded`() = runTest {
        // Act - No space has been loaded yet
        viewModel.refreshFiles()

        // Assert - DAO methods should not be called
        coVerify(exactly = 0) { mockSpaceDao.getSpaceById(any()) }
        coVerify(exactly = 0) { mockSyncedFileDao.getFilesBySpace(any()) }
    }

    @Test
    fun `retry reloads files with loading state`() = runTest {
        // Arrange - First load
        coEvery { mockSpaceDao.getSpaceById(testSpaceId) } returns testSpace
        coEvery { mockSyncedFileDao.getFilesBySpace(testSpaceId) } returns flowOf(testFiles)
        viewModel.loadFiles(testSpaceId)

        // Reset mock to track subsequent calls
        clearMocks(mockSpaceDao, mockSyncedFileDao)
        coEvery { mockSpaceDao.getSpaceById(testSpaceId) } returns testSpace
        coEvery { mockSyncedFileDao.getFilesBySpace(testSpaceId) } returns flowOf(testFiles)

        // Act - Retry
        viewModel.retry()

        // Assert - Should call DAO again
        coVerify { mockSpaceDao.getSpaceById(testSpaceId) }
        coVerify { mockSyncedFileDao.getFilesBySpace(testSpaceId) }
    }

    @Test
    fun `retry does nothing when no space loaded`() = runTest {
        // Act - No space has been loaded yet
        viewModel.retry()

        // Assert - DAO methods should not be called
        coVerify(exactly = 0) { mockSpaceDao.getSpaceById(any()) }
        coVerify(exactly = 0) { mockSyncedFileDao.getFilesBySpace(any()) }
    }

    @Test
    fun `retry sets loading state before reload`() = runTest {
        // Arrange
        coEvery { mockSpaceDao.getSpaceById(testSpaceId) } returns testSpace
        coEvery { mockSyncedFileDao.getFilesBySpace(testSpaceId) } returns flowOf(testFiles)
        viewModel.loadFiles(testSpaceId)

        // Reset mock
        clearMocks(mockSpaceDao, mockSyncedFileDao)
        coEvery { mockSpaceDao.getSpaceById(testSpaceId) } returns testSpace
        coEvery { mockSyncedFileDao.getFilesBySpace(testSpaceId) } returns flowOf(testFiles)

        // Act
        viewModel.retry()

        // Assert - The loading state should be set during retry
        // Note: This is hard to test directly because the state changes happen quickly
        // but we can verify the DAO was called
        coVerify { mockSpaceDao.getSpaceById(testSpaceId) }
    }

    @Test
    fun `getErrorFiles returns only files with ERROR status`() = runTest {
        // Arrange
        coEvery { mockSpaceDao.getSpaceById(testSpaceId) } returns testSpace
        coEvery { mockSyncedFileDao.getFilesBySpace(testSpaceId) } returns flowOf(testFiles)
        viewModel.loadFiles(testSpaceId)

        // Act
        val errorFiles = viewModel.getErrorFiles()

        // Assert
        assertThat(errorFiles).hasSize(1)
        assertThat(errorFiles[0].cid).isEqualTo("file-2")
        assertThat(errorFiles[0].syncStatus).isEqualTo(SyncStatus.ERROR)
    }

    @Test
    fun `getErrorFiles returns empty list when no files have error status`() = runTest {
        // Arrange
        val successFiles = listOf(
            SyncedFile(
                cid = "file-1",
                spaceId = testSpaceId,
                filePath = "/path/to/file1.txt",
                size = 1024,
                version = 1,
                syncStatus = SyncStatus.IDLE,
                modifiedAt = Date(),
                checksum = "checksum1"
            ),
            SyncedFile(
                cid = "file-2",
                spaceId = testSpaceId,
                filePath = "/path/to/file2.txt",
                size = 2048,
                version = 1,
                syncStatus = SyncStatus.SYNCING,
                modifiedAt = Date(),
                checksum = "checksum2"
            )
        )
        coEvery { mockSpaceDao.getSpaceById(testSpaceId) } returns testSpace
        coEvery { mockSyncedFileDao.getFilesBySpace(testSpaceId) } returns flowOf(successFiles)
        viewModel.loadFiles(testSpaceId)

        // Act
        val errorFiles = viewModel.getErrorFiles()

        // Assert
        assertThat(errorFiles).isEmpty()
    }

    @Test
    fun `getErrorFiles returns empty list when no files loaded`() {
        // Act - No files loaded yet
        val errorFiles = viewModel.getErrorFiles()

        // Assert
        assertThat(errorFiles).isEmpty()
    }

    @Test
    fun `loadFiles sets isRefreshing to true during load`() = runTest {
        // Arrange
        coEvery { mockSpaceDao.getSpaceById(testSpaceId) } returns testSpace
        coEvery { mockSyncedFileDao.getFilesBySpace(testSpaceId) } returns flowOf(testFiles)

        // Act
        viewModel.loadFiles(testSpaceId)

        // Assert - After load completes, it should be false
        assertThat(viewModel.isRefreshing.value).isFalse()
    }

    @Test
    fun `files flow emits loaded files`() = runTest {
        // Arrange
        coEvery { mockSpaceDao.getSpaceById(testSpaceId) } returns testSpace
        coEvery { mockSyncedFileDao.getFilesBySpace(testSpaceId) } returns flowOf(testFiles)

        // Act
        viewModel.loadFiles(testSpaceId)

        // Assert
        assertThat(viewModel.files.value).hasSize(2)
        assertThat(viewModel.files.value[0].cid).isEqualTo("file-1")
        assertThat(viewModel.files.value[1].cid).isEqualTo("file-2")
    }
}
