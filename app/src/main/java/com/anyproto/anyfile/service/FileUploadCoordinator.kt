package com.anyproto.anyfile.service

import android.util.Log
import com.anyproto.anyfile.domain.sync.SyncOrchestrator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Delegates file upload events from [SyncService]'s file watcher to [SyncOrchestrator].
 *
 * Uses a hardcoded space ID ("default-space") until space management is wired from config/Room.
 */
@Singleton
class FileUploadCoordinator @Inject constructor(
    private val syncOrchestrator: SyncOrchestrator,
) {
    companion object {
        private const val TAG = "FileUploadCoordinator"
        const val DEFAULT_SPACE_ID = "default-space"
    }

    suspend fun upload(path: String) {
        try {
            val result = syncOrchestrator.uploadFile(DEFAULT_SPACE_ID, path)
            Log.d(TAG, "Uploaded $path: $result")
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed for $path: ${e.message}")
        }
    }
}
