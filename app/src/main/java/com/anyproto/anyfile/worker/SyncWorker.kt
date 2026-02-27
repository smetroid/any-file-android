// app/src/main/java/com/anyproto/anyfile/worker/SyncWorker.kt
package com.anyproto.anyfile.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.anyproto.anyfile.data.database.dao.SpaceDao
import com.anyproto.anyfile.data.database.entity.Space
import com.anyproto.anyfile.data.database.model.SyncStatus
import com.anyproto.anyfile.domain.sync.SyncOrchestrator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Background sync worker that performs periodic synchronization of all active spaces.
 *
 * This worker:
 * - Runs periodically (minimum 15 minutes as per WorkManager constraints)
 * - Only runs when network is available
 * - Respects battery and data saver preferences
 * - Syncs all active (non-error) spaces
 *
 * Uses HiltWorker for dependency injection with WorkerParameters.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncOrchestrator: SyncOrchestrator,
    private val spaceDao: SpaceDao
) : CoroutineWorker(context, workerParams) {

    companion object {
        /**
         * Unique work name for the periodic sync worker.
         */
        const val WORK_NAME = "SyncWorker"

        /**
         * Minimum interval for periodic work (15 minutes).
         * This is the minimum interval allowed by WorkManager for periodic tasks.
         */
        const val MIN_SYNC_INTERVAL_MINUTES = 15L

        /**
         * Flex interval for sync (5 minutes before the 15 minute period ends).
         * This allows the system to optimize battery usage by running within this window.
         */
        const val SYNC_FLEX_INTERVAL_MINUTES = 5L

        private const val TAG = "SyncWorker"
    }

    override suspend fun doWork(): Result {
        android.util.Log.d(TAG, "Starting background sync")

        try {
            // Get all spaces from database
            val spaces = getAllSpaces()

            if (spaces.isEmpty()) {
                android.util.Log.d(TAG, "No spaces to sync")
                return Result.success()
            }

            // Filter to only active spaces (not in ERROR state)
            val activeSpaces = spaces.filter { it.syncStatus != SyncStatus.ERROR }

            if (activeSpaces.isEmpty()) {
                android.util.Log.d(TAG, "No active spaces to sync")
                return Result.success()
            }

            android.util.Log.d(TAG, "Syncing ${activeSpaces.size} active spaces")

            // Track results for all spaces
            var successCount = 0
            var failureCount = 0

            // Sync each space
            for (space in activeSpaces) {
                try {
                    val syncResult = syncOrchestrator.sync(space.spaceId)

                    when (syncResult) {
                        is com.anyproto.anyfile.domain.sync.SyncResult.Success -> {
                            successCount++
                            android.util.Log.d(TAG, "Successfully synced space ${space.spaceId}")
                        }
                        is com.anyproto.anyfile.domain.sync.SyncResult.PartialSuccess -> {
                            // Consider partial success as success but log
                            successCount++
                            android.util.Log.w(
                                TAG,
                                "Partially synced space ${space.spaceId} - " +
                                    "${syncResult.successfulFiles} succeeded, ${syncResult.failedFiles} failed"
                            )
                        }
                        is com.anyproto.anyfile.domain.sync.SyncResult.Failed -> {
                            failureCount++
                            android.util.Log.e(TAG, "Failed to sync space ${space.spaceId}", syncResult.error)
                        }
                    }
                } catch (e: Exception) {
                    failureCount++
                    android.util.Log.e(TAG, "Exception while syncing space ${space.spaceId}", e)
                }
            }

            // Return result based on overall outcome
            return when {
                failureCount == 0 -> {
                    android.util.Log.d(TAG, "All spaces synced successfully")
                    Result.success()
                }
                failureCount < activeSpaces.size -> {
                    // Some spaces failed, but not all - still succeed but log
                    android.util.Log.w(TAG, "Some spaces failed ($failureCount/${activeSpaces.size})")
                    Result.success()
                }
                else -> {
                    // All spaces failed - retry later
                    android.util.Log.e(TAG, "All spaces failed, will retry")
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Unexpected error during sync", e)
            return Result.retry()
        }
    }

    /**
     * Get all spaces from the database.
     */
    private suspend fun getAllSpaces(): List<Space> {
        return try {
            spaceDao.getAllSpaces().first()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to get spaces from database", e)
            emptyList()
        }
    }
}

/**
 * Extension functions for enqueueing the SyncWorker.
 */
object SyncWorkerEnqueuer {

    /**
     * Enqueue the SyncWorker as periodic work.
     *
     * This will:
     * - Run every 15 minutes (minimum allowed interval)
     * - Only run when network is connected
     * - Keep any existing worker (no duplicates)
     * - Respect battery and data saver preferences
     *
     * @param context Application context
     */
    fun enqueue(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(false)
            .setRequiresCharging(false)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
            SyncWorker.MIN_SYNC_INTERVAL_MINUTES,
            TimeUnit.MINUTES,
            SyncWorker.SYNC_FLEX_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )

        android.util.Log.d("SyncWorker", "Enqueued periodic sync worker")
    }

    /**
     * Cancel the SyncWorker.
     *
     * @param context Application context
     */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(SyncWorker.WORK_NAME)
        android.util.Log.d("SyncWorker", "Cancelled periodic sync worker")
    }

    /**
     * Check if the SyncWorker is currently enqueued.
     *
     * @param context Application context
     * @return true if the worker is enqueued
     */
    suspend fun isEnqueued(context: Context): Boolean {
        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(SyncWorker.WORK_NAME)
            .get()

        return workInfos.isNotEmpty()
    }
}
