// app/src/main/java/com/anyproto/anyfile/ui/screens/SettingsViewModel.kt
package com.anyproto.anyfile.ui.screens

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anyproto.anyfile.data.config.NetworkConfigRepository
import com.anyproto.anyfile.util.ErrorHandler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Result of a settings update operation
 */
sealed class SettingsUpdateResult {
    object Success : SettingsUpdateResult()
    data class Error(val message: String) : SettingsUpdateResult()
}

/**
 * ViewModel for SettingsScreen
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkConfigRepository: NetworkConfigRepository,
) : ViewModel() {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            coordinatorUrl = prefs.getString(KEY_COORDINATOR_URL, "") ?: "",
            syncInterval = prefs.getString(KEY_SYNC_INTERVAL, "Manual") ?: "Manual",
            debugLoggingEnabled = prefs.getBoolean(KEY_DEBUG_LOGGING, false),
            verboseSyncStatus = prefs.getBoolean(KEY_VERBOSE_SYNC, false),
            spaceId = networkConfigRepository.spaceId ?: "",
            appVersion = getAppVersion(),
            isDebugBuild = isDebugBuild()
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /**
     * Flow for error messages to display in UI
     */
    private val _errorEvent = MutableSharedFlow<Throwable>()
    val errorEvent: SharedFlow<Throwable> = _errorEvent.asSharedFlow()

    /**
     * Update coordinator URL
     */
    fun updateCoordinatorUrl(url: String): SettingsUpdateResult {
        return try {
            prefs.edit().putString(KEY_COORDINATOR_URL, url).apply()
            _uiState.value = _uiState.value.copy(coordinatorUrl = url)
            SettingsUpdateResult.Success
        } catch (e: Exception) {
            handleError(e, "updateCoordinatorUrl")
            SettingsUpdateResult.Error("Failed to save coordinator URL")
        }
    }

    /**
     * Update sync interval
     */
    fun updateSyncInterval(interval: String): SettingsUpdateResult {
        return try {
            prefs.edit().putString(KEY_SYNC_INTERVAL, interval).apply()
            _uiState.value = _uiState.value.copy(syncInterval = interval)
            SettingsUpdateResult.Success
        } catch (e: Exception) {
            handleError(e, "updateSyncInterval")
            SettingsUpdateResult.Error("Failed to save sync interval")
        }
    }

    /**
     * Toggle debug logging
     */
    fun toggleDebugLogging(enabled: Boolean): SettingsUpdateResult {
        return try {
            prefs.edit().putBoolean(KEY_DEBUG_LOGGING, enabled).apply()
            _uiState.value = _uiState.value.copy(debugLoggingEnabled = enabled)
            SettingsUpdateResult.Success
        } catch (e: Exception) {
            handleError(e, "toggleDebugLogging")
            SettingsUpdateResult.Error("Failed to update debug logging")
        }
    }

    /**
     * Toggle verbose sync status
     */
    fun toggleVerboseSyncStatus(enabled: Boolean): SettingsUpdateResult {
        return try {
            prefs.edit().putBoolean(KEY_VERBOSE_SYNC, enabled).apply()
            _uiState.value = _uiState.value.copy(verboseSyncStatus = enabled)
            SettingsUpdateResult.Success
        } catch (e: Exception) {
            handleError(e, "toggleVerboseSyncStatus")
            SettingsUpdateResult.Error("Failed to update verbose sync status")
        }
    }

    /**
     * Update space ID (stored in NetworkConfigRepository for SyncService to pick up)
     */
    fun updateSpaceId(value: String): SettingsUpdateResult {
        return try {
            networkConfigRepository.spaceId = value.takeIf { it.isNotBlank() }
            _uiState.value = _uiState.value.copy(spaceId = value)
            SettingsUpdateResult.Success
        } catch (e: Exception) {
            handleError(e, "updateSpaceId")
            SettingsUpdateResult.Error("Failed to save space ID")
        }
    }

    /**
     * Clear all settings
     */
    fun clearAllSettings(): SettingsUpdateResult {
        return try {
            prefs.edit().clear().apply()
            networkConfigRepository.spaceId = null
            _uiState.value = SettingsUiState(
                coordinatorUrl = "",
                syncInterval = "Manual",
                debugLoggingEnabled = false,
                verboseSyncStatus = false,
                spaceId = "",
                appVersion = getAppVersion(),
                isDebugBuild = isDebugBuild()
            )
            SettingsUpdateResult.Success
        } catch (e: Exception) {
            handleError(e, "clearAllSettings")
            SettingsUpdateResult.Error("Failed to clear settings")
        }
    }

    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "Unknown"
        } catch (e: Exception) {
            ErrorHandler.log(e, "getAppVersion")
            "Unknown"
        }
    }

    private fun isDebugBuild(): Boolean {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            (packageInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (e: Exception) {
            ErrorHandler.log(e, "isDebugBuild")
            false
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

    companion object {
        private const val PREFS_NAME = "anyfile_settings"
        private const val KEY_COORDINATOR_URL = "coordinator_url"
        private const val KEY_SYNC_INTERVAL = "sync_interval"
        private const val KEY_DEBUG_LOGGING = "debug_logging"
        private const val KEY_VERBOSE_SYNC = "verbose_sync"
    }
}
