// app/src/main/java/com/anyproto/anyfile/data/database/entity/SyncedFile.kt
package com.anyproto.anyfile.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.anyproto.anyfile.data.database.model.SyncStatus
import java.util.Date

@Entity
data class SyncedFile(
    @PrimaryKey
    val cid: String,
    val spaceId: String,
    val filePath: String,
    val size: Long,
    val version: Int,
    val syncStatus: SyncStatus,
    val modifiedAt: Date,
    val checksum: String // blake3 hash
)
