package com.anyproto.anyfile.data.network.handshake

/**
 * Credentials type for the any-sync handshake protocol.
 *
 * @property SKIP_VERIFY Skip identity verification (for P2P cases)
 * @property SIGNED_PEER_IDS Ed25519 signature + public key verification
 */
enum class CredentialsType {
    SKIP_VERIFY,
    SIGNED_PEER_IDS;

    companion object {
        fun fromProto(type: com.anyproto.anyfile.protos.CredentialsType): CredentialsType {
            return when (type) {
                com.anyproto.anyfile.protos.CredentialsType.SkipVerify -> SKIP_VERIFY
                com.anyproto.anyfile.protos.CredentialsType.SignedPeerIds -> SIGNED_PEER_IDS
                else -> throw IllegalArgumentException("Unknown CredentialsType: $type")
            }
        }
    }

    fun toProto(): com.anyproto.anyfile.protos.CredentialsType {
        return when (this) {
            SKIP_VERIFY -> com.anyproto.anyfile.protos.CredentialsType.SkipVerify
            SIGNED_PEER_IDS -> com.anyproto.anyfile.protos.CredentialsType.SignedPeerIds
        }
    }
}

/**
 * Error types for the any-sync handshake protocol.
 *
 * @property NULL No error
 * @property UNEXPECTED Unexpected error occurred
 * @property INVALID_CREDENTIALS Invalid credentials provided
 * @property UNEXPECTED_PAYLOAD Unexpected payload received
 * @property SKIP_VERIFY_NOT_ALLOWED SkipVerify mode not allowed
 * @property DEADLINE_EXCEEDED Handshake timed out
 * @property INCOMPATIBLE_VERSION Incompatible protocol version
 * @property INCOMPATIBLE_PROTO Incompatible protocol selected
 */
enum class HandshakeError {
    NULL,
    UNEXPECTED,
    INVALID_CREDENTIALS,
    UNEXPECTED_PAYLOAD,
    SKIP_VERIFY_NOT_ALLOWED,
    DEADLINE_EXCEEDED,
    INCOMPATIBLE_VERSION,
    INCOMPATIBLE_PROTO;

    companion object {
        fun fromProto(error: com.anyproto.anyfile.protos.Error): HandshakeError {
            return when (error) {
                com.anyproto.anyfile.protos.Error.Null -> NULL
                com.anyproto.anyfile.protos.Error.Unexpected -> UNEXPECTED
                com.anyproto.anyfile.protos.Error.InvalidCredentials -> INVALID_CREDENTIALS
                com.anyproto.anyfile.protos.Error.UnexpectedPayload -> UNEXPECTED_PAYLOAD
                com.anyproto.anyfile.protos.Error.SkipVerifyNotAllowed -> SKIP_VERIFY_NOT_ALLOWED
                com.anyproto.anyfile.protos.Error.DeadlineExceeded -> DEADLINE_EXCEEDED
                com.anyproto.anyfile.protos.Error.IncompatibleVersion -> INCOMPATIBLE_VERSION
                com.anyproto.anyfile.protos.Error.IncompatibleProto -> INCOMPATIBLE_PROTO
                else -> throw IllegalArgumentException("Unknown Error: $error")
            }
        }
    }

    fun toProto(): com.anyproto.anyfile.protos.Error {
        return when (this) {
            NULL -> com.anyproto.anyfile.protos.Error.Null
            UNEXPECTED -> com.anyproto.anyfile.protos.Error.Unexpected
            INVALID_CREDENTIALS -> com.anyproto.anyfile.protos.Error.InvalidCredentials
            UNEXPECTED_PAYLOAD -> com.anyproto.anyfile.protos.Error.UnexpectedPayload
            SKIP_VERIFY_NOT_ALLOWED -> com.anyproto.anyfile.protos.Error.SkipVerifyNotAllowed
            DEADLINE_EXCEEDED -> com.anyproto.anyfile.protos.Error.DeadlineExceeded
            INCOMPATIBLE_VERSION -> com.anyproto.anyfile.protos.Error.IncompatibleVersion
            INCOMPATIBLE_PROTO -> com.anyproto.anyfile.protos.Error.IncompatibleProto
        }
    }
}

/**
 * Result of a successful handshake.
 *
 * @property identity Remote peer's Ed25519 public key (null for SkipVerify)
 * @property protoVersion Protocol version (e.g., 8)
 * @property clientVersion Client version string (e.g., "any-file/v0.1.0")
 */
data class HandshakeResult(
    val identity: ByteArray?,
    val protoVersion: UInt,
    val clientVersion: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HandshakeResult

        if (identity != null) {
            if (other.identity == null) return false
            if (!identity.contentEquals(other.identity)) return false
        } else if (other.identity != null) return false
        if (protoVersion != other.protoVersion) return false
        if (clientVersion != other.clientVersion) return false

        return true
    }

    override fun hashCode(): Int {
        var result = identity?.contentHashCode() ?: 0
        result = 31 * result + protoVersion.hashCode()
        result = 31 * result + clientVersion.hashCode()
        return result
    }
}

/**
 * Credentials message for the handshake protocol.
 *
 * @property type Type of credentials (SkipVerify or SignedPeerIds)
 * @property payload Payload bytes (PayloadSignedPeerIds for SignedPeerIds, null for SkipVerify)
 * @property version Protocol version (e.g., 8)
 * @property clientVersion Client version string (e.g., "any-file/v0.1.0")
 */
