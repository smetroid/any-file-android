package com.anyproto.anyfile.data.network.model

import com.anyproto.anyfile.protos.AccountInfoResponse as ProtoAccountInfoResponse
import com.anyproto.anyfile.protos.FileInfo as ProtoFileInfo
import com.anyproto.anyfile.protos.SpaceInfoResponse as ProtoSpaceInfoResponse

/**
 * Wrapper for FileInfo proto message.
 * Represents information about a file in the filenode service.
 */
data class FileInfo(
    val fileId: String,
    val usageBytes: Long,
    val cidsCount: Int,
) {
    companion object {
        fun fromProto(proto: ProtoFileInfo): FileInfo {
            return FileInfo(
                fileId = proto.fileId,
                usageBytes = proto.usageBytes,
                cidsCount = proto.cidsCount,
            )
        }
    }
}

/**
 * Wrapper for SpaceUsageInfo proto message (SpaceInfoResponse).
 * Represents space usage information from the filenode service.
 */
data class SpaceUsageInfo(
    val spaceId: String,
    val limitBytes: Long,
    val totalUsageBytes: Long,
    val cidsCount: Long,
    val filesCount: Long,
    val spaceUsageBytes: Long,
) {
    companion object {
        fun fromProto(proto: ProtoSpaceInfoResponse): SpaceUsageInfo {
            return SpaceUsageInfo(
                spaceId = proto.spaceId,
                limitBytes = proto.limitBytes,
                totalUsageBytes = proto.totalUsageBytes,
                cidsCount = proto.cidsCount,
                filesCount = proto.filesCount,
                spaceUsageBytes = proto.spaceUsageBytes,
            )
        }
    }
}

/**
 * Wrapper for AccountInfo proto message (AccountInfoResponse).
 * Represents account-level information from the filenode service.
 */
data class AccountInfo(
    val limitBytes: Long,
    val totalUsageBytes: Long,
    val totalCidsCount: Long,
    val spaces: List<SpaceUsageInfo>,
    val accountLimitBytes: Long,
) {
    companion object {
        fun fromProto(proto: ProtoAccountInfoResponse): AccountInfo {
            return AccountInfo(
                limitBytes = proto.limitBytes,
                totalUsageBytes = proto.totalUsageBytes,
                totalCidsCount = proto.totalCidsCount,
                spaces = proto.spacesList.map { SpaceUsageInfo.fromProto(it) },
                accountLimitBytes = proto.accountLimitBytes,
            )
        }
    }
}

/**
 * Result class for block push operation.
 */
data class BlockPushResult(
    val success: Boolean,
)

/**
 * Result class for block get operation.
 */
data class BlockGetResult(
    val cid: ByteArray,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BlockGetResult

        if (!cid.contentEquals(other.cid)) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = cid.contentHashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * Result class for files info operation.
 */
data class FilesInfoResult(
    val files: List<FileInfo>,
)

/**
 * Result class for files get operation.
 */
data class FilesGetResult(
    val fileId: String,
)
