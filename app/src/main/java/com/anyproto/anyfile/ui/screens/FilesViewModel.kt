// app/src/main/java/com/anyproto/anyfile/ui/screens/FilesViewModel.kt
package com.anyproto.anyfile.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anyproto.anyfile.data.database.dao.SpaceDao
import com.anyproto.anyfile.data.database.dao.SyncedFileDao
import com.anyproto.anyfile.data.database.model.SyncStatus
import com.anyproto.anyfile.util.ErrorHandler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for FilesScreen
 */
data class FilesUiState(
    val isLoading: Boolean = false,
    val error: Throwable? = null,
    val isEmpty: Boolean = false
)

/**
 * ViewModel for FilesScreen
 */
@HiltViewModel
class FilesViewModel @Inject constructor(
    private val spaceDao: SpaceDao,
    private val syncedFileDao: SyncedFileDao
) : ViewModel() {

    /**
     * Flow of files for the current space
     */
    private val _files = MutableStateFlow<List<com.anyproto.anyfile.data.database.entity.SyncedFile>>(emptyList())
    val files: StateFlow<List<com.anyproto.anyfile.data.database.entity.SyncedFile>> = _files.asStateFlow()

    /**
     * Flow of the current space name
     */
    private val _spaceName = MutableStateFlow("")
    val spaceName: StateFlow<String> = _spaceName.asStateFlow()

    /**
     * Flow representing refresh state
     */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /**
     * Flow for UI state (loading, error, etc.)
     */
    private val _uiState = MutableStateFlow(FilesUiState())
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()

    /**
     * Flow for error messages to display in UI
     */
    private val _errorEvent = MutableSharedFlow<Throwable>()
    val errorEvent: SharedFlow<Throwable> = _errorEvent.asSharedFlow()

    private var currentSpaceId: String = ""

    /**
     * Load files for a specific space
     */
    fun loadFiles(spaceId: String) {
        if (spaceId.isEmpty()) return

        currentSpaceId = spaceId
        viewModelScope.launch {
            try {
                _isRefreshing.value = true
                _uiState.value = FilesUiState(isLoading = true)

                // Get space info
                val space = spaceDao.getSpaceById(spaceId)
                _spaceName.value = space?.name ?: "Unknown Space"

                if (space == null) {
                    _uiState.value = FilesUiState(
                        isLoading = false,
                        error = com.anyproto.anyfile.util.AnyfileException.Sync.SpaceNotFoundError(spaceId)
                    )
                    _isRefreshing.value = false
                    return@launch
                }

                // Load files
                syncedFileDao.getFilesBySpace(spaceId).collect { fileList ->
                    _files.value = fileList
                    _isRefreshing.value = false
                    _uiState.value = FilesUiState(
                        isLoading = false,
                        isEmpty = fileList.isEmpty()
                    )
                }
            } catch (e: Exception) {
                handleError(e, "loadFiles")
                _isRefreshing.value = false
                _uiState.value = FilesUiState(isLoading = false)
            }
        }
    }

    /**
     * Refresh the current file list
     */
    fun refreshFiles() {
        if (currentSpaceId.isNotEmpty()) {
            loadFiles(currentSpaceId)
        }
    }

    /**
     * Retry loading files after an error
     */
    fun retry() {
        if (currentSpaceId.isNotEmpty()) {
            _uiState.value = FilesUiState(isLoading = true)
            loadFiles(currentSpaceId)
        }
    }

    /**
     * Get files in error state
     */
    fun getErrorFiles(): List<com.anyproto.anyfile.data.database.entity.SyncedFile> {
        return files.value.filter { it.syncStatus == SyncStatus.ERROR }
    }

    /**
     * Handle errors by logging and emitting to error event flow
     */
    private fun handleError(error: Throwable, context: String) {
        ErrorHandler.log(error, "Error in $context")
        viewModelScope.launch {
            _errorEvent.emit(error)
        }
    }
}