data class HandshakeCredentials(
    val type: CredentialsType,
    val payload: ByteArray?,
    val version: UInt,
    val clientVersion: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HandshakeCredentials

        if (type != other.type) return false
        if (payload != null) {
            if (other.payload == null) return false
            if (!payload.contentEquals(other.payload)) return false
        } else if (other.payload != null) return false
        if (version != other.version) return false
        if (clientVersion != other.clientVersion) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + (payload?.contentHashCode() ?: 0)
        result = 31 * result + version.hashCode()
        result = 31 * result + clientVersion.hashCode()
        return result
    }

    fun toProto(): com.anyproto.anyfile.protos.Credentials {
        val builder = com.anyproto.anyfile.protos.Credentials.newBuilder()
            .setType(type.toProto())
            .setVersion(version.toInt())
            .setClientVersion(clientVersion)

        if (payload != null) {
            builder.setPayload(com.google.protobuf.ByteString.copyFrom(payload))
        }

        return builder.build()
    }

    companion object {
        fun fromProto(proto: com.anyproto.anyfile.protos.Credentials): HandshakeCredentials {
            return HandshakeCredentials(
                type = CredentialsType.fromProto(proto.type),
                payload = if (proto.payload.isEmpty()) null else proto.payload.toByteArray(),
                version = proto.version.toUInt(),
                clientVersion = proto.clientVersion
            )
        }
    }
}

/**
 * Payload for SignedPeerIds credentials.
 *
 * Contains the Ed25519 public key and signature proving ownership of the peer ID.
 *
 * @property identity Ed25519 public key (X.509 encoded, 44 bytes)
 * @property sign Ed25519 signature of (localPeerId + remotePeerId), 64 bytes
 */
data class PayloadSignedPeerIds(
    val identity: ByteArray,
    val sign: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PayloadSignedPeerIds

        if (!identity.contentEquals(other.identity)) return false
        if (!sign.contentEquals(other.sign)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = identity.contentHashCode()
        result = 31 * result + sign.contentHashCode()
        return result
    }

    fun toProto(): com.anyproto.anyfile.protos.PayloadSignedPeerIds {
        return com.anyproto.anyfile.protos.PayloadSignedPeerIds.newBuilder()
            .setIdentity(com.google.protobuf.ByteString.copyFrom(identity))
            .setSign(com.google.protobuf.ByteString.copyFrom(sign))
            .build()
    }

    companion object {
        fun fromProto(proto: com.anyproto.anyfile.protos.PayloadSignedPeerIds): PayloadSignedPeerIds {
            return PayloadSignedPeerIds(
                identity = proto.identity.toByteArray(),
                sign = proto.sign.toByteArray()
            )
        }
    }
}

/**
 * Acknowledgment message for the handshake protocol.
 *
 * @property error Error code (NULL if no error)
 */
data class Ack(
    val error: HandshakeError = HandshakeError.NULL
) {
    fun toProto(): com.anyproto.anyfile.protos.Ack {
        return com.anyproto.anyfile.protos.Ack.newBuilder()
            .setError(error.toProto())
            .build()
    }

    companion object {
        fun fromProto(proto: com.anyproto.anyfile.protos.Ack): Ack {
            return Ack(
                error = HandshakeError.fromProto(proto.error)
            )
        }
    }
}

/**
 * Protocol negotiation message for the handshake protocol.
 *
 * @property proto Protocol type (DRPC)
 * @property encodings Supported encodings (None, Snappy)
 */
data class Proto(
    val proto: ProtoType = ProtoType.DRPC,
    val encodings: List<Encoding> = emptyList()
) {
    fun toProto(): com.anyproto.anyfile.protos.Proto {
        val builder = com.anyproto.anyfile.protos.Proto.newBuilder()
            .setProto(proto.toProto())

        encodings.forEach { encoding ->
            builder.addEncodings(encoding.toProto())
        }

        return builder.build()
    }

    companion object {
        fun fromProto(proto: com.anyproto.anyfile.protos.Proto): Proto {
            return Proto(
                proto = ProtoType.fromProto(proto.proto),
                encodings = proto.encodingsList.map { Encoding.fromProto(it) }
            )
        }
    }
}

/**
 * Protocol type for protocol negotiation.
 */
enum class ProtoType {
    DRPC;

    companion object {
        fun fromProto(type: com.anyproto.anyfile.protos.ProtoType): ProtoType {
            return when (type) {
                com.anyproto.anyfile.protos.ProtoType.DRPC -> DRPC
                else -> throw IllegalArgumentException("Unknown ProtoType: $type")
            }
        }
    }

    fun toProto(): com.anyproto.anyfile.protos.ProtoType {
        return when (this) {
            DRPC -> com.anyproto.anyfile.protos.ProtoType.DRPC
        }
    }
}

/**
 * Encoding type for protocol negotiation.
 */
enum class Encoding {
    NONE,
    SNAPPY;

    companion object {
        fun fromProto(encoding: com.anyproto.anyfile.protos.Encoding): Encoding {
            return when (encoding) {
                com.anyproto.anyfile.protos.Encoding.None -> NONE
                com.anyproto.anyfile.protos.Encoding.Snappy -> SNAPPY
                else -> throw IllegalArgumentException("Unknown Encoding: $encoding")
            }
        }
    }

    fun toProto(): com.anyproto.anyfile.protos.Encoding {
        return when (this) {
            NONE -> com.anyproto.anyfile.protos.Encoding.None
            SNAPPY -> com.anyproto.anyfile.protos.Encoding.Snappy
        }
    }
}
