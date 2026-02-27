package com.anyproto.anyfile.data.network.model

import com.anyproto.anyfile.protos.AccountLimits
import com.anyproto.anyfile.protos.Node
import com.anyproto.anyfile.protos.SpaceLimits as ProtoSpaceLimits
import com.anyproto.anyfile.protos.SpaceReceipt as ProtoSpaceReceipt
import com.anyproto.anyfile.protos.SpaceStatusPayload

/**
 * Wrapper for SpaceReceipt proto message
 */
data class SpaceReceipt(
    val spaceId: String,
    val peerId: String,
    val accountIdentity: ByteArray,
    val networkId: String,
    val validUntil: Long,
) {
    companion object {
        fun fromProto(proto: com.anyproto.anyfile.protos.SpaceReceipt): SpaceReceipt {
            return SpaceReceipt(
                spaceId = proto.spaceId,
                peerId = proto.peerId,
                accountIdentity = proto.accountIdentity.toByteArray(),
                networkId = proto.networkId,
                validUntil = proto.validUntil,
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SpaceReceipt

        if (spaceId != other.spaceId) return false
        if (peerId != other.peerId) return false
        if (!accountIdentity.contentEquals(other.accountIdentity)) return false
        if (networkId != other.networkId) return false
        if (validUntil != other.validUntil) return false

        return true
    }

    override fun hashCode(): Int {
        var result = spaceId.hashCode()
        result = 31 * result + peerId.hashCode()
        result = 31 * result + accountIdentity.contentHashCode()
        result = 31 * result + networkId.hashCode()
        result = 31 * result + validUntil.hashCode()
        return result
    }
}

/**
 * Wrapper for SpaceStatusPayload proto message
 */
data class SpaceStatusInfo(
    val status: SpaceStatus,
    val deletionTimestamp: Long,
    val permissions: SpacePermissions,
    val limits: SpaceLimits?,
    val isShared: Boolean,
) {
    companion object {
        fun fromProto(proto: SpaceStatusPayload): SpaceStatusInfo {
            return SpaceStatusInfo(
                status = SpaceStatus.fromProto(proto.status),
                deletionTimestamp = proto.deletionTimestamp,
                permissions = SpacePermissions.fromProto(proto.permissions),
                limits = proto.limits?.let { SpaceLimits.fromProto(it) },
                isShared = proto.isShared,
            )
        }
    }
}

/**
 * Enum for SpaceStatus from proto
 */
enum class SpaceStatus {
    CREATED,
    PENDING_DELETION,
    DELETION_STARTED,
    DELETED,
    NOT_EXISTS;

    companion object {
        fun fromProto(proto: com.anyproto.anyfile.protos.SpaceStatus): SpaceStatus {
            return when (proto) {
                com.anyproto.anyfile.protos.SpaceStatus.SpaceStatusCreated -> CREATED
                com.anyproto.anyfile.protos.SpaceStatus.SpaceStatusPendingDeletion -> PENDING_DELETION
                com.anyproto.anyfile.protos.SpaceStatus.SpaceStatusDeletionStarted -> DELETION_STARTED
                com.anyproto.anyfile.protos.SpaceStatus.SpaceStatusDeleted -> DELETED
                com.anyproto.anyfile.protos.SpaceStatus.SpaceStatusNotExists -> NOT_EXISTS
                com.anyproto.anyfile.protos.SpaceStatus.UNRECOGNIZED -> NOT_EXISTS
            }
        }
    }
}

/**
 * Enum for SpacePermissions from proto
 */
enum class SpacePermissions {
    UNKNOWN,
    OWNER;

    companion object {
        fun fromProto(proto: com.anyproto.anyfile.protos.SpacePermissions): SpacePermissions {
            return when (proto) {
                com.anyproto.anyfile.protos.SpacePermissions.SpacePermissionsUnknown -> UNKNOWN
                com.anyproto.anyfile.protos.SpacePermissions.SpacePermissionsOwner -> OWNER
                com.anyproto.anyfile.protos.SpacePermissions.UNRECOGNIZED -> UNKNOWN
            }
        }
    }
}

/**
 * Wrapper for SpaceLimits from proto
 */
data class SpaceLimits(
    val readMembers: Int,
    val writeMembers: Int,
) {
    companion object {
        fun fromProto(proto: ProtoSpaceLimits): SpaceLimits {
            return SpaceLimits(
                readMembers = proto.readMembers,
                writeMembers = proto.writeMembers,
            )
        }
    }
}

/**
 * Network configuration containing coordinator nodes
 */
data class NetworkConfiguration(
    val configurationId: String,
    val networkId: String,
    val nodes: List<NodeInfo>,
    val creationTimeUnix: Long,
) {
    companion object {
        fun fromProto(proto: com.anyproto.anyfile.protos.NetworkConfigurationResponse): NetworkConfiguration {
            return NetworkConfiguration(
                configurationId = proto.configurationId,
                networkId = proto.networkId,
                nodes = proto.nodesList.map { NodeInfo.fromProto(it) },
                creationTimeUnix = proto.creationTimeUnix,
            )
        }
    }
}

/**
 * Information about a node in the network
 */
data class NodeInfo(
    val peerId: String,
    val addresses: List<String>,
    val types: List<NodeType>,
) {
    companion object {
        fun fromProto(proto: Node): NodeInfo {
            return NodeInfo(
                peerId = proto.peerId,
                addresses = proto.addressesList.toList(),
                types = proto.typesList.map { NodeType.fromProto(it) },
            )
        }
    }
}

/**
 * Enum for NodeType from proto
 */
enum class NodeType {
    TREE_API,
    FILE_API,
    COORDINATOR_API,
    CONSENSUS_API,
    NAMING_NODE_API,
    PAYMENT_PROCESSING_API;

    companion object {
        fun fromProto(proto: com.anyproto.anyfile.protos.NodeType): NodeType {
            return when (proto) {
                com.anyproto.anyfile.protos.NodeType.TreeAPI -> TREE_API
                com.anyproto.anyfile.protos.NodeType.FileAPI -> FILE_API
                com.anyproto.anyfile.protos.NodeType.CoordinatorAPI -> COORDINATOR_API
                com.anyproto.anyfile.protos.NodeType.ConsensusAPI -> CONSENSUS_API
                com.anyproto.anyfile.protos.NodeType.NamingNodeAPI -> NAMING_NODE_API
                com.anyproto.anyfile.protos.NodeType.PaymentProcessingAPI -> PAYMENT_PROCESSING_API
                com.anyproto.anyfile.protos.NodeType.UNRECOGNIZED -> COORDINATOR_API
            }
        }
    }
}

/**
 * Account-level limits
 */
data class AccountLimitInfo(
    val sharedSpacesLimit: Int,
) {
    companion object {
        fun fromProto(proto: AccountLimits): AccountLimitInfo {
            return AccountLimitInfo(
                sharedSpacesLimit = proto.sharedSpacesLimit,
            )
        }
    }
}

/**
 * Result of space sign operation
 */
data class SpaceSignResult(
    val receipt: SpaceReceipt,
    val signature: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SpaceSignResult

        if (receipt != other.receipt) return false
        if (!signature.contentEquals(other.signature)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = receipt.hashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}

/**
 * Information about a space
 */
data class SpaceInfo(
    val spaceId: String,
    val status: SpaceStatusInfo,
) {
    companion object {
        fun fromProto(spaceId: String, proto: SpaceStatusPayload): SpaceInfo {
            return SpaceInfo(
                spaceId = spaceId,
                status = SpaceStatusInfo.fromProto(proto),
            )
        }
    }
}
