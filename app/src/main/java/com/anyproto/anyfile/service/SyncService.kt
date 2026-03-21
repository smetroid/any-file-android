package com.anyproto.anyfile.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.anyproto.anyfile.data.config.NetworkConfigRepository
import com.anyproto.anyfile.data.network.Base58Btc
import com.anyproto.anyfile.data.network.SyncClient
import com.anyproto.anyfile.domain.watch.FileChangeListener
import com.anyproto.anyfile.domain.watch.FileWatcher
import com.anyproto.anyfile.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.io.File
import javax.inject.Inject

/**
 * Foreground service that keeps the P2P sync running while the app is in the background.
 *
 * Lifecycle:
 * 1. [onStartCommand] - starts foreground notification, launches [runSyncLoop] in [serviceScope]
 * 2. [runSyncLoop] - connects coordinator + filenode, optionally starts [FileWatcher]
 * 3. [onDestroy] - stops watcher, disconnects clients, cancels coroutine scope
 *
 * Use [start] / [stop] companion helpers from any Context.
 */
@AndroidEntryPoint
class SyncService : Service() {

    companion object {
        private const val TAG = "SyncService"
        const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "anyfile_sync"
        private const val POLL_INTERVAL_MS = 10_000L

        /**
         * Start the foreground sync service.
         */
        fun start(context: Context) {
            val intent = Intent(context, SyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stop the foreground sync service.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, SyncService::class.java))
        }
    }

    @Inject lateinit var syncClient: SyncClient
    @Inject lateinit var networkConfigRepository: NetworkConfigRepository
    @Inject lateinit var uploadCoordinator: FileUploadCoordinator

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var fileWatcher: FileWatcher? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Starting sync..."))
        serviceScope.launch { runSyncLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        fileWatcher?.stop()
        serviceScope.cancel()  // stop poll loop
        // Disconnect in a one-shot scope that cannot be cancelled
        CoroutineScope(Dispatchers.IO).launch {
            syncClient.disconnect()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun runSyncLoop() {
        try {
            if (!networkConfigRepository.isConfigured()) {
                updateNotification("Not configured")
                stopSelf()
                return
            }

            val (coordHost, coordPort) = networkConfigRepository.getCoordinatorAddress()
            val (fnHost, fnPort) = networkConfigRepository.getFilenodeAddress()
            val syncDir = (getExternalFilesDir(null) ?: File(filesDir, "sync")).also { it.mkdirs() }

            updateNotification("Connecting...")
            syncClient.connectCoordinator(coordHost, coordPort)
            val fn = syncClient.connectFilenode(fnHost, fnPort)

            updateNotification("Syncing")
            startFileWatcher(syncDir.absolutePath)

            // Upload any files already in syncDir sequentially to avoid Redis lock contention:
            // filenode's per-account limit check uses a single distributed lock, so concurrent
            // blockPush calls for the same account all fail to acquire it.
            serviceScope.launch {
                syncDir.listFiles()?.filter { it.isFile }?.forEach { file ->
                    uploadCoordinator.upload(file.absolutePath)
                }
            }

            val localFileIds = mutableSetOf<String>()
            while (serviceScope.isActive) {
                try {
                    val spaceId = networkConfigRepository.spaceId
                    if (spaceId != null) {
                        val filesGetResult = fn.filesGet(spaceId)
                        val remoteIds = filesGetResult.getOrNull() ?: emptyList()
                        Log.d(TAG, "Poll: spaceId=${spaceId.take(20)}... filesGet=${filesGetResult.isSuccess} count=${remoteIds.size} ids=$remoteIds err=${filesGetResult.exceptionOrNull()?.message}")
                        for (fileId in remoteIds) {
                            if (fileId !in localFileIds) {
                                // fileId = "relPath|base58btc(CID)" — Go's buildFileID convention
                                val pipeIdx = fileId.lastIndexOf('|')
                                if (pipeIdx < 0) {
                                    Log.w(TAG, "Remote file $fileId missing '|' separator, skipping")
                                    localFileIds += fileId
                                    continue
                                }
                                val relPath = fileId.substring(0, pipeIdx)
                                val base58Cid = fileId.substring(pipeIdx + 1)
                                val cid = try {
                                    Base58Btc.decode(base58Cid)
                                } catch (e: IllegalArgumentException) {
                                    Log.w(TAG, "Remote file $fileId has non-base58 CID, skipping")
                                    localFileIds += fileId
                                    continue
                                }
                                val result = fn.blockGet(spaceId, cid).getOrNull()
                                if (result != null) {
                                    File(syncDir, relPath).apply { parentFile?.mkdirs() }.writeBytes(result.data)
                                    localFileIds += fileId
                                    Log.d(TAG, "Downloaded $relPath (fileId=$fileId)")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Poll error: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        } catch (e: CancellationException) {
            // normal shutdown
        } catch (e: Exception) {
            updateNotification("Sync error: ${e.message?.take(50)}")
        }
    }

    private fun startFileWatcher(syncFolderPath: String) {
        try {
            fileWatcher = FileWatcher(syncFolderPath)
            fileWatcher?.setListener(object : FileChangeListener {
                override fun onFileCreated(path: String) {
                    serviceScope.launch { uploadCoordinator.upload(path) }
                }
                override fun onFileModified(path: String) {
                    serviceScope.launch { uploadCoordinator.upload(path) }
                }
                override fun onFileDeleted(path: String) {
                    // not synced in v1
                }
                override fun onFileMoved(oldPath: String, newPath: String) {
                    serviceScope.launch { uploadCoordinator.upload(newPath) }
                }
            })
            fileWatcher?.start()
        } catch (e: Exception) {
            // watcher not critical — continue without it
        }
    }

    private fun buildNotification(status: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("AnyFile Sync")
            .setContentText(status)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(com.anyproto.anyfile.R.string.sync_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows sync status"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}
