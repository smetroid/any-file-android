package com.anyproto.anyfile.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anyproto.anyfile.data.config.NetworkConfigRepository
import com.anyproto.anyfile.data.network.SyncClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class OnboardingState {
    object Initial : OnboardingState()
    object Loading : OnboardingState()
    data class ConfigLoaded(val source: String) : OnboardingState()
    data class Error(val message: String) : OnboardingState()
    object ReadyToSync : OnboardingState()
}

sealed class OnboardingEvent {
    object NavigateToMain : OnboardingEvent()
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val networkConfigRepository: NetworkConfigRepository,
    private val syncClient: SyncClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow<OnboardingState>(OnboardingState.Initial)
    val uiState: StateFlow<OnboardingState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<OnboardingEvent>()
    val events: SharedFlow<OnboardingEvent> = _events.asSharedFlow()

    private var syncFolderReady = false
    private var configReady = false

    /**
     * Fetch config from [source] (file path or https:// URL), validate, and save it.
     */
    fun importConfig(source: String) {
        viewModelScope.launch {
            _uiState.value = OnboardingState.Loading
            try {
                val bytes = networkConfigRepository.fetch(source)
                networkConfigRepository.save(bytes)
                configReady = true
                _uiState.value = OnboardingState.ConfigLoaded(source)
                checkReadyToSync()
            } catch (e: Exception) {
                _uiState.value = OnboardingState.Error(e.message ?: "Import failed")
            }
        }
    }

    /**
     * Store the chosen sync folder path and check if onboarding is complete.
     */
    fun setSyncFolder(path: String) {
        networkConfigRepository.syncFolderPath = path
        syncFolderReady = path.isNotBlank()
        checkReadyToSync()
    }

    /**
     * Emit a navigation event to leave onboarding and proceed to main content.
     */
    fun startSyncing() {
        viewModelScope.launch {
            _events.emit(OnboardingEvent.NavigateToMain)
        }
    }

    private fun checkReadyToSync() {
        if (configReady && syncFolderReady) {
            _uiState.value = OnboardingState.ReadyToSync
        }
    }
}
