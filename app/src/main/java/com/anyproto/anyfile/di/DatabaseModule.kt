package com.anyproto.anyfile.di

import android.content.Context
import androidx.room.Room
import com.anyproto.anyfile.data.database.AnyfileDatabase
import com.anyproto.anyfile.data.database.dao.SpaceDao
import com.anyproto.anyfile.data.database.dao.SyncedFileDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Database module providing Room database instance and DAOs.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AnyfileDatabase {
        return Room.databaseBuilder(
            context,
            AnyfileDatabase::class.java,
            "anyfile.db"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideSpaceDao(database: AnyfileDatabase): SpaceDao {
        return database.spaceDao()
    }

    @Provides
    @Singleton
    fun provideSyncedFileDao(database: AnyfileDatabase): SyncedFileDao {
        return database.syncedFileDao()
    }
}
