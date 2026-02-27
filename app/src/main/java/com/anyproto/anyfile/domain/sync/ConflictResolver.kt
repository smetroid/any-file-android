// app/src/main/java/com/anyproto/anyfile/domain/sync/ConflictResolver.kt
package com.anyproto.anyfile.domain.sync

import com.anyproto.anyfile.data.database.entity.SyncedFile
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Strategy for resolving sync conflicts between local and remote file versions.
 */
enum class ConflictResolutionStrategy {
    /**
     * Most recent modification timestamp wins.
     */
    LATEST_WINS,

    /**
     * User must manually resolve the conflict.
     */
    MANUAL,

    /**
     * Keep both versions by renaming the local file.
     */
    BOTH_KEEP
}

/**
 * Result of a conflict resolution operation.
 */
sealed class ConflictResolutionResult {
    /**
     * Use the local version of the file.
     */
    data class UseLocal(val file: SyncedFile) : ConflictResolutionResult()

    /**
     * Use the remote version of the file.
     */
    data class UseRemote(val file: SyncedFile) : ConflictResolutionResult()

    /**
     * Keep both versions - local file will be renamed.
     */
    data class KeepBoth(
        val localFile: SyncedFile,
        val remoteFile: SyncedFile,
        val newLocalPath: String
    ) : ConflictResolutionResult()

    /**
     * Conflict requires manual resolution by the user.
     */
    data class RequiresManualResolution(
        val localFile: SyncedFile,
        val remoteFile: SyncedFile
    ) : ConflictResolutionResult()
}

/**
 * Handles conflict resolution when synchronizing files between local and remote storage.
 *
 * This class provides strategies for resolving conflicts that occur when the same file
 * has been modified on multiple devices.
 */
@Singleton
class ConflictResolver @Inject constructor() {

    /**
     * Resolve a conflict between local and remote file versions using the specified strategy.
     *
     * @param localFile The local file version
     * @param remoteFile The remote file version
     * @param strategy The resolution strategy to apply
     * @return ConflictResolutionResult indicating which version(s) to keep
     */
    fun resolve(
        localFile: SyncedFile,
        remoteFile: SyncedFile,
        strategy: ConflictResolutionStrategy
    ): ConflictResolutionResult {
        return when (strategy) {
            ConflictResolutionStrategy.LATEST_WINS -> resolveLatestWins(localFile, remoteFile)
            ConflictResolutionStrategy.MANUAL -> ConflictResolutionResult.RequiresManualResolution(
                localFile,
                remoteFile
            )
            ConflictResolutionStrategy.BOTH_KEEP -> resolveKeepBoth(localFile, remoteFile)
        }
    }

    /**
     * Resolve conflict by using the most recently modified version.
     */
    private fun resolveLatestWins(
        localFile: SyncedFile,
        remoteFile: SyncedFile
    ): ConflictResolutionResult {
        return if (localFile.modifiedAt.after(remoteFile.modifiedAt)) {
            ConflictResolutionResult.UseLocal(localFile)
        } else {
            ConflictResolutionResult.UseRemote(remoteFile)
        }
    }

    /**
     * Resolve conflict by keeping both versions.
     * The local file will be renamed with a conflict suffix.
     */
    private fun resolveKeepBoth(
        localFile: SyncedFile,
        remoteFile: SyncedFile
    ): ConflictResolutionResult {
        val newLocalPath = generateConflictFileName(localFile.filePath)
        return ConflictResolutionResult.KeepBoth(
            localFile = localFile,
            remoteFile = remoteFile,
            newLocalPath = newLocalPath
        )
    }

    /**
     * Generate a new filename for a conflicted file by appending a timestamp.
     *
     * @param originalPath The original file path
     * @return A new file path with a conflict suffix
     */
    private fun generateConflictFileName(originalPath: String): String {
        val timestamp = Date().time
        val dotIndex = originalPath.lastIndexOf('.')

        return if (dotIndex > 0) {
            val nameWithoutExtension = originalPath.substring(0, dotIndex)
            val extension = originalPath.substring(dotIndex)
            "${nameWithoutExtension}_conflict_$timestamp$extension"
        } else {
            "${originalPath}_conflict_$timestamp"
        }
    }

    /**
     * Detect if there is a conflict between local and remote file versions.
     *
     * @param localFile The local file version (null if file doesn't exist locally)
     * @param remoteFile The remote file version (null if file doesn't exist remotely)
     * @return true if there is a conflict that needs resolution
     */
    fun hasConflict(
        localFile: SyncedFile?,
        remoteFile: SyncedFile?
    ): Boolean {
        // No conflict if one version doesn't exist
        if (localFile == null || remoteFile == null) {
            return false
        }

        // Conflict if versions differ but both have been modified
        return localFile.version != remoteFile.version &&
                localFile.checksum != remoteFile.checksum
    }
}
