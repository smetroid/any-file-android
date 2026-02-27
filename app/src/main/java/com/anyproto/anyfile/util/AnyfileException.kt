// app/src/main/java/com/anyproto/anyfile/util/AnyfileException.kt
package com.anyproto.anyfile.util

/**
 * Base exception for all Anyfile app errors.
 * Provides user-friendly error messages that can be displayed in the UI.
 *
 * @param message User-friendly error message
 * @param cause The underlying throwable that caused this error
 */
sealed class AnyfileException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    /**
     * User-friendly error message for UI display
     */
    val userMessage: String = message

    /**
     * Network-related errors (connection failures, timeouts, etc.)
     */
    sealed class Network(
        message: String,
        cause: Throwable? = null
    ) : AnyfileException(message, cause) {

        /**
         * Failed to connect to the coordinator service.
         */
        class CoordinatorConnectionError(cause: Throwable? = null) : Network(
            message = "Failed to connect to coordinator service",
            cause = cause
        )

        /**
         * Failed to connect to a filenode.
         */
        class FilenodeConnectionError(cause: Throwable? = null) : Network(
            message = "Failed to connect to storage node",
            cause = cause
        )

        /**
         * Network request timed out.
         */
        class TimeoutError(cause: Throwable? = null) : Network(
            message = "Request timed out. Please check your connection.",
            cause = cause
        )

        /**
         * No internet connection available.
         */
        class NoConnectionError : Network(
            message = "No internet connection. Please check your network settings."
        )

        /**
         * Generic network error with a custom message.
         */
        class GenericNetworkError(
            detailMessage: String,
            cause: Throwable? = null
        ) : Network(
            message = "Network error: $detailMessage",
            cause = cause
        )
    }

    /**
     * Sync-related errors (conflicts, upload failures, etc.)
     */
    sealed class Sync(
        message: String,
        cause: Throwable? = null
    ) : AnyfileException(message, cause) {

        /**
         * Conflict detected between local and remote file versions.
         */
        class ConflictError(cause: Throwable? = null) : Sync(
            message = "Sync conflict detected. Some files need manual resolution.",
            cause = cause
        )

        /**
         * Failed to upload a file.
         */
        class UploadFailedError(
            fileName: String,
            cause: Throwable? = null
        ) : Sync(
            message = "Failed to upload file: $fileName",
            cause = cause
        )

        /**
         * Failed to download a file.
         */
        class DownloadFailedError(
            fileName: String,
            cause: Throwable? = null
        ) : Sync(
            message = "Failed to download file: $fileName",
            cause = cause
        )

        /**
         * Space not found in the database or network.
         */
        class SpaceNotFoundError(spaceId: String) : Sync(
            message = "Space not found: ${spaceId.take(8)}..."
        )

        /**
         * Sync operation was cancelled.
         */
        class CancelledError : Sync(
            message = "Sync operation was cancelled"
        )
    }

    /**
     * Storage/File-related errors (file not found, permission denied, etc.)
     */
    sealed class Storage(
        message: String,
        cause: Throwable? = null
    ) : AnyfileException(message, cause) {

        /**
         * File not found at the specified path.
         */
        class FileNotFound(filePath: String) : Storage(
            message = "File not found: ${filePath.substringAfterLast('/')}"
        )

        /**
         * Permission denied when accessing a file or directory.
         */
        class PermissionDeniedError(path: String) : Storage(
            message = "Permission denied accessing: ${path.substringAfterLast('/')}"
        )

        /**
         * Insufficient storage space on the device.
         */
        class InsufficientSpaceError : Storage(
            message = "Not enough storage space available"
        )

        /**
         * IO error when reading/writing files.
         */
        class IoError(
            detailMessage: String,
            cause: Throwable? = null
        ) : Storage(
            message = "Storage error: $detailMessage",
            cause = cause
        )

        /**
         * Directory not found or could not be created.
         */
        class DirectoryNotFoundError(path: String) : Storage(
            message = "Directory not found: $path"
        )
    }
}

/**
 * Convert any Throwable to an AnyfileException for consistent error handling.
 */
fun Throwable.toAnyfileException(): AnyfileException {
    return when (this) {
        is AnyfileException -> this
        is java.net.UnknownHostException, is java.net.ConnectException ->
            AnyfileException.Network.NoConnectionError()
        is java.net.SocketTimeoutException ->
            AnyfileException.Network.TimeoutError(this)
        is java.io.FileNotFoundException ->
            AnyfileException.Storage.FileNotFound(message ?: "Unknown file")
        is java.io.IOException ->
            AnyfileException.Storage.IoError(message ?: "IO error", this)
        else -> AnyfileException.Storage.IoError(
            detailMessage = message ?: "An unexpected error occurred",
            cause = this
        )
    }
}
