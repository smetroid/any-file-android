// app/src/main/java/com/anyproto/anyfile/ui/screens/SpacesViewModel.kt
package com.anyproto.anyfile.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anyproto.anyfile.data.database.dao.SpaceDao
import com.anyproto.anyfile.domain.sync.SyncOrchestrator
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
     * Sync a specific space
     */
    fun syncSpace(spaceId: String) {
        viewModelScope.launch {
            try {
                _isRefreshing.value = true
                syncOrchestrator.sync(spaceId)
            } catch (e: Exception) {
                // Handle error - could show a snackbar in the UI
                e.printStackTrace()
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
                currentSpaces.forEach { space ->
                    syncOrchestrator.sync(space.spaceId)
                }
            } catch (e: Exception) {
                // Handle error
                e.printStackTrace()
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
