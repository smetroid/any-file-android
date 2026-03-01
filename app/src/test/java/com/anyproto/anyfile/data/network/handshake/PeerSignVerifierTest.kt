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
        // Generate key pairs for Alice and Bob
        aliceKeyPair = Libp2pSignature.generateKeyPair()
        bobKeyPair = Libp2pSignature.generateKeyPair()

        // Derive peer IDs (simplified - using base58 strings)
        alicePeerId = PeerId("12D3KooWAlicePeerId1234567890ABCDEFGHIJ", byteArrayOf(), aliceKeyPair.publicKey)
        bobPeerId = PeerId("12D3KooWBobPeerId1234567890ABCDEFGHIJK", byteArrayOf(), bobKeyPair.publicKey)
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

        assertThat(payload.identity).isEqualTo(aliceKeyPair.publicKey)
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

        // Verify the signature
        val message = (alicePeerId.base58 + bobPeerId.base58).toByteArray()
        val verified = Libp2pSignature.verify(payload.identity, message, payload.sign)

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

        val charliePeerId = PeerId("12D3KooWCharliePeerId123456", byteArrayOf(), byteArrayOf())

        // Alice creates credentials for Bob
        val aliceCred = aliceChecker.makeCredentials(bobPeerId)

        // Charlie tries to use Alice's credentials (should fail because signature is for Bob)
        val exception = org.junit.Assert.assertThrows(HandshakeProtocolException::class.java) {
            bobChecker.checkCredential(charliePeerId, aliceCred)
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
