// app/src/main/java/com/anyproto/anyfile/data/database/AnyfileDatabase.kt
package com.anyproto.anyfile.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.anyproto.anyfile.data.database.dao.SpaceDao
import com.anyproto.anyfile.data.database.dao.SyncedFileDao
import com.anyproto.anyfile.data.database.entity.Peer
import com.anyproto.anyfile.data.database.entity.Space
import com.anyproto.anyfile.data.database.entity.SyncedFile
import com.anyproto.anyfile.data.database.converter.Converters

@Database(
    entities = [Space::class, SyncedFile::class, Peer::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AnyfileDatabase : RoomDatabase() {
    abstract fun spaceDao(): SpaceDao
    abstract fun syncedFileDao(): SyncedFileDao
}
