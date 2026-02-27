package com.anyproto.anyfile.di

import android.content.Context
import androidx.startup.Initializer
import com.anyproto.anyfile.worker.SyncWorkerEnqueuer

/**
 * WorkManager initializer that enqueues the SyncWorker for periodic background sync.
 *
 * This initializer is called when the app starts and ensures that the
 * SyncWorker is properly enqueued with WorkManager.
 *
 * The SyncWorker will:
 * - Run every 15 minutes (minimum allowed by WorkManager)
 * - Only run when network is available
 * - Sync all active spaces
 */
class WorkManagerInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        // Enqueue the SyncWorker for periodic background sync
        SyncWorkerEnqueuer.enqueue(context)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> {
        return emptyList()
    }
}
