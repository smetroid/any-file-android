// app/src/main/java/com/anyproto/anyfile/ui/screens/SettingsViewModel.kt
package com.anyproto.anyfile.ui.screens

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for SettingsScreen
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context
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
            appVersion = getAppVersion(),
            isDebugBuild = isDebugBuild()
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun updateCoordinatorUrl(url: String) {
        viewModelScope.launch {
            prefs.edit().putString(KEY_COORDINATOR_URL, url).apply()
            _uiState.value = _uiState.value.copy(coordinatorUrl = url)
        }
    }

    fun updateSyncInterval(interval: String) {
        viewModelScope.launch {
            prefs.edit().putString(KEY_SYNC_INTERVAL, interval).apply()
            _uiState.value = _uiState.value.copy(syncInterval = interval)
        }
    }

    fun toggleDebugLogging(enabled: Boolean) {
        viewModelScope.launch {
            prefs.edit().putBoolean(KEY_DEBUG_LOGGING, enabled).apply()
            _uiState.value = _uiState.value.copy(debugLoggingEnabled = enabled)
        }
    }

    fun toggleVerboseSyncStatus(enabled: Boolean) {
        viewModelScope.launch {
            prefs.edit().putBoolean(KEY_VERBOSE_SYNC, enabled).apply()
            _uiState.value = _uiState.value.copy(verboseSyncStatus = enabled)
        }
    }

    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun isDebugBuild(): Boolean {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            (packageInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (e: Exception) {
            false
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
