// app/src/main/java/com/anyproto/anyfile/ui/screens/SpacesViewModel.kt
package com.anyproto.anyfile.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anyproto.anyfile.data.database.dao.SpaceDao
import com.anyproto.anyfile.data.database.model.SyncStatus
import com.anyproto.anyfile.domain.sync.SyncOrchestrator
import com.anyproto.anyfile.domain.sync.SyncResult
import com.anyproto.anyfile.util.AnyfileException
import com.anyproto.anyfile.util.ErrorHandler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for SpacesScreen
 */
@HiltViewModel
class SpacesViewModel @Inject constructor(
    private val spaceDao: SpaceDao,
    private val syncOrchestrator: SyncOrchestrator
) : ViewModel() {

    /**
     * Flow of all spaces from database
     */
    val spaces: StateFlow<List<com.anyproto.anyfile.data.database.entity.Space>> = spaceDao
        .getAllSpaces()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /**
     * Flow representing refresh state
     */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /**
     * Flow for error messages to display in UI
     */
    private val _errorEvent = MutableSharedFlow<Throwable>()
    val errorEvent: SharedFlow<Throwable> = _errorEvent.asSharedFlow()

    /**
     * Clear any pending error event
     */
    fun clearError() {
        // Error events are consumed when displayed
    }

    /**
     * Sync a specific space
     */
    fun syncSpace(spaceId: String) {
        viewModelScope.launch {
            try {
                _isRefreshing.value = true
                val result = syncOrchestrator.sync(spaceId)
                handleSyncResult(result, spaceId)
            } catch (e: Exception) {
                handleError(e, "syncSpace")
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /**
     * Refresh all spaces (triggers sync for all)
     */
    fun refreshAllSpaces() {
        viewModelScope.launch {
            try {
                _isRefreshing.value = true
                val currentSpaces = spaces.value
                var lastError: Throwable? = null

                currentSpaces.forEach { space ->
                    val result = syncOrchestrator.sync(space.spaceId)
                    handleSyncResult(result, space.spaceId)

                    if (result is SyncResult.Failed) {
                        lastError = result.error
                    }
                }

                // Emit the last error if any occurred
                lastError?.let { handleError(it, "refreshAllSpaces") }
            } catch (e: Exception) {
                handleError(e, "refreshAllSpaces")
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /**
     * Handle sync result and update space status accordingly
     */
    private suspend fun handleSyncResult(result: SyncResult, spaceId: String) {
        val space = spaceDao.getSpaceById(spaceId) ?: return

        val newStatus = when (result) {
            is SyncResult.Success -> SyncStatus.IDLE
            is SyncResult.PartialSuccess -> {
                if (result.failedFiles > 0) SyncStatus.ERROR else SyncStatus.IDLE
            }
            is SyncResult.Failed -> SyncStatus.ERROR
        }

        spaceDao.updateSpace(space.copy(syncStatus = newStatus))

        // Emit error event if sync failed
        if (result is SyncResult.Failed) {
            _errorEvent.emit(result.error)
        } else if (result is SyncResult.PartialSuccess && result.failedFiles > 0) {
            _errorEvent.emit(
                AnyfileException.Sync.UploadFailedError(
                    fileName = "${result.failedFiles} file(s)",
                    cause = null
                )
            )
        }
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
