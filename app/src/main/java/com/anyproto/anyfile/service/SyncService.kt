package com.anyproto.anyfile.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.anyproto.anyfile.data.config.NetworkConfigRepository
import com.anyproto.anyfile.data.network.SyncClient
import com.anyproto.anyfile.data.network.p2p.P2PFilenodeClient
import com.anyproto.anyfile.domain.watch.FileChangeListener
import com.anyproto.anyfile.domain.watch.FileWatcher
import com.anyproto.anyfile.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
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
        serviceScope.launch { syncClient.disconnect() }
        serviceScope.cancel()
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
            val syncFolder = networkConfigRepository.syncFolderPath

            updateNotification("Connecting...")
            syncClient.connectCoordinator(coordHost, coordPort)
            val fn = syncClient.connectFilenode(fnHost, fnPort)

            updateNotification("Syncing")

            if (syncFolder != null && syncFolder.startsWith("/")) {
                startFileWatcher(syncFolder, fn)
            }

            while (serviceScope.isActive) {
                try {
                    // Download poll cycle — stub for future implementation
                } catch (e: Exception) {
                    // log and continue
                }
                delay(POLL_INTERVAL_MS)
            }
        } catch (e: CancellationException) {
            // normal shutdown
        } catch (e: Exception) {
            updateNotification("Sync error: ${e.message?.take(50)}")
        }
    }

    private fun startFileWatcher(syncFolderPath: String, fn: P2PFilenodeClient) {
        try {
            fileWatcher = FileWatcher(syncFolderPath)
            fileWatcher?.setListener(object : FileChangeListener {
                override fun onFileCreated(path: String) {
                    serviceScope.launch { /* upload stub */ }
                }
                override fun onFileModified(path: String) {
                    serviceScope.launch { /* upload stub */ }
                }
                override fun onFileDeleted(path: String) {
                    // not synced in v1
                }
                override fun onFileMoved(oldPath: String, newPath: String) {
                    serviceScope.launch { /* upload newPath stub */ }
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
