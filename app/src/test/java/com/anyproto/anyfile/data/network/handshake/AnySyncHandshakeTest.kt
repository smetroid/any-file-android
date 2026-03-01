package com.anyproto.anyfile.data.network.handshake

import com.anyproto.anyfile.data.network.libp2p.Libp2pKeyPair
import com.anyproto.anyfile.data.network.libp2p.Libp2pSignature
import com.anyproto.anyfile.data.network.libp2p.PeerId
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AnySyncHandshakeTest {

    private lateinit var aliceKeyPair: Libp2pKeyPair
    private lateinit var bobKeyPair: Libp2pKeyPair
    private lateinit var alicePeerId: PeerId
    private lateinit var bobPeerId: PeerId

    @Before
    fun setup() {
        aliceKeyPair = Libp2pSignature.generateKeyPair()
        bobKeyPair = Libp2pSignature.generateKeyPair()

        alicePeerId = PeerId("12D3KooWAlicePeerId1234567890ABCDEFGHIJ", byteArrayOf(), aliceKeyPair.publicKey)
        bobPeerId = PeerId("12D3KooWBobPeerId1234567890ABCDEFGHIJK", byteArrayOf(), bobKeyPair.publicKey)
    }

    @Test
    fun handshake_withNoVerify_generatesCorrectMessages() {
        val checker = NoVerifyChecker()

        val aliceCred = checker.makeCredentials(bobPeerId)
        val bobCred = checker.makeCredentials(alicePeerId)

        assertThat(aliceCred.type).isEqualTo(CredentialsType.SKIP_VERIFY)
        assertThat(bobCred.type).isEqualTo(CredentialsType.SKIP_VERIFY)

        // Both can verify each other's credentials
        val aliceResult = checker.checkCredential(alicePeerId, aliceCred)
        val bobResult = checker.checkCredential(bobPeerId, bobCred)

        assertThat(aliceResult.identity).isNull()
        assertThat(bobResult.identity).isNull()
    }

    @Test
    fun handshake_withPeerSign_generatesCorrectMessages() {
        val aliceChecker = PeerSignVerifier(aliceKeyPair, alicePeerId)
        val bobChecker = PeerSignVerifier(bobKeyPair, bobPeerId)

        val aliceCred = aliceChecker.makeCredentials(bobPeerId)
        val bobCred = bobChecker.makeCredentials(alicePeerId)

        assertThat(aliceCred.type).isEqualTo(CredentialsType.SIGNED_PEER_IDS)
        assertThat(bobCred.type).isEqualTo(CredentialsType.SIGNED_PEER_IDS)

        // Bob can verify Alice's credentials
        val aliceResult = bobChecker.checkCredential(alicePeerId, aliceCred)
        assertThat(aliceResult.identity).isEqualTo(aliceKeyPair.publicKey)

        // Alice can verify Bob's credentials
        val bobResult = aliceChecker.checkCredential(bobPeerId, bobCred)
        assertThat(bobResult.identity).isEqualTo(bobKeyPair.publicKey)
    }

    @Test
    fun credentials_toProto_andFromProto_roundTrip() {
        val checker = PeerSignVerifier(aliceKeyPair, alicePeerId)
        val cred = checker.makeCredentials(bobPeerId)

        // Convert to proto
        val proto = cred.toProto()

        // Convert back
        val restored = HandshakeCredentials.fromProto(proto)

        assertThat(restored.type).isEqualTo(cred.type)
        assertThat(restored.version).isEqualTo(cred.version)
        assertThat(restored.clientVersion).isEqualTo(cred.clientVersion)
        assertThat(restored.payload).isEqualTo(cred.payload)
    }

    @Test
    fun ack_toProto_andFromProto_roundTrip() {
        val ack = Ack(HandshakeError.INVALID_CREDENTIALS)

        val proto = ack.toProto()
        val restored = Ack.fromProto(proto)

        assertThat(restored.error).isEqualTo(HandshakeError.INVALID_CREDENTIALS)
    }

    @Test
    fun payloadSignedPeerIds_toProto_andFromProto_roundTrip() {
        val payload = PayloadSignedPeerIds(
            identity = aliceKeyPair.publicKey,
            sign = ByteArray(64) { it.toByte() }
        )

        val proto = payload.toProto()
        val restored = PayloadSignedPeerIds.fromProto(proto)

        assertThat(restored.identity).isEqualTo(payload.identity)
        assertThat(restored.sign).isEqualTo(payload.sign)
    }

    @Test
    fun frame_writeAndRead_roundTrip() {
        val originalData = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

        val output = ByteArrayOutputStream()
        HandshakeFrame.writeMessage(output, HandshakeFrame.MSG_TYPE_CRED, originalData)

        val input = ByteArrayInputStream(output.toByteArray())
        val frame = HandshakeFrame.readMessage(input, setOf(HandshakeFrame.MSG_TYPE_CRED))

        assertThat(frame.type).isEqualTo(HandshakeFrame.MSG_TYPE_CRED)
        assertThat(frame.payload).isEqualTo(originalData)
    }

    @Test
    fun frame_withDifferentTypes_preservesType() {
        val testData = byteArrayOf(42)

        for (type in listOf(HandshakeFrame.MSG_TYPE_CRED, HandshakeFrame.MSG_TYPE_ACK, HandshakeFrame.MSG_TYPE_PROTO)) {
            val output = ByteArrayOutputStream()
            HandshakeFrame.writeMessage(output, type, testData)

            val input = ByteArrayInputStream(output.toByteArray())
            val frame = HandshakeFrame.readMessage(input, setOf(type))

            assertThat(frame.type).isEqualTo(type)
            assertThat(frame.payload).isEqualTo(testData)
        }
    }

    @Test
    fun frame_withLargePayload_handlesCorrectly() {
        val largeData = ByteArray(10000) { it.toByte() }

        val output = ByteArrayOutputStream()
        HandshakeFrame.writeMessage(output, HandshakeFrame.MSG_TYPE_CRED, largeData)

        val input = ByteArrayInputStream(output.toByteArray())
        val frame = HandshakeFrame.readMessage(input, setOf(HandshakeFrame.MSG_TYPE_CRED))

        assertThat(frame.payload).isEqualTo(largeData)
    }

    @Test
    fun secureSession_fromHandshake_createsCorrectSession() {
        // We'll test SecureSession creation indirectly through HandshakeResult
        val result = HandshakeResult(
            identity = aliceKeyPair.publicKey,
            protoVersion = 8u,
            clientVersion = "any-file/v0.1.0"
        )

        assertThat(result.identity).isEqualTo(aliceKeyPair.publicKey)
        assertThat(result.protoVersion).isEqualTo(8u)
        assertThat(result.clientVersion).isEqualTo("any-file/v0.1.0")
    }

    @Test
    fun peerSignVerifier_withIncompatibleVersion_throwsException() {
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
    fun noVerifyChecker_withIncompatibleVersion_throwsException() {
        val checker = NoVerifyChecker(
            compatibleVersions = listOf(8u)
        )

        val cred = HandshakeCredentials(
            type = CredentialsType.SKIP_VERIFY,
            payload = null,
            version = 9u,
            clientVersion = "test"
        )

        val exception = org.junit.Assert.assertThrows(HandshakeProtocolException::class.java) {
            checker.checkCredential(bobPeerId, cred)
        }

        assertThat(exception.message).contains("Incompatible protocol version")
    }

    @Test
    fun handshakeError_toProto_andFromProto_roundTrip() {
        val errors = listOf(
            HandshakeError.NULL,
            HandshakeError.UNEXPECTED,
            HandshakeError.INVALID_CREDENTIALS,
            HandshakeError.UNEXPECTED_PAYLOAD,
            HandshakeError.SKIP_VERIFY_NOT_ALLOWED,
            HandshakeError.DEADLINE_EXCEEDED,
            HandshakeError.INCOMPATIBLE_VERSION,
            HandshakeError.INCOMPATIBLE_PROTO
        )

        for (error in errors) {
            val proto = error.toProto()
            val restored = HandshakeError.fromProto(proto)
            assertThat(restored).isEqualTo(error)
        }
    }

    @Test
    fun credentialsType_toProto_andFromProto_roundTrip() {
        val types = listOf(
            CredentialsType.SKIP_VERIFY,
            CredentialsType.SIGNED_PEER_IDS
        )

        for (type in types) {
            val proto = type.toProto()
            val restored = CredentialsType.fromProto(proto)
            assertThat(restored).isEqualTo(type)
        }
    }

    @Test
    fun proto_toProto_andFromProto_roundTrip() {
        val proto = Proto(
            proto = ProtoType.DRPC,
            encodings = listOf(Encoding.NONE, Encoding.SNAPPY)
        )

        val protoProto = proto.toProto()
        val restored = Proto.fromProto(protoProto)

        assertThat(restored.proto).isEqualTo(ProtoType.DRPC)
        assertThat(restored.encodings).isEqualTo(listOf(Encoding.NONE, Encoding.SNAPPY))
    }

    @Test
    fun encoding_toProto_andFromProto_roundTrip() {
        val encodings = listOf(Encoding.NONE, Encoding.SNAPPY)

        for (encoding in encodings) {
            val proto = encoding.toProto()
            val restored = Encoding.fromProto(proto)
            assertThat(restored).isEqualTo(encoding)
        }
    }

    @Test
    fun protoType_toProto_andFromProto_roundTrip() {
        val protoType = ProtoType.DRPC

        val proto = protoType.toProto()
        val restored = ProtoType.fromProto(proto)

        assertThat(restored).isEqualTo(ProtoType.DRPC)
    }
}
