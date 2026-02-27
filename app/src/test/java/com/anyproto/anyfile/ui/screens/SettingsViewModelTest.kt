// app/src/test/java/com/anyproto/anyfile/ui/screens/SettingsViewModelTest.kt
package com.anyproto.anyfile.ui.screens

import android.content.Context
import android.content.SharedPreferences
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import com.google.common.truth.Truth.assertThat

/**
 * Unit tests for SettingsViewModel.
 *
 * Tests cover:
 * - Settings persistence
 * - UI state updates
 * - Error handling
 * - Settings clearing
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private lateinit var viewModel: SettingsViewModel
    private lateinit var mockContext: Context
    private lateinit var mockSharedPreferences: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockContext = mockk()
        mockSharedPreferences = mockk()
        mockEditor = mockk()

        // Mock SharedPreferences behavior
        every { mockContext.getSharedPreferences("anyfile_settings", Context.MODE_PRIVATE) } returns mockSharedPreferences
        every { mockSharedPreferences.getString("coordinator_url", any()) } returns ""
        every { mockSharedPreferences.getString("sync_interval", any()) } returns "Manual"
        every { mockSharedPreferences.getBoolean("debug_logging", false) } returns false
        every { mockSharedPreferences.getBoolean("verbose_sync", false) } returns false

        // Mock Editor behavior
        every { mockSharedPreferences.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockEditor.putBoolean(any(), any()) } returns mockEditor
        every { mockEditor.clear() } returns mockEditor
        every { mockEditor.apply() } just Runs

        // Mock package manager for version info
        val mockPackageManager = mockk<android.content.pm.PackageManager>()
        val mockPackageInfo = mockk<android.content.pm.PackageInfo>()
        mockPackageInfo.versionName = "1.0.0"
        mockPackageInfo.applicationInfo = mockk<android.content.pm.ApplicationInfo>()
        mockPackageInfo.applicationInfo.flags = 0

        every { mockContext.packageManager } returns mockPackageManager
        every { mockContext.packageName } returns "com.anyproto.anyfile"
        every { mockPackageManager.getPackageInfo("com.anyproto.anyfile", 0) } returns mockPackageInfo

        viewModel = SettingsViewModel(mockContext)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `uiState has default values initially`() {
        // Assert
        val state = viewModel.uiState.value
        assertThat(state.coordinatorUrl).isEmpty()
        assertThat(state.syncInterval).isEqualTo("Manual")
        assertThat(state.debugLoggingEnabled).isFalse()
        assertThat(state.verboseSyncStatus).isFalse()
        assertThat(state.appVersion).isEqualTo("1.0.0")
        assertThat(state.isDebugBuild).isFalse()
    }

    @Test
    fun `uiState loads saved coordinator url from preferences`() {
        // Arrange
        every { mockSharedPreferences.getString("coordinator_url", "") } returns "https://example.com"

        // Act
        val testViewModel = SettingsViewModel(mockContext)

        // Assert
        assertThat(testViewModel.uiState.value.coordinatorUrl).isEqualTo("https://example.com")
    }

    @Test
    fun `uiState loads saved sync interval from preferences`() {
        // Arrange
        every { mockSharedPreferences.getString("sync_interval", "Manual") } returns "15 minutes"

        // Act
        val testViewModel = SettingsViewModel(mockContext)

        // Assert
        assertThat(testViewModel.uiState.value.syncInterval).isEqualTo("15 minutes")
    }

    @Test
    fun `uiState loads saved debug logging from preferences`() {
        // Arrange
        every { mockSharedPreferences.getBoolean("debug_logging", false) } returns true

        // Act
        val testViewModel = SettingsViewModel(mockContext)

        // Assert
        assertThat(testViewModel.uiState.value.debugLoggingEnabled).isTrue()
    }

    @Test
    fun `uiState loads saved verbose sync status from preferences`() {
        // Arrange
        every { mockSharedPreferences.getBoolean("verbose_sync", false) } returns true

        // Act
        val testViewModel = SettingsViewModel(mockContext)

        // Assert
        assertThat(testViewModel.uiState.value.verboseSyncStatus).isTrue()
    }

    @Test
    fun `updateCoordinatorUrl saves value and updates uiState`() {
        // Act
        val result = viewModel.updateCoordinatorUrl("https://new-url.com")

        // Assert
        assertThat(result).isInstanceOf(SettingsUpdateResult.Success::class.java)
        assertThat(viewModel.uiState.value.coordinatorUrl).isEqualTo("https://new-url.com")
        verify { mockEditor.putString("coordinator_url", "https://new-url.com") }
        verify { mockEditor.apply() }
    }

    @Test
    fun `updateSyncInterval saves value and updates uiState`() {
        // Act
        val result = viewModel.updateSyncInterval("30 minutes")

        // Assert
        assertThat(result).isInstanceOf(SettingsUpdateResult.Success::class.java)
        assertThat(viewModel.uiState.value.syncInterval).isEqualTo("30 minutes")
        verify { mockEditor.putString("sync_interval", "30 minutes") }
        verify { mockEditor.apply() }
    }

    @Test
    fun `toggleDebugLogging enables debug logging`() {
        // Act
        val result = viewModel.toggleDebugLogging(true)

        // Assert
        assertThat(result).isInstanceOf(SettingsUpdateResult.Success::class.java)
        assertThat(viewModel.uiState.value.debugLoggingEnabled).isTrue()
        verify { mockEditor.putBoolean("debug_logging", true) }
        verify { mockEditor.apply() }
    }

    @Test
    fun `toggleDebugLogging disables debug logging`() {
        // Arrange - Start with enabled
        every { mockSharedPreferences.getBoolean("debug_logging", false) } returns true
        val testViewModel = SettingsViewModel(mockContext)
        every { mockEditor.putBoolean(any(), any()) } returns mockEditor

        // Act
        val result = testViewModel.toggleDebugLogging(false)

        // Assert
        assertThat(result).isInstanceOf(SettingsUpdateResult.Success::class.java)
        assertThat(testViewModel.uiState.value.debugLoggingEnabled).isFalse()
        verify { mockEditor.putBoolean("debug_logging", false) }
        verify { mockEditor.apply() }
    }

    @Test
    fun `toggleVerboseSyncStatus enables verbose sync status`() {
        // Act
        val result = viewModel.toggleVerboseSyncStatus(true)

        // Assert
        assertThat(result).isInstanceOf(SettingsUpdateResult.Success::class.java)
        assertThat(viewModel.uiState.value.verboseSyncStatus).isTrue()
        verify { mockEditor.putBoolean("verbose_sync", true) }
        verify { mockEditor.apply() }
    }

    @Test
    fun `toggleVerboseSyncStatus disables verbose sync status`() {
        // Arrange - Start with enabled
        every { mockSharedPreferences.getBoolean("verbose_sync", false) } returns true
        val testViewModel = SettingsViewModel(mockContext)
        every { mockEditor.putBoolean(any(), any()) } returns mockEditor

        // Act
        val result = testViewModel.toggleVerboseSyncStatus(false)

        // Assert
        assertThat(result).isInstanceOf(SettingsUpdateResult.Success::class.java)
        assertThat(testViewModel.uiState.value.verboseSyncStatus).isFalse()
        verify { mockEditor.putBoolean("verbose_sync", false) }
        verify { mockEditor.apply() }
    }

    @Test
    fun `clearAllSettings resets all settings to defaults`() {
        // Act
        val result = viewModel.clearAllSettings()

        // Assert
        assertThat(result).isInstanceOf(SettingsUpdateResult.Success::class.java)
        val state = viewModel.uiState.value
        assertThat(state.coordinatorUrl).isEmpty()
        assertThat(state.syncInterval).isEqualTo("Manual")
        assertThat(state.debugLoggingEnabled).isFalse()
        assertThat(state.verboseSyncStatus).isFalse()
        // App version and debug build should remain
        assertThat(state.appVersion).isNotEmpty()
        verify { mockEditor.clear() }
        verify { mockEditor.apply() }
    }

    @Test
    fun `isDebugBuild returns true when app is debuggable`() {
        // Arrange
        val mockPackageInfo = mockk<android.content.pm.PackageInfo>()
        mockPackageInfo.versionName = "1.0.0"
        mockPackageInfo.applicationInfo = mockk<android.content.pm.ApplicationInfo>()
        mockPackageInfo.applicationInfo.flags = android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE

        val mockPackageManager = mockk<android.content.pm.PackageManager>()
        every { mockContext.packageManager } returns mockPackageManager
        every { mockContext.packageName } returns "com.anyproto.anyfile"
        every { mockPackageManager.getPackageInfo("com.anyproto.anyfile", 0) } returns mockPackageInfo

        // Act
        val testViewModel = SettingsViewModel(mockContext)

        // Assert
        assertThat(testViewModel.uiState.value.isDebugBuild).isTrue()
    }

    @Test
    fun `multiple settings updates persist correctly`() {
        // Act
        viewModel.updateCoordinatorUrl("https://url1.com")
        viewModel.updateSyncInterval("15 minutes")
        viewModel.toggleDebugLogging(true)
        viewModel.toggleVerboseSyncStatus(true)

        // Assert
        val state = viewModel.uiState.value
        assertThat(state.coordinatorUrl).isEqualTo("https://url1.com")
        assertThat(state.syncInterval).isEqualTo("15 minutes")
        assertThat(state.debugLoggingEnabled).isTrue()
        assertThat(state.verboseSyncStatus).isTrue()

        verify(exactly = 1) { mockEditor.putString("coordinator_url", "https://url1.com") }
        verify(exactly = 1) { mockEditor.putString("sync_interval", "15 minutes") }
        verify(exactly = 1) { mockEditor.putBoolean("debug_logging", true) }
        verify(exactly = 1) { mockEditor.putBoolean("verbose_sync", true) }
        verify(exactly = 4) { mockEditor.apply() }
    }
}
