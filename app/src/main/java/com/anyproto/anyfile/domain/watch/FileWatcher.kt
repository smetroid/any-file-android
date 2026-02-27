// app/src/main/java/com/anyproto/anyfile/domain/watch/FileWatcher.kt
package com.anyproto.anyfile.domain.watch

import android.os.FileObserver
import java.io.File

/**
 * Listener for file change events.
 */
interface FileChangeListener {
    /**
     * Called when a file is created.
     * @param path The absolute path to the created file
     */
    fun onFileCreated(path: String)

    /**
     * Called when a file is deleted.
     * @param path The absolute path to the deleted file
     */
    fun onFileDeleted(path: String)

    /**
     * Called when a file is modified.
     * @param path The absolute path to the modified file
     */
    fun onFileModified(path: String)

    /**
     * Called when a file is moved.
     * @param oldPath The original path of the file
     * @param newPath The new path of the file
     */
    fun onFileMoved(oldPath: String, newPath: String)
}

/**
 * Wrapper around Android FileObserver to watch for file system changes.
 *
 * This class provides a Kotlin-friendly interface to FileObserver,
 * allowing observation of file creation, modification, deletion, and moves.
 *
 * Note: On Android 10+ (API 29+), scoped storage limits which directories
 * can be watched. This implementation is designed for app-specific directories.
 *
 * @property path The directory path to watch
 */
class FileWatcher(private val path: String) {

    private var fileObserver: AppFileObserver? = null
    private var listener: FileChangeListener? = null

    /**
     * Whether the watcher is currently active.
     */
    val isWatching: Boolean
        get() = fileObserver != null

    /**
     * Start watching the directory for file changes.
     *
     * @throws IllegalArgumentException if the path doesn't exist or isn't a directory
     */
    fun start() {
        val directory = File(path)
        if (!directory.exists()) {
            throw IllegalArgumentException("Path does not exist: $path")
        }
        if (!directory.isDirectory) {
            throw IllegalArgumentException("Path is not a directory: $path")
        }

        if (fileObserver != null) {
            // Already watching
            return
        }

        fileObserver = AppFileObserver(path).apply {
            startWatching()
        }
    }

    /**
     * Stop watching the directory.
     */
    fun stop() {
        fileObserver?.stopWatching()
        fileObserver = null
    }

    /**
     * Set the listener for file change events.
     *
     * @param listener The listener to receive file change events
     */
    fun setListener(listener: FileChangeListener?) {
        this.listener = listener
        fileObserver?.listener = listener
    }

    /**
     * Internal FileObserver implementation that delegates to the FileChangeListener.
     */
    private inner class AppFileObserver(watchPath: String) : FileObserver(watchPath, EVENTS) {

        private val watchPath: String = watchPath
        var listener: FileChangeListener? = null

        override fun onEvent(event: Int, path: String?) {
            if (path == null) return

            val fullPath = "$watchPath/$path".replace("//", "/")

            when (event and MASK) {
                CREATE -> listener?.onFileCreated(fullPath)
                DELETE -> listener?.onFileDeleted(fullPath)
                MODIFY -> listener?.onFileModified(fullPath)
                MOVED_FROM -> {
                    // Store the old path, will be paired with MOVED_TO
                    pendingMovePath = fullPath
                }
                MOVED_TO -> {
                    val oldPath = pendingMovePath
                    if (oldPath != null) {
                        listener?.onFileMoved(oldPath, fullPath)
                        pendingMovePath = null
                    } else {
                        // MOVED_TO without MOVED_FROM, treat as create
                        listener?.onFileCreated(fullPath)
                    }
                }
                CLOSE_WRITE -> {
                    // File was written and closed, treat as modification
                    listener?.onFileModified(fullPath)
                }
            }
        }
    }

    companion object {
        // FileObserver event constants.
        const val CREATE = FileObserver.CREATE
        const val DELETE = FileObserver.DELETE
        const val MODIFY = FileObserver.MODIFY
        const val MOVED_FROM = FileObserver.MOVED_FROM
        const val MOVED_TO = FileObserver.MOVED_TO
        const val CLOSE_WRITE = FileObserver.CLOSE_WRITE

        // FileObserver events to watch
        private const val EVENTS = (
            CREATE or
            DELETE or
            MODIFY or
            MOVED_FROM or
            MOVED_TO or
            CLOSE_WRITE
        )

        // Mask to extract event type
        private const val MASK = FileObserver.ALL_EVENTS

        // Used to pair MOVED_FROM with MOVED_TO events
        private var pendingMovePath: String? = null
    }
}
