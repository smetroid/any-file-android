package com.anyproto.anyfile.data.network.handshake

import android.util.Log
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
    private val compatibleVersions: List<UInt> = listOf(7u, 8u, 9u),
    private val clientVersion: String = NoVerifyChecker.DEFAULT_CLIENT_VERSION
) : CredentialChecker {

    override fun makeCredentials(remotePeerId: PeerId): HandshakeCredentials {
        // Create signature over (localPeerId + remotePeerId)
        // IMPORTANT: Use UTF-8 encoding explicitly to match Go implementation
        val message = (localPeerId.base58 + remotePeerId.base58).toByteArray(Charsets.UTF_8)
        val signature = Libp2pSignature.sign(localKeyPair.privateKey, message)

        // Create payload with any-sync crypto.Key proto-encoded public key and signature.
        // Go coordinator calls crypto.UnmarshalEd25519PublicKeyProto(payload.Identity),
        // which expects Key{Type: ED25519_PUBLIC=0, Data: rawKey} wire format:
        //   0x12 (field 2, length-delimited) 0x20 (32 bytes) [32 bytes] = 34 bytes.
        // ED25519_PUBLIC=0 is the proto3 default, so the Type field is omitted.
        val payload = PayloadSignedPeerIds(
            identity = localKeyPair.encodePublicKeyProto(),  // any-sync crypto.Key proto (34 bytes)
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

        // Decode any-sync crypto.Key proto to get raw 32-byte Ed25519 public key.
        // Go sends Key{Type: ED25519_PUBLIC=0, Data: rawKey} → 0x12 0x20 [32 bytes] = 34 bytes.
        Log.d("PeerSignVerifier", "Credential identity length: ${payload.identity.size}")
        val rawPubKey = decodeAnySyncCryptoKeyProto(payload.identity)

        // Verify signature over (remotePeerId + localPeerId).
        // IMPORTANT: Use remotePeerId directly (from TLS), NOT derived from the credential key.
        //
        // In any-sync, nodes have separate PeerKey (peer ID) and SignKey (for signing).
        // account.PeerId = PeerKey.GetPublic().PeerId() — this is the remotePeerId from TLS.
        // credential.identity = SignKey.GetPublic().Marshall() — a different key.
        //
        // Go's MakeCredentials signs: SignKey.Sign(account.PeerId + remotePeerId)
        // Go's CheckCredential verifies: pubKey.Verify(remotePeerId + account.PeerId, sign)
        //   where remotePeerId is the TLS-established peer ID, NOT derived from the credential.
        val message = (remotePeerId.base58 + localPeerId.base58).toByteArray(Charsets.UTF_8)
        val verified = Libp2pSignature.verify(
            publicKey = rawPubKey,
            message = message,
            signature = payload.sign
        )

        if (!verified) {
            throw HandshakeProtocolException(
                "Signature verification failed for peer ${remotePeerId.base58}"
            )
        }

        // Return raw 32-byte identity (remote peer's public key) and version info
        return HandshakeResult(
            identity = rawPubKey,
            protoVersion = cred.version,
            clientVersion = cred.clientVersion
        )
    }

    /**
     * Decode any-sync crypto.Key proto to extract the raw 32-byte Ed25519 public key.
     *
     * Go's crypto.Key{Type: ED25519_PUBLIC=0, Data: rawKey} encodes to:
     *   0x12 (field 2, length-delimited) 0x20 (32 bytes) [32-byte key] = 34 bytes total.
     * The Type field (ED25519_PUBLIC=0) is the proto3 default and is omitted.
     *
     * @param encoded Proto-encoded Key bytes (34 bytes from Go server)
     * @return Raw 32-byte Ed25519 public key
     */
    private fun decodeAnySyncCryptoKeyProto(encoded: ByteArray): ByteArray {
        if (encoded.size == 34 &&
            encoded[0] == 0x12.toByte() &&
            encoded[1] == 0x20.toByte()
        ) {
            return encoded.copyOfRange(2, 34)
        }
        // Fallback: raw 32-byte key (legacy compatibility)
        if (encoded.size == 32) return encoded
        throw HandshakeProtocolException(
            "Invalid identity encoding: expected 34-byte crypto.Key proto, got ${encoded.size} bytes"
        )
    }

}
