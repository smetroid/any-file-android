// app/src/main/java/com/anyproto/anyfile/data/database/entity/Peer.kt
package com.anyproto.anyfile.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity
data class Peer(
    @PrimaryKey
    val peerId: String,
    val addresses: List<String>,
    val types: List<String>,
    val lastSeen: Date
)
