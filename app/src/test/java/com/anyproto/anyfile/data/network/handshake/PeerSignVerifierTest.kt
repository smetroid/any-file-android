package com.anyproto.anyfile.data.network.handshake

import com.anyproto.anyfile.data.network.libp2p.Libp2pKeyPair
import com.anyproto.anyfile.data.network.libp2p.Libp2pSignature
import com.anyproto.anyfile.data.network.libp2p.PeerId
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class PeerSignVerifierTest {

    private lateinit var aliceKeyPair: Libp2pKeyPair
    private lateinit var bobKeyPair: Libp2pKeyPair
    private lateinit var alicePeerId: PeerId
    private lateinit var bobPeerId: PeerId

    @Before
    fun setup() {
        val keyManager = com.anyproto.anyfile.data.network.libp2p.Libp2pKeyManager()

        // Generate key pairs for Alice and Bob
        aliceKeyPair = Libp2pSignature.generateKeyPair()
        bobKeyPair = Libp2pSignature.generateKeyPair()

        // Use REAL derived peer IDs so they are consistent with what
        // PeerSignVerifier.checkCredential() derives from the public key internally.
        alicePeerId = keyManager.derivePeerId(aliceKeyPair.publicKey)
        bobPeerId = keyManager.derivePeerId(bobKeyPair.publicKey)
    }

    @Test
    fun makeCredentials_returnsSignedPeerIdsType() {
        val checker = PeerSignVerifier(aliceKeyPair, alicePeerId)

        val cred = checker.makeCredentials(bobPeerId)

        assertThat(cred.type).isEqualTo(CredentialsType.SIGNED_PEER_IDS)
        assertThat(cred.payload).isNotNull()
    }

    @Test
    fun makeCredentials_includesPublicKey() {
        val checker = PeerSignVerifier(aliceKeyPair, alicePeerId)

        val cred = checker.makeCredentials(bobPeerId)

        val payload = PayloadSignedPeerIds.fromProto(
            com.anyproto.anyfile.protos.PayloadSignedPeerIds.parseFrom(
                com.google.protobuf.ByteString.copyFrom(cred.payload!!)
            )
        )

        // identity is any-sync crypto.Key proto: Key{Type: ED25519_PUBLIC=0, Data: rawKey}
        // wire format: 0x12 (field 2, length-delimited) 0x20 (32 bytes) [32 bytes] = 34 bytes
        assertThat(payload.identity).isEqualTo(aliceKeyPair.encodePublicKeyProto())
    }

    @Test
    fun makeCredentials_generatesValidSignature() {
        val checker = PeerSignVerifier(aliceKeyPair, alicePeerId)

        val cred = checker.makeCredentials(bobPeerId)

        val payload = PayloadSignedPeerIds.fromProto(
            com.anyproto.anyfile.protos.PayloadSignedPeerIds.parseFrom(
                com.google.protobuf.ByteString.copyFrom(cred.payload!!)
            )
        )

        // identity is proto-encoded (34 bytes); extract raw 32-byte key for verification
        val rawKey = payload.identity.copyOfRange(2, 34)  // skip 0x12 0x20 header

        // Verify the signature
        val message = (alicePeerId.base58 + bobPeerId.base58).toByteArray()
        val verified = Libp2pSignature.verify(rawKey, message, payload.sign)

        assertThat(verified).isTrue()
    }

    @Test
    fun checkCredential_withValidCredentials_succeeds() {
        val aliceChecker = PeerSignVerifier(aliceKeyPair, alicePeerId)
        val bobChecker = PeerSignVerifier(bobKeyPair, bobPeerId)

        // Alice creates credentials for Bob
        val aliceCred = aliceChecker.makeCredentials(bobPeerId)

        // Bob verifies Alice's credentials
        val result = bobChecker.checkCredential(alicePeerId, aliceCred)

        assertThat(result.identity).isEqualTo(aliceKeyPair.publicKey)
        assertThat(result.protoVersion).isEqualTo(8u)
        assertThat(result.clientVersion).isEqualTo("any-file/v0.1.0")
    }

    @Test
    fun checkCredential_withInvalidSignature_throwsException() {
        val aliceChecker = PeerSignVerifier(aliceKeyPair, alicePeerId)
        val bobChecker = PeerSignVerifier(bobKeyPair, bobPeerId)

        // Alice creates credentials for Bob
        val aliceCred = aliceChecker.makeCredentials(bobPeerId)

        // Tamper with the signature
        val tamperedCred = HandshakeCredentials(
            type = aliceCred.type,
            payload = aliceCred.payload?.let { originalPayload ->
                val payloadProto = com.anyproto.anyfile.protos.PayloadSignedPeerIds.parseFrom(
                    com.google.protobuf.ByteString.copyFrom(originalPayload)
                )
                val tamperedSignature = payloadProto.sign.toByteArray().also {
                    it[0] = (it[0].toInt() xor 0xFF).toByte()
                }
                PayloadSignedPeerIds(
                    identity = payloadProto.identity.toByteArray(),
                    sign = tamperedSignature
                ).toProto().toByteArray()
            },
            version = aliceCred.version,
            clientVersion = aliceCred.clientVersion
        )

        // Bob tries to verify Alice's tampered credentials
        val exception = org.junit.Assert.assertThrows(HandshakeProtocolException::class.java) {
            bobChecker.checkCredential(alicePeerId, tamperedCred)
        }

        assertThat(exception.message).contains("Signature verification failed")
    }

    @Test
    fun checkCredential_withWrongPeerId_throwsException() {
        val aliceChecker = PeerSignVerifier(aliceKeyPair, alicePeerId)
        val bobChecker = PeerSignVerifier(bobKeyPair, bobPeerId)

        // Alice creates credentials for a different peer (not Bob).
        // checkCredential uses remotePeerId (from TLS) as the first part of the verified message.
        // Alice signed (alicePeerId + charliePeerId), but Bob verifies (alicePeerId + bobPeerId).
        // The message doesn't match, so signature verification fails.
        val charliePeerId = PeerId("12D3KooWCharliePeerId123456", byteArrayOf(), byteArrayOf())
        val aliceCredForCharlie = aliceChecker.makeCredentials(charliePeerId)

        // Bob verifies Alice's credentials (signed for Charlie, not Bob) — should fail
        val exception = org.junit.Assert.assertThrows(HandshakeProtocolException::class.java) {
            bobChecker.checkCredential(alicePeerId, aliceCredForCharlie)
        }

        assertThat(exception.message).contains("Signature verification failed")
    }

    @Test
    fun checkCredential_withSkipVerifyType_throwsException() {
        val checker = PeerSignVerifier(aliceKeyPair, alicePeerId)

        val cred = HandshakeCredentials(
            type = CredentialsType.SKIP_VERIFY,
            payload = null,
            version = 8u,
            clientVersion = "test"
        )

        val exception = org.junit.Assert.assertThrows(HandshakeProtocolException::class.java) {
            checker.checkCredential(bobPeerId, cred)
        }

        assertThat(exception.message).contains("Expected SignedPeerIds credentials")
    }

    @Test
    fun checkCredential_withIncompatibleVersion_throwsException() {
        val checker = PeerSignVerifier(
            aliceKeyPair,
            alicePeerId,
            compatibleVersions = listOf(8u)
        )

        val cred = HandshakeCredentials(
            type = CredentialsType.SIGNED_PEER_IDS,
            payload = PayloadSignedPeerIds(aliceKeyPair.publicKey, ByteArray(64)).toProto().toByteArray(),
            version = 9u,
            clientVersion = "test"
        )

        val exception = org.junit.Assert.assertThrows(HandshakeProtocolException::class.java) {
            checker.checkCredential(bobPeerId, cred)
        }

        assertThat(exception.message).contains("Incompatible protocol version")
    }

    @Test
    fun roundTrip_aliceAndBob_successfullyExchangeCredentials() {
        val aliceChecker = PeerSignVerifier(aliceKeyPair, alicePeerId)
        val bobChecker = PeerSignVerifier(bobKeyPair, bobPeerId)

        // Alice creates credentials for Bob
        val aliceCred = aliceChecker.makeCredentials(bobPeerId)
        val aliceResult = bobChecker.checkCredential(alicePeerId, aliceCred)

        // Bob creates credentials for Alice
        val bobCred = bobChecker.makeCredentials(alicePeerId)
        val bobResult = aliceChecker.checkCredential(bobPeerId, bobCred)

        assertThat(aliceResult.identity).isEqualTo(aliceKeyPair.publicKey)
        assertThat(bobResult.identity).isEqualTo(bobKeyPair.publicKey)
    }

    @Test
    fun makeCredentials_withCustomVersion_usesCustomVersion() {
        val protoVersion = 10u
        val checker = PeerSignVerifier(
            aliceKeyPair,
            alicePeerId,
            protoVersion = protoVersion
        )

        val cred = checker.makeCredentials(bobPeerId)

        assertThat(cred.version).isEqualTo(protoVersion)
    }

    /**
     * TDD RED: makeCredentials must encode identity as any-sync crypto.Key proto.
     *
     * Go coordinator calls crypto.UnmarshalEd25519PublicKeyProto(payload.Identity).
     * This expects Key{Type: ED25519_PUBLIC=0, Data: rawKey} wire format:
     *   0x12 (field 2, length-delimited) 0x20 (32 bytes) [32 bytes] = 34 bytes.
     * Sending raw 32 bytes causes INVALID_CREDENTIALS on the Go side.
     */
    @Test
    fun makeCredentials_identity_isAnySyncCryptoKeyProtoEncoded() {
        val checker = PeerSignVerifier(aliceKeyPair, alicePeerId)

        val cred = checker.makeCredentials(bobPeerId)

        val payload = PayloadSignedPeerIds.fromProto(
            com.anyproto.anyfile.protos.PayloadSignedPeerIds.parseFrom(
                com.google.protobuf.ByteString.copyFrom(cred.payload!!)
            )
        )

        // any-sync crypto.Key{Type: ED25519_PUBLIC=0 (omitted), Data: 32 bytes}
        // wire: 0x12 0x20 [32 bytes] = 34 bytes
        assertThat(payload.identity.size).isEqualTo(34)
        assertThat(payload.identity[0]).isEqualTo(0x12.toByte())
        assertThat(payload.identity[1]).isEqualTo(0x20.toByte())
        assertThat(payload.identity.copyOfRange(2, 34)).isEqualTo(aliceKeyPair.publicKey)
    }

    /**
     * TDD RED: any-sync nodes have separate PeerKey (for peer ID) and SignKey (for signing).
     *
     * The real Go coordinator calls:
     *   sign = SignKey.Sign(account.PeerId + remotePeerId)
     *   identity = SignKey.GetPublic().Marshall()
     *
     * So `identity` key derives to a DIFFERENT peer ID than `account.PeerId`.
     * CheckCredential must use remotePeerId directly (from TLS/argument), NOT derive it from
     * the credential's identity key.
     */
    @Test
    fun checkCredential_withSeparateSigningAndPeerKey_succeeds() {
        val keyManager = com.anyproto.anyfile.data.network.libp2p.Libp2pKeyManager()

        // Simulate coordinator: peerKey for peer ID, signKey for signing
        val coordinatorPeerKeyPair = Libp2pSignature.generateKeyPair()
        val coordinatorSignKeyPair = Libp2pSignature.generateKeyPair()
        val coordinatorPeerId = keyManager.derivePeerId(coordinatorPeerKeyPair.publicKey)

        // Note: PeerSignVerifier uses localKeyPair for signing, localPeerId as identity string
        val coordinatorChecker = PeerSignVerifier(coordinatorSignKeyPair, coordinatorPeerId)
        val bobChecker = PeerSignVerifier(bobKeyPair, bobPeerId)

        // Coordinator makes credentials for Bob
        val coordCred = coordinatorChecker.makeCredentials(bobPeerId)

        // Bob verifies coordinator's credentials using coordinatorPeerId from TLS
        // (not derived from the credential's signing key — which would be different)
        val result = bobChecker.checkCredential(coordinatorPeerId, coordCred)

        assertThat(result.identity).isEqualTo(coordinatorSignKeyPair.publicKey)
        assertThat(result.protoVersion).isEqualTo(8u)
    }

    @Test
    fun makeCredentials_withCustomClientVersion_usesCustomClientVersion() {
        val clientVersion = "any-file/v2.0.0"
        val checker = PeerSignVerifier(
            aliceKeyPair,
            alicePeerId,
            clientVersion = clientVersion
        )

        val cred = checker.makeCredentials(bobPeerId)

        assertThat(cred.clientVersion).isEqualTo(clientVersion)
    }
}
