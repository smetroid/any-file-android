package com.anyproto.anyfile.di

import android.content.Context
import com.anyproto.anyfile.data.database.dao.SpaceDao
import com.anyproto.anyfile.data.database.dao.SyncedFileDao
import com.anyproto.anyfile.data.network.FilenodeClient
import com.anyproto.anyfile.domain.sync.ConflictResolver
import com.anyproto.anyfile.domain.sync.SyncOrchestrator
import com.anyproto.anyfile.domain.watch.FileWatcherManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Sync module providing sync orchestrator and related dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    @Provides
    @Singleton
    fun provideConflictResolver(): ConflictResolver {
        return ConflictResolver()
    }

    @Provides
    @Singleton
    fun provideSyncOrchestrator(
        @ApplicationContext context: Context,
        filenodeClient: FilenodeClient,
        spaceDao: SpaceDao,
        syncedFileDao: SyncedFileDao,
        conflictResolver: ConflictResolver
    ): SyncOrchestrator {
        return SyncOrchestrator(
            context = context,
            filenodeClient = filenodeClient,
            spaceDao = spaceDao,
            syncedFileDao = syncedFileDao,
            conflictResolver = conflictResolver
        )
    }

    @Provides
    @Singleton
    fun provideFileWatcherManager(
        syncOrchestrator: SyncOrchestrator
    ): FileWatcherManager {
        return FileWatcherManager(syncOrchestrator)
    }
}
