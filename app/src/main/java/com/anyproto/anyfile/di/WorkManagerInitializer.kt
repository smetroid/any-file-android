package com.anyproto.anyfile.di

import android.content.Context
import androidx.startup.Initializer
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * WorkManager initializer placeholder.
 * WorkManager configuration will be added in Task 10 when SyncWorker is implemented.
 */
class WorkManagerInitializer : Initializer<Unit> {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkManagerEntryPoint {
        // TODO: Add dependencies when needed for SyncWorker
    }

    override fun create(context: Context) {
        // WorkManager will be configured in Task 10
        // This is a placeholder to satisfy the manifest initialization setup
    }

    override fun dependencies(): List<Class<out Initializer<*>>> {
        return emptyList()
    }
}
