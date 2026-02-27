package com.anyproto.anyfile.di

import android.content.Context
import androidx.room.Room
import com.anyproto.anyfile.data.database.AnyfileDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Database module providing Room database instance.
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
}
