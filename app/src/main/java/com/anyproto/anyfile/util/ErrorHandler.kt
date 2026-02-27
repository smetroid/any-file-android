// app/src/main/java/com/anyproto/anyfile/util/ErrorHandler.kt
package com.anyproto.anyfile.util

import android.content.Context
import android.widget.Toast
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Utility class for handling errors throughout the app.
 *
 * Provides:
 * - User-friendly error messages for display in UI
 * - Logging of technical errors for debugging
 * - Helper functions for common error scenarios
 */
object ErrorHandler {

    private const val TAG = "AnyfileErrorHandler"

    /**
     * Log a throwable to Logcat with appropriate level.
     *
     * @param error The throwable to log
     * @param message Optional additional message context
     */
    fun log(error: Throwable, message: String? = null) {
        val logMessage = message ?: error.message ?: "Unknown error"

        when (error) {
            is AnyfileException -> {
                android.util.Log.w(TAG, "$logMessage | User: ${error.userMessage}", error)
            }
            is UnknownHostException, is ConnectException -> {
                android.util.Log.w(TAG, "Network error: $logMessage", error)
            }
            is SocketTimeoutException -> {
                android.util.Log.w(TAG, "Timeout error: $logMessage", error)
            }
            is FileNotFoundException -> {
                android.util.Log.e(TAG, "File not found: $logMessage", error)
            }
            is IOException -> {
                android.util.Log.e(TAG, "IO error: $logMessage", error)
            }
            else -> {
                android.util.Log.e(TAG, "Unexpected error: $logMessage", error)
            }
        }
    }

    /**
     * Get a user-friendly error message from a throwable.
     *
     * @param error The throwable to convert to a user message
     * @return A user-friendly error message string
     */
    fun getUserMessage(error: Throwable): String {
        return when (val anyfileError = error.toAnyfileException()) {
            is AnyfileException.Network.CoordinatorConnectionError ->
                "Failed to connect to coordinator. Please check your connection."

            is AnyfileException.Network.FilenodeConnectionError ->
                "Failed to connect to storage node. Please try again."

            is AnyfileException.Network.TimeoutError ->
                "Request timed out. Please check your connection and try again."

            is AnyfileException.Network.NoConnectionError ->
                "No internet connection. Please check your network settings."

            is AnyfileException.Network.GenericNetworkError ->
                "Network error: ${anyfileError.userMessage}"

            is AnyfileException.Sync.ConflictError ->
                "Sync conflict detected. Some files need manual resolution."

            is AnyfileException.Sync.UploadFailedError ->
                anyfileError.userMessage

            is AnyfileException.Sync.DownloadFailedError ->
                anyfileError.userMessage

            is AnyfileException.Sync.SpaceNotFoundError ->
                anyfileError.userMessage

            is AnyfileException.Sync.CancelledError ->
                "Sync operation was cancelled"

            is AnyfileException.Storage.FileNotFound ->
                "File not found on device"

            is AnyfileException.Storage.PermissionDeniedError ->
                "Permission denied. Please check app permissions."

            is AnyfileException.Storage.InsufficientSpaceError ->
                "Not enough storage space available on device"

            is AnyfileException.Storage.IoError ->
                "Storage error: ${anyfileError.userMessage}"

            is AnyfileException.Storage.DirectoryNotFoundError ->
                "Directory not found. Please check your sync settings."

            else -> "An unexpected error occurred. Please try again."
        }
    }

    /**
     * Show a toast message for an error.
     *
     * @param context Application or activity context
     * @param error The error to display
     * @param duration Toast duration (default: SHORT)
     */
    fun showToast(context: Context, error: Throwable, duration: Int = Toast.LENGTH_SHORT) {
        log(error)
        Toast.makeText(context, getUserMessage(error), duration).show()
    }

    /**
     * Show a snackbar for an error with optional retry action.
     *
     * @param scope Coroutine scope for launching the snackbar
     * @param snackbarHostState The SnackbarHostState to show the snackbar on
     * @param error The error to display
     * @param actionLabel Optional label for retry action button
     * @param onAction Optional action to perform when user clicks the action button
     */
    fun showSnackbar(
        scope: CoroutineScope,
        snackbarHostState: SnackbarHostState,
        error: Throwable,
        actionLabel: String? = "Retry",
        onAction: (() -> Unit)? = null
    ) {
        log(error)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = getUserMessage(error),
                actionLabel = actionLabel,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed && onAction != null) {
                onAction()
            }
        }
    }

    /**
     * Handle a Result<T> type, showing an error if it's a failure.
     *
     * @param result The result to check
     * @param scope Coroutine scope for launching the snackbar
     * @param snackbarHostState The SnackbarHostState to show errors on
     * @param onSuccess Optional callback when result is successful
     * @return true if result was successful, false otherwise
     */
    fun <T> handleResult(
        result: Result<T>,
        scope: CoroutineScope,
        snackbarHostState: SnackbarHostState,
        onSuccess: ((T) -> Unit)? = null
    ): Boolean {
        return if (result.isSuccess) {
            onSuccess?.invoke(result.getOrNull()!!)
            true
        } else {
            result.exceptionOrNull()?.let { error ->
                showSnackbar(scope, snackbarHostState, error)
            }
            false
        }
    }

    /**
     * Create a network error from a throwable.
     *
     * @param cause The underlying throwable
     * @return An appropriate NetworkException
     */
    fun networkError(cause: Throwable?): AnyfileException.Network {
        return when (cause) {
            is UnknownHostException, is ConnectException ->
                AnyfileException.Network.NoConnectionError()
            is SocketTimeoutException ->
                AnyfileException.Network.TimeoutError(cause)
            else ->
                AnyfileException.Network.GenericNetworkError(
                    detailMessage = cause?.message ?: "Unknown network error",
                    cause = cause
                )
        }
    }

    /**
     * Create a storage error from a throwable.
     *
     * @param cause The underlying throwable
     * @param path Optional file path context
     * @return An appropriate StorageException
     */
    fun storageError(cause: Throwable, path: String? = null): AnyfileException.Storage {
        return when (cause) {
            is java.io.FileNotFoundException ->
                AnyfileException.Storage.FileNotFound(path ?: cause.message ?: "Unknown file")
            is IOException -> {
                val message = cause.message ?: "IO error"
                when {
                    message.contains("permission", ignoreCase = true) ->
                        AnyfileException.Storage.PermissionDeniedError(path ?: "file")
                    message.contains("space", ignoreCase = true) ->
                        AnyfileException.Storage.InsufficientSpaceError()
                    else ->
                        AnyfileException.Storage.IoError(detailMessage = message, cause = cause)
                }
            }
            else ->
                AnyfileException.Storage.IoError(
                    detailMessage = cause.message ?: "Unknown storage error",
                    cause = cause
                )
        }
    }

    /**
     * Create a sync error from a throwable.
     *
     * @param cause The underlying throwable
     * @param fileName Optional file name context
     * @return An appropriate SyncException
     */
    fun syncError(cause: Throwable, fileName: String? = null): AnyfileException.Sync {
        return when (cause) {
            is AnyfileException.Storage.IoError -> {
                if (fileName != null) {
                    AnyfileException.Sync.UploadFailedError(fileName, cause)
                } else {
                    AnyfileException.Sync.UploadFailedError("file", cause)
                }
            }
            else ->
                AnyfileException.Sync.UploadFailedError(
                    fileName ?: "file",
                    cause
                )
        }
    }
}
