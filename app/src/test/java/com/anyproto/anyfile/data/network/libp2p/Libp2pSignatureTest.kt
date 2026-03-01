package com.anyproto.anyfile.data.network.libp2p

import com.google.common.truth.Truth.assertThat
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Before
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Security
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Unit tests for Ed25519 signing and verification using Bouncy Castle.
 *
 * These tests use Bouncy Castle because Android's Ed25519 KeyPairGenerator
 * is not available in the JVM test environment.
 *
 * The production code in Libp2pSignature.kt uses Android's built-in
 * KeyPairGenerator with "Ed25519" algorithm (API 26+).
 */
class Libp2pSignatureTest {

    // Bouncy Castle-based implementation for unit tests
    private object BouncyCastleEd25519 {
        private const val ALGORITHM = "Ed25519"
        private const val SIGNATURE_ALGORITHM = "Ed25519"

        fun generateKeyPair(): Pair<ByteArray, ByteArray> {
            val keyPairGenerator = KeyPairGenerator.getInstance(ALGORITHM)
            val keyPair = keyPairGenerator.generateKeyPair()

            // Store keys in standard encoded format
            // PKCS#8 for private key (48 bytes for Ed25519)
            // X.509 for public key (44 bytes for Ed25519)
            val privateKey = keyPair.private.encoded
            val publicKey = keyPair.public.encoded

            return Pair(privateKey, publicKey)
        }

        fun sign(privateKey: ByteArray, message: ByteArray): ByteArray {
            val keyFactory = java.security.KeyFactory.getInstance(ALGORITHM)
            val privateKeySpec = PKCS8EncodedKeySpec(privateKey)
            val privateKeyObj = keyFactory.generatePrivate(privateKeySpec)

            val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
            signature.initSign(privateKeyObj)
            signature.update(message)
            return signature.sign()
        }

        fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
            return try {
                val keyFactory = java.security.KeyFactory.getInstance(ALGORITHM)
                val publicKeySpec = X509EncodedKeySpec(publicKey)
                val publicKeyObj = keyFactory.generatePublic(publicKeySpec)

                val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
                verifier.initVerify(publicKeyObj)
                verifier.update(message)
                verifier.verify(signature)
            } catch (e: Exception) {
                false
            }
        }
    }

    @Before
    fun setup() {
        // Register Bouncy Castle as a security provider
        Security.addProvider(BouncyCastleProvider())
    }

    @Test
    fun generateKeyPair_returnsValidKeyPair() {
        val (privateKey, publicKey) = BouncyCastleEd25519.generateKeyPair()

        // PKCS#8 encoded Ed25519 private key is 48 bytes
        assertThat(privateKey).hasLength(48)
        // X.509 encoded Ed25519 public key is 44 bytes
        assertThat(publicKey).hasLength(44)
    }

    @Test
    fun generateKeyPair_producesUniqueKeys() {
        val (privateKey1, publicKey1) = BouncyCastleEd25519.generateKeyPair()
        val (privateKey2, publicKey2) = BouncyCastleEd25519.generateKeyPair()

        assertThat(privateKey1).isNotEqualTo(privateKey2)
        assertThat(publicKey1).isNotEqualTo(publicKey2)
    }

    @Test
    fun sign_returns64ByteSignature() {
        val (privateKey, _) = BouncyCastleEd25519.generateKeyPair()
        val message = "test message".toByteArray()

        val signature = BouncyCastleEd25519.sign(privateKey, message)

        assertThat(signature).hasLength(64)
    }

    @Test
    fun sign_producesDeterministicSignatures() {
        val (privateKey, _) = BouncyCastleEd25519.generateKeyPair()
        val message = "test message".toByteArray()

        val signature1 = BouncyCastleEd25519.sign(privateKey, message)
        val signature2 = BouncyCastleEd25519.sign(privateKey, message)

        assertThat(signature1).isEqualTo(signature2)
    }

    @Test
    fun verify_acceptsValidSignature() {
        val (privateKey, publicKey) = BouncyCastleEd25519.generateKeyPair()
        val message = "test message".toByteArray()
        val signature = BouncyCastleEd25519.sign(privateKey, message)

        val verified = BouncyCastleEd25519.verify(publicKey, message, signature)

        assertThat(verified).isTrue()
    }

    @Test
    fun verify_rejectsInvalidSignature() {
        val (privateKey, publicKey) = BouncyCastleEd25519.generateKeyPair()
        val message = "test message".toByteArray()
        val signature = BouncyCastleEd25519.sign(privateKey, message)

        // Tamper with the signature
        signature[0] = (signature[0].toInt() xor 0xFF).toByte()

        val verified = BouncyCastleEd25519.verify(publicKey, message, signature)

        assertThat(verified).isFalse()
    }

    @Test
    fun verify_rejectsSignatureForWrongMessage() {
        val (privateKey, publicKey) = BouncyCastleEd25519.generateKeyPair()
        val message = "test message".toByteArray()
        val signature = BouncyCastleEd25519.sign(privateKey, message)
        val wrongMessage = "wrong message".toByteArray()

        val verified = BouncyCastleEd25519.verify(publicKey, wrongMessage, signature)

        assertThat(verified).isFalse()
    }

    @Test
    fun verify_rejectsSignatureForWrongPublicKey() {
        val (privateKey1, publicKey1) = BouncyCastleEd25519.generateKeyPair()
        val (_, publicKey2) = BouncyCastleEd25519.generateKeyPair()
        val message = "test message".toByteArray()
        val signature = BouncyCastleEd25519.sign(privateKey1, message)

        val verified = BouncyCastleEd25519.verify(publicKey2, message, signature)

        assertThat(verified).isFalse()
    }

    @Test
    fun signAndVerify_emptyMessage() {
        val (privateKey, publicKey) = BouncyCastleEd25519.generateKeyPair()
        val message = byteArrayOf()

        val signature = BouncyCastleEd25519.sign(privateKey, message)
        val verified = BouncyCastleEd25519.verify(publicKey, message, signature)

        assertThat(verified).isTrue()
    }

    @Test
    fun signAndVerify_largeMessage() {
        val (privateKey, publicKey) = BouncyCastleEd25519.generateKeyPair()
        val message = ByteArray(1024) { it.toByte() }

        val signature = BouncyCastleEd25519.sign(privateKey, message)
        val verified = BouncyCastleEd25519.verify(publicKey, message, signature)

        assertThat(verified).isTrue()
    }

    @Test
    fun signAndVerify_peerIdMessage() {
        // This is the actual use case in the handshake protocol
        val (privateKey, publicKey) = BouncyCastleEd25519.generateKeyPair()

        // Simulate peer IDs (base58 strings)
        val localPeerId = "12D3KooWABCDEF1234567890ABCDEFGHIJKLMN"
        val remotePeerId = "12D3KooWZYXWVUTSRQPONMLKJIHGFEDCBA9876543210"

        val message = (localPeerId + remotePeerId).toByteArray()

        val signature = BouncyCastleEd25519.sign(privateKey, message)
        val verified = BouncyCastleEd25519.verify(publicKey, message, signature)

        assertThat(verified).isTrue()
    }
}

// Helper functions for Truth assertions
private fun ByteArray.hasLength(length: Int) {
    assertThat(this.size).isEqualTo(length)
}

private fun ByteArray.isNotEqualTo(other: ByteArray) {
    assertThat(this.contentEquals(other)).isFalse()
}

private fun ByteArray.isEqualTo(other: ByteArray) {
    assertThat(this.contentEquals(other)).isTrue()
}
