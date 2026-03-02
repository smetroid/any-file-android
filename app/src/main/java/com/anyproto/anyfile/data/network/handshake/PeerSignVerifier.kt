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
    private val compatibleVersions: List<UInt> = listOf(8u, 9u),
    private val clientVersion: String = NoVerifyChecker.DEFAULT_CLIENT_VERSION
) : CredentialChecker {

    override fun makeCredentials(remotePeerId: PeerId): HandshakeCredentials {
        // Create signature over (localPeerId + remotePeerId)
        // IMPORTANT: Use UTF-8 encoding explicitly to match Go implementation
        val message = (localPeerId.base58 + remotePeerId.base58).toByteArray(Charsets.UTF_8)
        val signature = Libp2pSignature.sign(localKeyPair.privateKey, message)

        // Create payload with raw Ed25519 public key and signature
        // IMPORTANT: identity is raw 32-byte Ed25519 public key (NOT protobuf-encoded)
        // This matches the any-sync wire format for SignedPeerIds credentials
        val payload = PayloadSignedPeerIds(
            identity = localKeyPair.publicKey,  // Raw 32-byte Ed25519 public key
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

        // IMPORTANT: Derive remote peer ID from the credentials' identity field
        // The identity field contains the raw 32-byte Ed25519 public key of the remote peer
        // We derive the peer ID from this public key, NOT from the TLS certificate
        Log.d("PeerSignVerifier", "Credential identity length: ${payload.identity.size}")
        val remotePeerIdFromCred = derivePeerIdFromPublicKey(payload.identity)
        Log.d("PeerSignVerifier", "Derived remote peer ID from credentials: ${remotePeerIdFromCred.base58}")

        // Verify signature over (remotePeerId + localPeerId)
        // IMPORTANT: Use the peer ID derived from credentials, not from TLS
        // IMPORTANT: Use UTF-8 encoding explicitly to match Go implementation
        val message = (remotePeerIdFromCred.base58 + localPeerId.base58).toByteArray(Charsets.UTF_8)
        val verified = Libp2pSignature.verify(
            publicKey = payload.identity,
            message = message,
            signature = payload.sign
        )

        if (!verified) {
            throw HandshakeProtocolException(
                "Signature verification failed for peer ${remotePeerIdFromCred.base58}"
            )
        }

        // Return identity (remote peer's public key) and version info
        return HandshakeResult(
            identity = payload.identity,
            protoVersion = cred.version,
            clientVersion = cred.clientVersion
        )
    }

    /**
     * Derive a libp2p peer ID from a raw Ed25519 public key.
     *
     * Uses identity multihash format: <code(0x00)><length(36)><protobuf-encoded-key>
     *
     * The protobuf encoding for Ed25519 keys is: <0x08><0x01><0x12><0x20><32-byte-key>
     *
     * @param publicKey Raw 32-byte Ed25519 public key
     * @return Derived peer ID
     */
    private fun derivePeerIdFromPublicKey(publicKey: ByteArray): PeerId {
        // Step 1: Create protobuf encoding of Ed25519 key
        // Format: 08 01 12 20 <32-byte-key>
        // - 0x08: field 1, wire type 0 (varint) for key type
        // - 0x01: Ed25519 key type value
        // - 0x12: field 2, wire type 2 (length-delimited) for key data
        // - 0x20: varint encoded length (32 bytes)
        val protobufBytes = ByteArray(4 + publicKey.size)
        protobufBytes[0] = 0x08.toByte()
        protobufBytes[1] = 0x01.toByte()
        protobufBytes[2] = 0x12.toByte()
        protobufBytes[3] = 0x20.toByte()
        System.arraycopy(publicKey, 0, protobufBytes, 4, publicKey.size)

        // Step 2: Create identity multihash
        // Format: <code(0x00)><length(36)><protobuf>
        val multihash = ByteArray(2 + protobufBytes.size)
        multihash[0] = 0x00  // Identity multihash code (NOT SHA-256!)
        multihash[1] = protobufBytes.size.toByte()  // Length of protobuf (36)
        System.arraycopy(protobufBytes, 0, multihash, 2, protobufBytes.size)

        // Step 3: Encode multihash to base58 (libp2p alphabet)
        val base58 = encodeBase58Libp2p(multihash)

        return PeerId(base58, multihash, publicKey)
    }

    /**
     * Encode byte array to base58 (libp2p alphabet).
     */
    private fun encodeBase58Libp2p(input: ByteArray): String {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var num = java.math.BigInteger(1, input)
        val base = java.math.BigInteger.valueOf(58)
        val output = StringBuilder()

        while (num.compareTo(java.math.BigInteger.ZERO) > 0) {
            val rem = num.mod(base).toInt()
            output.append(alphabet[rem])
            num = num.divide(base)
        }

        // Handle leading zeros
        for (b in input) {
            if (b.toInt() == 0) {
                output.append('1') // '1' is alphabet[0]
            } else {
                break
            }
        }

        return output.reverse().toString()
    }
}
