// app/src/main/java/com/anyproto/anyfile/domain/watch/FileWatcherManager.kt
package com.anyproto.anyfile.domain.watch

import com.anyproto.anyfile.domain.sync.SyncOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages multiple FileWatcher instances for different spaces.
 *
 * This manager handles:
 * - Starting and stopping watchers for specific spaces
 * - Triggering sync operations when file changes are detected
 * - Respecting app lifecycle (pausing watchers when app is backgrounded)
 *
 * @property syncOrchestrator The sync orchestrator to trigger uploads
 */
@Singleton
class FileWatcherManager @Inject constructor(
    private val syncOrchestrator: SyncOrchestrator
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Map of space ID to its corresponding watcher info.
     */
    private val watchers = ConcurrentHashMap<String, WatcherInfo>()

    /**
     * Whether the app is currently in the foreground.
     */
    @Volatile
    private var isForeground = true

    /**
     * Start watching a space's directory for file changes.
     *
     * @param spaceId The ID of the space to watch
     * @param path The absolute path to the space's directory
     * @throws IllegalArgumentException if the path doesn't exist
     */
    fun watchSpace(spaceId: String, path: String) {
        logDebug("Starting to watch space: $spaceId at path: $path")

        // Stop existing watcher if present
        unwatchSpace(spaceId)

        val watcher = FileWatcher(path)
        val listener = FileChangeEventListener(spaceId, path)

        watcher.setListener(listener)
        watcher.start()

        watchers[spaceId] = WatcherInfo(watcher, path, listener)
    }

    /**
     * Stop watching a space's directory.
     *
     * @param spaceId The ID of the space to stop watching
     */
    fun unwatchSpace(spaceId: String) {
        logDebug( "Stopping watch for space: $spaceId")

        val info = watchers.remove(spaceId)
        info?.watcher?.stop()
    }

    /**
     * Stop watching all spaces.
     */
    fun unwatchAll() {
        logDebug( "Stopping all watchers")

        watchers.keys.toList().forEach { spaceId ->
            unwatchSpace(spaceId)
        }
    }

    /**
     * Called when the app goes to the background.
     * Pauses all file watchers to conserve resources.
     */
    fun onAppBackground() {
        logDebug( "App backgrounded, pausing all watchers")

        isForeground = false

        // Pause all watchers
        watchers.values.forEach { info ->
            info.watcher.stop()
        }
    }

    /**
     * Called when the app comes to the foreground.
     * Resumes all file watchers.
     */
    fun onAppForeground() {
        logDebug( "App foregrounded, resuming all watchers")

        isForeground = true

        // Resume all watchers
        watchers.values.forEach { info ->
            try {
                info.watcher.start()
                info.watcher.setListener(info.listener)
            } catch (e: Exception) {
                logError( "Failed to resume watcher for path: ${info.path}", e)
            }
        }
    }

    /**
     * Check if a space is currently being watched.
     *
     * @param spaceId The ID of the space
     * @return true if the space is being watched
     */
    fun isWatching(spaceId: String): Boolean {
        return watchers.containsKey(spaceId) &&
               watchers[spaceId]?.watcher?.isWatching == true
    }

    /**
     * Get the number of active watchers.
     */
    fun getWatcherCount(): Int = watchers.size

    /**
     * Internal listener that triggers sync when files change.
     */
    private inner class FileChangeEventListener(
        private val spaceId: String,
        private val basePath: String
    ) : FileChangeListener {

        override fun onFileCreated(path: String) {
            logDebug( "File created: $path")
            triggerSyncForFile(path)
        }

        override fun onFileDeleted(path: String) {
            logDebug( "File deleted: $path")
            // TODO: Handle file deletion in sync
            triggerSyncForFile(path)
        }

        override fun onFileModified(path: String) {
            logDebug( "File modified: $path")
            triggerSyncForFile(path)
        }

        override fun onFileMoved(oldPath: String, newPath: String) {
            logDebug( "File moved: $oldPath -> $newPath")
            // TODO: Handle file move in sync
            triggerSyncForFile(newPath)
        }

        private fun triggerSyncForFile(filePath: String) {
            // Only trigger sync if app is in foreground
            if (!isForeground) {
                logDebug( "App is backgrounded, skipping sync trigger for: $filePath")
                return
            }

            // Verify file is within the watched directory
            val file = File(filePath)
            val parentExists = file.parentFile?.exists() == true
            if (!file.exists() && !parentExists) {
                logDebug( "File does not exist, may have been deleted: $filePath")
                return
            }

            // Trigger sync in background
            scope.launch {
                try {
                    if (file.exists()) {
                        logDebug( "Triggering sync upload for file: $filePath")
                        syncOrchestrator.uploadFile(spaceId, filePath)
                    }
                } catch (e: Exception) {
                    logError( "Failed to sync file: $filePath", e)
                }
            }
        }
    }

    /**
     * Holds information about an active watcher.
     */
    private data class WatcherInfo(
        val watcher: FileWatcher,
        val path: String,
        val listener: FileChangeListener
    )

    companion object {
        private const val TAG = "FileWatcherManager"

        /**
         * Safe logging that works in both production and test environments.
         */
        private fun logDebug(message: String) {
            try {
                android.util.Log.d(TAG, message)
            } catch (e: Exception) {
                // Ignore logging errors in test environment
            }
        }

        /**
         * Safe error logging that works in both production and test environments.
         */
        private fun logError(message: String, throwable: Throwable? = null) {
            try {
                if (throwable != null) {
                    android.util.Log.e(TAG, message, throwable)
                } else {
                    android.util.Log.e(TAG, message)
                }
            } catch (e: Exception) {
                // Ignore logging errors in test environment
            }
        }
    }
}
