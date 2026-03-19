// app/src/main/java/com/anyproto/anyfile/ui/screens/SettingsScreen.kt
package com.anyproto.anyfile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.anyproto.anyfile.util.ErrorHandler
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction

/**
 * Settings screen for app configuration
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    // Snackbar host state for error messages
    val snackbarHostState = remember { SnackbarHostState() }

    // Collect error events
    LaunchedEffect(Unit) {
        viewModel.errorEvent.collect { error ->
            ErrorHandler.showSnackbar(
                scope = scope,
                snackbarHostState = snackbarHostState,
                error = error
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Sync Settings Section
            SettingsSection(title = "Sync Settings") {
                SettingItem(
                    title = "Coordinator URL",
                    description = "Server address for space coordination",
                    icon = Icons.Default.Cloud
                ) {
                    var showDialog by remember { mutableStateOf(false) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = uiState.coordinatorUrl.ifEmpty { "Not set" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (uiState.coordinatorUrl.isEmpty())
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                else
                                    MaterialTheme.colorScheme.onSurface
                            )
                        }
                        TextButton(onClick = { showDialog = true }) {
                            Text("Edit")
                        }
                    }

                    if (showDialog) {
                        CoordinatorUrlDialog(
                            currentUrl = uiState.coordinatorUrl,
                            onDismiss = { showDialog = false },
                            onConfirm = { newUrl ->
                                scope.launch {
                                    val result = viewModel.updateCoordinatorUrl(newUrl)
                                    if (result is SettingsUpdateResult.Error) {
                                        ErrorHandler.showSnackbar(
                                            scope = scope,
                                            snackbarHostState = snackbarHostState,
                                            error = Exception(result.message)
                                        )
                                    }
                                }
                                showDialog = false
                            }
                        )
                    }
                }

                // Space ID field — inline text entry backed by NetworkConfigRepository
                run {
                    val focusManager = LocalFocusManager.current
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        OutlinedTextField(
                            value = uiState.spaceId,
                            onValueChange = { viewModel.updateSpaceId(it) },
                            label = { Text("Space ID") },
                            placeholder = { Text("Paste space UUID here") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        )
                    }
                }

                SettingItem(
                    title = "Sync Interval",
                    description = "How often to check for changes",
                    icon = Icons.Default.Schedule
                ) {
                    var expanded by remember { mutableStateOf(false) }
                    val intervals = listOf("Manual", "15 minutes", "30 minutes", "1 hour", "2 hours")

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = uiState.syncInterval,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = !expanded }
                        ) {
                            OutlinedButton(
                                onClick = { expanded = true },
                                modifier = Modifier.menuAnchor()
                            ) {
                                Text("Change")
                            }
                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                intervals.forEach { interval ->
                                    DropdownMenuItem(
                                        text = { Text(interval) },
                                        onClick = {
                                            scope.launch {
                                                val result = viewModel.updateSyncInterval(interval)
                                                if (result is SettingsUpdateResult.Error) {
                                                    ErrorHandler.showSnackbar(
                                                        scope = scope,
                                                        snackbarHostState = snackbarHostState,
                                                        error = Exception(result.message)
                                                    )
                                                }
                                            }
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // Debug Settings Section
            SettingsSection(title = "Debug") {
                SettingSwitch(
                    title = "Enable Debug Logging",
                    description = "Show detailed sync logs",
                    icon = Icons.Default.Info,
                    checked = uiState.debugLoggingEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            val result = viewModel.toggleDebugLogging(enabled)
                            if (result is SettingsUpdateResult.Error) {
                                ErrorHandler.showSnackbar(
                                    scope = scope,
                                    snackbarHostState = snackbarHostState,
                                    error = Exception(result.message)
                                )
                            }
                        }
                    }
                )

                SettingSwitch(
                    title = "Verbose Sync Status",
                    description = "Show detailed sync progress",
                    icon = Icons.Default.Visibility,
                    checked = uiState.verboseSyncStatus,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            val result = viewModel.toggleVerboseSyncStatus(enabled)
                            if (result is SettingsUpdateResult.Error) {
                                ErrorHandler.showSnackbar(
                                    scope = scope,
                                    snackbarHostState = snackbarHostState,
                                    error = Exception(result.message)
                                )
                            }
                        }
                    }
                )
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // About Section
            SettingsSection(title = "About") {
                SettingItem(
                    title = "App Version",
                    description = "Current application version",
                    icon = Icons.Default.Info
                ) {
                    Text(
                        text = uiState.appVersion,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                SettingItem(
                    title = "Build Info",
                    description = "Build configuration",
                    icon = Icons.Default.Build
                ) {
                    Text(
                        text = if (uiState.isDebugBuild) "Debug" else "Release",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Extra padding at bottom
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Section header for settings groups
 */
@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        content()
    }
}

/**
 * Setting item with icon, title, description, and custom content
 */
@Composable
fun SettingItem(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            content()
        }
    }
}

/**
 * Setting item with toggle switch
 */
@Composable
fun SettingSwitch(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

/**
 * Dialog for editing coordinator URL
 */
@Composable
fun CoordinatorUrlDialog(
    currentUrl: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(currentUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Coordinator URL") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Server URL") },
                placeholder = { Text("wss://coordinator.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * UI state for settings screen
 */
data class SettingsUiState(
    val coordinatorUrl: String = "",
    val syncInterval: String = "Manual",
    val debugLoggingEnabled: Boolean = false,
    val verboseSyncStatus: Boolean = false,
    val spaceId: String = "",
    val appVersion: String = "0.1.0",
    val isDebugBuild: Boolean = false
)
