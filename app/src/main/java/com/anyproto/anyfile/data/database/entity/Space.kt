// app/src/main/java/com/anyproto/anyfile/data/database/entity/Space.kt
package com.anyproto.anyfile.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.anyproto.anyfile.data.database.model.SyncStatus
import java.util.Date

@Entity
data class Space(
    @PrimaryKey
    val spaceId: String,
    val name: String,
    val spaceKey: ByteArray,
    val createdAt: Date,
    val lastSyncAt: Date?,
    val syncStatus: SyncStatus = SyncStatus.IDLE
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Space

        if (spaceId != other.spaceId) return false
        if (name != other.name) return false
        if (!spaceKey.contentEquals(other.spaceKey)) return false
        if (createdAt != other.createdAt) return false
        if (lastSyncAt != other.lastSyncAt) return false
        if (syncStatus != other.syncStatus) return false

        return true
    }

    override fun hashCode(): Int {
        var result = spaceId.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + spaceKey.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + (lastSyncAt?.hashCode() ?: 0)
        result = 31 * result + syncStatus.hashCode()
        return result
    }
}
