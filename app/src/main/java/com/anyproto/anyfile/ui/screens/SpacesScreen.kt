// app/src/main/java/com/anyproto/anyfile/ui/screens/SpacesScreen.kt
package com.anyproto.anyfile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.anyproto.anyfile.data.database.model.SyncStatus
import com.anyproto.anyfile.ui.theme.StatusConflict
import com.anyproto.anyfile.ui.theme.StatusError
import com.anyproto.anyfile.ui.theme.StatusIdle
import com.anyproto.anyfile.ui.theme.StatusSyncing
import com.anyproto.anyfile.util.ErrorHandler
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Spaces screen displaying all sync spaces with their sync status
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpacesScreen(
    onSpaceClick: (String) -> Unit,
    viewModel: SpacesViewModel = hiltViewModel()
) {
    val spaces by viewModel.spaces.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val serviceSyncStatus by viewModel.serviceSyncStatus.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Snackbar host state for error messages
    val snackbarHostState = remember { SnackbarHostState() }

    // Collect error events
    LaunchedEffect(Unit) {
        viewModel.errorEvent.collect { error ->
            ErrorHandler.showSnackbar(
                scope = scope,
                snackbarHostState = snackbarHostState,
                error = error,
                actionLabel = "Retry",
                onAction = { viewModel.refreshAllSpaces() }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spaces") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.refreshAllSpaces() }
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh all")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            SyncStatusBanner(
                status = serviceSyncStatus,
                syncFolderPath = viewModel.syncFolderPath,
                onStart = { viewModel.startSync(context) },
                onStop = { viewModel.stopSync(context) }
            )
            Box(modifier = Modifier.weight(1f)) {
                if (spaces.isEmpty()) {
                    EmptySpacesState(
                        onRefresh = { viewModel.refreshAllSpaces() },
                        isRefreshing = isRefreshing
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(
                            items = spaces,
                            key = { it.spaceId }
                        ) { space ->
                            SpaceListItem(
                                space = space,
                                onClick = { onSpaceClick(space.spaceId) },
                                onSyncClick = { viewModel.syncSpace(space.spaceId) },
                                isSyncing = space.syncStatus == SyncStatus.SYNCING
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Empty state when no spaces exist
 */
@Composable
fun EmptySpacesState(
    onRefresh: () -> Unit,
    isRefreshing: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CloudOff,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No Spaces",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Create a space to start syncing files",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onRefresh,
            enabled = !isRefreshing
        ) {
            if (isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("Refresh")
        }
    }
}

/**
 * List item for a single space
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceListItem(
    space: com.anyproto.anyfile.data.database.entity.Space,
    onClick: () -> Unit,
    onSyncClick: () -> Unit,
    isSyncing: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = space.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    SyncStatusIndicator(status = space.syncStatus)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "ID: ${space.spaceId.take(8)}...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Last sync: ${formatDate(space.lastSyncAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onSyncClick,
                enabled = !isSyncing
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = "Sync",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/**
 * Visual indicator for sync status
 */
@Composable
fun SyncStatusIndicator(status: SyncStatus) {
    val (color, label) = when (status) {
        SyncStatus.IDLE -> StatusIdle to "Synced"
        SyncStatus.SYNCING -> StatusSyncing to "Syncing"
        SyncStatus.ERROR -> StatusError to "Error"
        SyncStatus.CONFLICT -> StatusConflict to "Conflict"
    }

    Surface(
        color = color.copy(alpha = 0.2f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.height(24.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

/**
 * Format a date for display
 */
private fun formatDate(date: Date?): String {
    return if (date != null) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(date)
    } else {
        "Never"
    }
}

/**
 * Banner showing the sync service status with Start/Stop controls.
 */
@Composable
fun SyncStatusBanner(
    status: ServiceSyncStatus,
    syncFolderPath: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val (statusText, color) = when (status) {
        ServiceSyncStatus.IDLE -> "Paused" to MaterialTheme.colorScheme.onSurfaceVariant
        ServiceSyncStatus.ACTIVE -> "Active" to MaterialTheme.colorScheme.primary
        ServiceSyncStatus.ERROR -> "Error" to MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text("Sync: $statusText", style = MaterialTheme.typography.labelMedium, color = color)
                if (syncFolderPath != null) {
                    Text(
                        syncFolderPath.takeLast(40),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (status == ServiceSyncStatus.ACTIVE) {
                OutlinedButton(onClick = onStop) { Text("Stop") }
            } else {
                Button(onClick = onStart) { Text("Start") }
            }
        }
    }
}
