package com.anyproto.anyfile.data.network.libp2p

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator

class Libp2pTlsKeyManagerTest {

    @Test
    fun testCreateKeyManager_createsValidKeyManager() {
        // Arrange
        val keyGen = KeyPairGenerator.getInstance("Ed25519")
        val keyPair = keyGen.generateKeyPair()
        val peerId = "12D3KooWExamplePeerId"
        val cert = Libp2pCertificateGenerator.generateSelfSignedCertificate(keyPair, peerId)

        // Act
        val keyManager = Libp2pTlsKeyManager(keyPair, cert, peerId)

        // Assert
        assertNotNull(keyManager)
        assertNotNull(keyManager.getCertificate())
        assertNotNull(keyManager.toKeyManagerFactory())
    }

    @Test
    fun testGetCertificateChain_returnsSingleCert() {
        // Arrange
        val keyGen = KeyPairGenerator.getInstance("Ed25519")
        val keyPair = keyGen.generateKeyPair()
        val peerId = "12D3KooWExamplePeerId"
        val cert = Libp2pCertificateGenerator.generateSelfSignedCertificate(keyPair, peerId)
        val keyManager = Libp2pTlsKeyManager(keyPair, cert, peerId)

        // Act
        val chain = keyManager.getCertificateChain(peerId)

        // Assert
        assertEquals(1, chain.size)
        assertEquals(cert, chain[0])
    }

    @Test
    fun testGetPrivateKey_returnsCorrectKey() {
        // Arrange
        val keyGen = KeyPairGenerator.getInstance("Ed25519")
        val keyPair = keyGen.generateKeyPair()
        val peerId = "12D3KooWExamplePeerId"
        val cert = Libp2pCertificateGenerator.generateSelfSignedCertificate(keyPair, peerId)
        val keyManager = Libp2pTlsKeyManager(keyPair, cert, peerId)

        // Act
        val privateKey = keyManager.getPrivateKey(peerId)

        // Assert
        assertNotNull(privateKey)
        // Note: On JVM, Ed25519 keys are reported as "EdDSA" algorithm
        assertEquals("EdDSA", privateKey.algorithm)
        assertEquals(keyPair.private, privateKey)
    }
}
