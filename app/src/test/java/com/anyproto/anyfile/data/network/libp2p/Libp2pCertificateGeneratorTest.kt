package com.anyproto.anyfile.data.network.libp2p

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

class Libp2pCertificateGeneratorTest {

    @Before
    fun setup() {
        // Register Bouncy Castle as a security provider
        Security.addProvider(BouncyCastleProvider())
    }

    @Test
    fun testGenerateSelfSignedCertificate_createsValidCertificate() {
        // Arrange
        val keyGen = KeyPairGenerator.getInstance("Ed25519")
        val keyPair = keyGen.generateKeyPair()
        val peerId = "12D3KooWExamplePeerIdForTesting12345678"

        // Act
        val cert = Libp2pCertificateGenerator.generateSelfSignedCertificate(keyPair, peerId)

        // Assert
        assertThat(cert).isNotNull()
        assertThat(cert.subjectX500Principal.name).contains(peerId)
        assertThat(cert.issuerX500Principal.name).contains(peerId)
        assertThat(cert.publicKey.algorithm).isEqualTo("Ed25519")
        assertThat(cert.notBefore.before(cert.notAfter)).isTrue()
    }

    @Test
    fun testEncodeToPem_createsValidPemFormat() {
        // Arrange
        val keyGen = KeyPairGenerator.getInstance("Ed25519")
        val keyPair = keyGen.generateKeyPair()
        val peerId = "12D3KooWTestPeerId"
        val cert = Libp2pCertificateGenerator.generateSelfSignedCertificate(keyPair, peerId)

        // Act
        val pem = Libp2pCertificateGenerator.encodeToPem(cert)

        // Assert
        assertThat(pem).startsWith("-----BEGIN CERTIFICATE-----")
        assertThat(pem).endsWith("-----END CERTIFICATE-----")
        assertThat(pem).contains("\n")
    }
}
