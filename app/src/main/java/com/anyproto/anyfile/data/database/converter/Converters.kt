// app/src/main/java/com/anyproto/anyfile/data/database/converter/Converters.kt
package com.anyproto.anyfile.data.database.converter

import androidx.room.TypeConverter
import com.anyproto.anyfile.data.database.model.SyncStatus
import java.util.Date

class Converters {
    @TypeConverter
    fun fromDate(date: Date?): Long? {
        return date?.time
    }

    @TypeConverter
    fun toDate(timestamp: Long?): Date? {
        return timestamp?.let { Date(it) }
    }

    @TypeConverter
    fun fromStringList(list: List<String>?): String? {
        return list?.joinToString(",")
    }

    @TypeConverter
    fun toStringList(data: String?): List<String>? {
        return data?.split(",")?.filter { it.isNotEmpty() }
    }

    @TypeConverter
    fun fromByteArray(byteArray: ByteArray?): String? {
        return byteArray?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
    }

    @TypeConverter
    fun toByteArray(data: String?): ByteArray? {
        return data?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
    }

    @TypeConverter
    fun fromSyncStatus(status: SyncStatus): String {
        return status.name
    }

    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus {
        return SyncStatus.valueOf(value)
    }
}
