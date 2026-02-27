package com.anyproto.anyfile.di

import com.anyproto.anyfile.domain.sync.SyncOrchestrator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Sync module providing sync orchestrator instance.
 */
@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    @Provides
    @Singleton
    fun provideSyncOrchestrator(): SyncOrchestrator {
        return SyncOrchestrator()
    }
}
