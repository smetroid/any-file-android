// app/src/main/java/com/anyproto/anyfile/data/database/dao/SyncedFileDao.kt
package com.anyproto.anyfile.data.database.dao

import androidx.room.*
import com.anyproto.anyfile.data.database.entity.SyncedFile
import com.anyproto.anyfile.data.database.model.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncedFileDao {
    @Query("SELECT * FROM syncedfile WHERE spaceId = :spaceId ORDER BY filePath")
    fun getFilesBySpace(spaceId: String): Flow<List<SyncedFile>>

    @Query("SELECT * FROM syncedfile WHERE cid = :cid")
    suspend fun getFileByCid(cid: String): SyncedFile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFile(file: SyncedFile)

    @Update
    suspend fun updateFile(file: SyncedFile): Int

    @Query("UPDATE syncedfile SET syncStatus = :status WHERE cid = :cid")
    suspend fun updateSyncStatus(cid: String, status: SyncStatus): Int
}
