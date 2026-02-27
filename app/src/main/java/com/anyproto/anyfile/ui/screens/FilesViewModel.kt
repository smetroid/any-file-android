// app/src/main/java/com/anyproto/anyfile/ui/screens/FilesViewModel.kt
package com.anyproto.anyfile.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anyproto.anyfile.data.database.dao.SpaceDao
import com.anyproto.anyfile.data.database.dao.SyncedFileDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

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

                // Get space info
                val space = spaceDao.getSpaceById(spaceId)
                _spaceName.value = space?.name ?: "Unknown Space"

                // Load files
                syncedFileDao.getFilesBySpace(spaceId).collect { fileList ->
                    _files.value = fileList
                    _isRefreshing.value = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _isRefreshing.value = false
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
}
