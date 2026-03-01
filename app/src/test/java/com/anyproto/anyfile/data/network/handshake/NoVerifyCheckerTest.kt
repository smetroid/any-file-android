package com.anyproto.anyfile.data.network.handshake

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import com.anyproto.anyfile.data.network.libp2p.PeerId

class NoVerifyCheckerTest {

    @Test
    fun makeCredentials_returnsSkipVerifyType() {
        val checker = NoVerifyChecker()
        val remotePeerId = PeerId("test", byteArrayOf(), byteArrayOf())

        val cred = checker.makeCredentials(remotePeerId)

        assertThat(cred.type).isEqualTo(CredentialsType.SKIP_VERIFY)
        assertThat(cred.payload).isNull()
    }

    @Test
    fun makeCredentials_returnsCorrectVersion() {
        val protoVersion = 9u
        val checker = NoVerifyChecker(protoVersion = protoVersion)
        val remotePeerId = PeerId("test", byteArrayOf(), byteArrayOf())

        val cred = checker.makeCredentials(remotePeerId)

        assertThat(cred.version).isEqualTo(protoVersion)
    }

    @Test
    fun makeCredentials_returnsCorrectClientVersion() {
        val clientVersion = "any-file/v0.2.0"
        val checker = NoVerifyChecker(clientVersion = clientVersion)
        val remotePeerId = PeerId("test", byteArrayOf(), byteArrayOf())

        val cred = checker.makeCredentials(remotePeerId)

        assertThat(cred.clientVersion).isEqualTo(clientVersion)
    }

    @Test
    fun checkCredential_withSkipVerifyType_succeeds() {
        val checker = NoVerifyChecker()
        val remotePeerId = PeerId("test", byteArrayOf(), byteArrayOf())
        val cred = checker.makeCredentials(remotePeerId)

        val result = checker.checkCredential(remotePeerId, cred)

        assertThat(result.identity).isNull()
        assertThat(result.protoVersion).isEqualTo(8u)
        assertThat(result.clientVersion).isEqualTo(NoVerifyChecker.DEFAULT_CLIENT_VERSION)
    }

    @Test
    fun checkCredential_withIncompatibleVersion_throwsException() {
        val checker = NoVerifyChecker(
            protoVersion = 8u,
            compatibleVersions = listOf(8u)
        )
        val remotePeerId = PeerId("test", byteArrayOf(), byteArrayOf())
        val cred = HandshakeCredentials(
            type = CredentialsType.SKIP_VERIFY,
            payload = null,
            version = 9u,
            clientVersion = "test"
        )

        val exception = org.junit.Assert.assertThrows(HandshakeProtocolException::class.java) {
            checker.checkCredential(remotePeerId, cred)
        }

        assertThat(exception.message).contains("Incompatible protocol version")
    }

    @Test
    fun checkCredential_withCompatibleVersion_succeeds() {
        val checker = NoVerifyChecker(
            protoVersion = 8u,
            compatibleVersions = listOf(8u, 9u, 10u)
        )
        val remotePeerId = PeerId("test", byteArrayOf(), byteArrayOf())
        val cred = HandshakeCredentials(
            type = CredentialsType.SKIP_VERIFY,
            payload = null,
            version = 9u,
            clientVersion = "test"
        )

        val result = checker.checkCredential(remotePeerId, cred)

        assertThat(result.protoVersion).isEqualTo(9u)
    }

    @Test
    fun checkCredential_withWrongCredentialsType_throwsException() {
        val checker = NoVerifyChecker()
        val remotePeerId = PeerId("test", byteArrayOf(), byteArrayOf())
        val cred = HandshakeCredentials(
            type = CredentialsType.SIGNED_PEER_IDS,
            payload = byteArrayOf(1, 2, 3),
            version = 8u,
            clientVersion = "test"
        )

        val exception = org.junit.Assert.assertThrows(HandshakeProtocolException::class.java) {
            checker.checkCredential(remotePeerId, cred)
        }

        assertThat(exception.message).contains("Expected SkipVerify credentials")
    }

    @Test
    fun checkCredential_preservesClientVersion() {
        val clientVersion = "any-file/v0.5.0"
        val checker = NoVerifyChecker(clientVersion = clientVersion)
        val remotePeerId = PeerId("test", byteArrayOf(), byteArrayOf())
        val cred = checker.makeCredentials(remotePeerId)

        val result = checker.checkCredential(remotePeerId, cred)

        assertThat(result.clientVersion).isEqualTo(clientVersion)
    }

    @Test
    fun makeCredentials_andCheckCredential_roundTrip() {
        val protoVersion = 10u
        val clientVersion = "any-file/v1.0.0"
        val checker = NoVerifyChecker(
            protoVersion = protoVersion,
            compatibleVersions = listOf(8u, 9u, 10u, 11u),
            clientVersion = clientVersion
        )

        val alice = PeerId("alice", byteArrayOf(), byteArrayOf())
        val bob = PeerId("bob", byteArrayOf(), byteArrayOf())

        // Alice creates credentials for Bob
        val aliceCred = checker.makeCredentials(bob)

        // Bob verifies Alice's credentials
        val result = checker.checkCredential(alice, aliceCred)

        assertThat(result.identity).isNull()
        assertThat(result.protoVersion).isEqualTo(protoVersion)
        assertThat(result.clientVersion).isEqualTo(clientVersion)
    }

    @Test
    fun defaultValues_areCorrect() {
        val checker = NoVerifyChecker()
        val remotePeerId = PeerId("test", byteArrayOf(), byteArrayOf())
        val cred = checker.makeCredentials(remotePeerId)

        assertThat(cred.version).isEqualTo(8u)
        assertThat(cred.clientVersion).isEqualTo("any-file/v0.1.0")
    }
}
