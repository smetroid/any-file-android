// app/src/main/java/com/anyproto/anyfile/data/database/dao/SpaceDao.kt
package com.anyproto.anyfile.data.database.dao

import androidx.room.*
import com.anyproto.anyfile.data.database.entity.Space
import kotlinx.coroutines.flow.Flow
import java.util.Date

@Dao
interface SpaceDao {
    @Query("SELECT * FROM space ORDER BY createdAt DESC")
    fun getAllSpaces(): Flow<List<Space>>

    @Query("SELECT * FROM space WHERE spaceId = :spaceId")
    suspend fun getSpaceById(spaceId: String): Space?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpace(space: Space)

    @Update
    suspend fun updateSpace(space: Space): Int

    @Delete
    suspend fun deleteSpace(space: Space): Int

    @Query("UPDATE space SET lastSyncAt = :lastSyncAt WHERE spaceId = :spaceId")
    suspend fun updateLastSyncTime(spaceId: String, lastSyncAt: Date): Int
}
