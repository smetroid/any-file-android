// app/src/main/java/com/anyproto/anyfile/ui/screens/FilesScreen.kt
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
 * Files screen displaying all files in a space
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    spaceId: String,
    viewModel: FilesViewModel = hiltViewModel()
) {
    val files by viewModel.files.collectAsState()
    val spaceName by viewModel.spaceName.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
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
                error = error,
                actionLabel = "Retry",
                onAction = { viewModel.retry() }
            )
        }
    }

    // Load files when space ID changes
    LaunchedEffect(spaceId) {
        if (spaceId.isNotEmpty()) {
            viewModel.loadFiles(spaceId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = spaceName.ifEmpty { "Files" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (spaceId.isNotEmpty()) {
                            Text(
                                text = "Space: ${spaceId.take(8)}...",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                spaceId.isEmpty() -> {
                    NoSpaceSelectedState()
                }
                uiState.isLoading -> {
                    LoadingState()
                }
                files.isEmpty() -> {
                    EmptyFilesState(
                        spaceName = spaceName,
                        onRefresh = { viewModel.refreshFiles() },
                        isRefreshing = isRefreshing
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = files,
                            key = { it.cid }
                        ) { file ->
                            FileListItem(file = file)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Loading state indicator
 */
@Composable
fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                strokeWidth = 4.dp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Loading files...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Empty state when no space is selected
 */
@Composable
fun NoSpaceSelectedState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No Space Selected",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Select a space from the Spaces tab to view files",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Empty state when a space has no files
 */
@Composable
fun EmptyFilesState(
    @Suppress("UNUSED_PARAMETER") spaceName: String,
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
            imageVector = Icons.Default.Description,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No Files",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "This space has no synced files yet",
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
 * List item for a single file
 */
@Composable
fun FileListItem(
    file: com.anyproto.anyfile.data.database.entity.SyncedFile
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = getFileIcon(file.filePath),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = getFileName(file.filePath),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatFileSize(file.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "v${file.version}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FileSyncStatusBadge(status = file.syncStatus)
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Modified: ${formatDate(file.modifiedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Badge showing file sync status
 */
@Composable
fun FileSyncStatusBadge(status: SyncStatus) {
    val (color, label) = when (status) {
        SyncStatus.IDLE -> StatusIdle to "Synced"
        SyncStatus.SYNCING -> StatusSyncing to "Syncing"
        SyncStatus.ERROR -> StatusError to "Error"
        SyncStatus.CONFLICT -> StatusConflict to "Conflict"
    }

    Surface(
        color = color.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.extraSmall,
        modifier = Modifier.height(18.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

/**
 * Get icon for file based on extension
 */
@Composable
fun getFileIcon(filePath: String): androidx.compose.ui.graphics.vector.ImageVector {
    val extension = filePath.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "jpg", "jpeg", "png", "gif", "bmp" -> Icons.Default.Image
        "pdf" -> Icons.Default.PictureAsPdf
        "txt", "md", "log" -> Icons.Default.Description
        "mp4", "mov", "avi" -> Icons.Default.VideoFile
        "mp3", "wav", "flac" -> Icons.Default.AudioFile
        "zip", "rar", "7z" -> Icons.Default.Archive
        else -> Icons.Default.InsertDriveFile
    }
}

/**
 * Extract filename from path
 */
private fun getFileName(path: String): String {
    return path.substringAfterLast('/')
}

/**
 * Format file size for display
 */
private fun formatFileSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
        else -> "${size / (1024 * 1024 * 1024)} GB"
    }
}

/**
 * Format a date for display
 */
private fun formatDate(date: Date): String {
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(date)
}
