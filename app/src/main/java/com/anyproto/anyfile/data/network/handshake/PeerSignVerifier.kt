package com.anyproto.anyfile.data.network.handshake

import com.anyproto.anyfile.data.network.libp2p.Libp2pKeyPair
import com.anyproto.anyfile.data.network.libp2p.Libp2pSignature
import com.anyproto.anyfile.data.network.libp2p.PeerId

/**
 * Credential checker for Ed25519 signature-based peer identity verification.
 *
 * This implementation verifies peer identities using Ed25519 signatures.
 * The signature covers the concatenation of local and remote peer IDs.
 *
 * @property localKeyPair Local peer's Ed25519 key pair
 * @property localPeerId Local peer's libp2p peer ID
 * @property protoVersion Protocol version (default: 8)
 * @property compatibleVersions List of compatible protocol versions
 * @property clientVersion Client version string (e.g., "any-file/v0.1.0")
 *
 * Based on the Go implementation in any-sync/net/secureservice/credential.go
 */
class PeerSignVerifier(
    private val localKeyPair: Libp2pKeyPair,
    private val localPeerId: PeerId,
    private val protoVersion: UInt = 8u,
    private val compatibleVersions: List<UInt> = listOf(8u, 9u),
    private val clientVersion: String = NoVerifyChecker.DEFAULT_CLIENT_VERSION
) : CredentialChecker {

    override fun makeCredentials(remotePeerId: PeerId): HandshakeCredentials {
        // Create signature over (localPeerId + remotePeerId)
        val message = (localPeerId.base58 + remotePeerId.base58).toByteArray()
        val signature = Libp2pSignature.sign(localKeyPair.privateKey, message)

        // Create payload with public key and signature
        val payload = PayloadSignedPeerIds(
            identity = localKeyPair.publicKey,
            sign = signature
        )

        return HandshakeCredentials(
            type = CredentialsType.SIGNED_PEER_IDS,
            payload = payload.toProto().toByteArray(),
            version = protoVersion,
            clientVersion = clientVersion
        )
    }

    override fun checkCredential(
        remotePeerId: PeerId,
        cred: HandshakeCredentials
    ): HandshakeResult {
        // Verify credentials type
        if (cred.type != CredentialsType.SIGNED_PEER_IDS) {
            throw HandshakeProtocolException(
                "Expected SignedPeerIds credentials, got ${cred.type}"
            )
        }

        // Verify protocol version compatibility
        if (cred.version !in compatibleVersions) {
            throw HandshakeProtocolException(
                "Incompatible protocol version: ${cred.version} (compatible: $compatibleVersions)"
            )
        }

        // Parse payload
        val payload = try {
            val proto = com.anyproto.anyfile.protos.PayloadSignedPeerIds.parseFrom(
                com.google.protobuf.ByteString.copyFrom(cred.payload ?: throw HandshakeProtocolException("Missing payload"))
            )
            PayloadSignedPeerIds.fromProto(proto)
        } catch (e: Exception) {
            throw HandshakeProtocolException("Failed to parse PayloadSignedPeerIds", e)
        }

        // Verify signature over (remotePeerId + localPeerId)
        val message = (remotePeerId.base58 + localPeerId.base58).toByteArray()
        val verified = Libp2pSignature.verify(
            publicKey = payload.identity,
            message = message,
            signature = payload.sign
        )

        if (!verified) {
            throw HandshakeProtocolException(
                "Signature verification failed for peer ${remotePeerId.base58}"
            )
        }

        // Return identity (remote peer's public key) and version info
        return HandshakeResult(
            identity = payload.identity,
            protoVersion = cred.version,
            clientVersion = cred.clientVersion
        )
    }
}
