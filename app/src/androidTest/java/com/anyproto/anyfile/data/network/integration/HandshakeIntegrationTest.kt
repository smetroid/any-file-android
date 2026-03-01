package com.anyproto.anyfile.data.network.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anyproto.anyfile.data.network.handshake.AnySyncHandshake
import com.anyproto.anyfile.data.network.handshake.CredentialChecker
import com.anyproto.anyfile.data.network.handshake.PeerSignVerifier
import com.anyproto.anyfile.data.network.libp2p.Libp2pKeyManager
import com.anyproto.anyfile.data.network.libp2p.Libp2pSignature
import com.anyproto.anyfile.data.network.libp2p.Libp2pTlsProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Integration tests for the any-sync handshake (Layer 2).
 *
 * These tests verify that Layer 2 (any-sync Handshake) integrates properly
 * with Layer 1 (libp2p TLS) and all components are accessible via dependency
 * injection.
 *
 * Tests verify:
 * - Ed25519 signature generation and verification
 * - libp2p TLS provider creates valid peer identities
 * - Handshake orchestrator is accessible
 * - All components are properly injected via Hilt
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class HandshakeIntegrationTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var keyManager: Libp2pKeyManager

    @Inject
    lateinit var tlsProvider: Libp2pTlsProvider

    @Before
    fun setup() {
        hiltRule.inject()
    }

    /**
     * Test that Ed25519 signatures can be generated and verified.
     *
     * Verifies:
     * - Libp2pSignature can generate Ed25519 key pairs
     * - Signatures can be created for messages
     * - Signatures can be verified with the public key
     *
     * Note: Libp2pSignature uses raw 32-byte keys for Ed25519 signing/verification.
     * This works reliably on both JVM (unit tests) and Android runtime.
     */
    @Test
    fun testCanGenerateEd25519Signature() = runTest {
        val keyPair = Libp2pSignature.generateKeyPair()
        val message = "test message".toByteArray()
        val sig = Libp2pSignature.sign(keyPair.privateKey, message)

        val isValid = Libp2pSignature.verify(keyPair.publicKey, message, sig)
        assertTrue("Signature should be valid", isValid)
        assertNotNull("Signature should be generated", sig)
        assertTrue("Signature should be 64 bytes", sig.size == 64)
        // Libp2pSignature uses raw 32-byte keys for Ed25519 operations
        assertTrue("Public key should be 32 bytes (raw Ed25519)", keyPair.publicKey.size == 32)
        assertTrue("Private key should be 32 bytes (raw seed)", keyPair.privateKey.size == 32)
    }

    /**
     * Test that libp2p TLS provider creates valid peer identities.
     *
     * Verifies:
     * - TLS provider creates a peer identity
     * - Peer identity has a valid peer ID
     * - Peer identity has Ed25519 keys
     * - Identity is cached (same on subsequent calls)
     *
     * Note: Both Libp2pKeyManager and Libp2pSignature now use raw 32-byte Ed25519 keys.
     * The TLS provider uses Libp2pKeyManager for key management.
     */
    @Test
    fun testLibp2pTlsCreatesPeerIds() = runTest {
        val identity1 = tlsProvider.getPeerIdentity()
        val identity2 = tlsProvider.getPeerIdentity()

        // Should return cached identity
        assertTrue("Peer IDs should match", identity1.peerId.base58 == identity2.peerId.base58)
        assertTrue("Peer ID should not be empty", identity1.peerId.base58.isNotEmpty())
        // Verify the multihash format (not the base58 string prefix)
        // Multihash should start with [18, 32] for SHA-256
        val mh = identity1.peerId.multihash
        assertTrue("Multihash should have at least 2 bytes", mh.size >= 2)
        assertTrue("Multihash[0] should be 18 (SHA-256)", (mh[0].toInt() and 0xFF) == 0x12)
        assertTrue("Multihash[1] should be 32 (hash length)", (mh[1].toInt() and 0xFF) == 32)
        // Libp2pKeyManager uses raw 32-byte keys (not X.509/PKCS#8)
        assertTrue("Public key should be 32 bytes (raw)", identity1.keyPair.publicKey.size == 32)
        assertTrue("Private key should be 32 bytes (raw)", identity1.keyPair.privateKey.size == 32)
    }

    /**
     * Test that handshake orchestrator is accessible.
     *
     * Verifies:
     * - AnySyncHandshake object exists
     * - Has the correct timeout constant
     * - Methods are accessible (via object reference)
     */
    @Test
    fun testHandshakeOrchestratorExists() = runTest {
        // Verify AnySyncHandshake is accessible
        assertNotNull("AnySyncHandshake should not be null", AnySyncHandshake)
        assertTrue("Default timeout should be 30000ms", AnySyncHandshake.DEFAULT_TIMEOUT_MS == 30000L)
    }

    /**
     * Test that PeerSignVerifier can be created.
     *
     * Verifies:
     * - PeerSignVerifier can be instantiated with a key pair and peer ID
     * - It implements CredentialChecker interface
     */
    @Test
    fun testPeerSignVerifierCanBeCreated() = runTest {
        val identity = tlsProvider.getPeerIdentity()
        val verifier = PeerSignVerifier(
            localKeyPair = identity.keyPair,
            localPeerId = identity.peerId
        )
        assertNotNull("PeerSignVerifier should be created", verifier)
        assertTrue("PeerSignVerifier should implement CredentialChecker",
            verifier is CredentialChecker)
    }

    /**
     * Test that all Layer 2 components integrate with Layer 1.
     *
     * Verifies:
     * - Key manager is injected
     * - TLS provider is injected
     * - Components work together (peer ID derivation)
     */
    @Test
    fun testLayer2IntegratesWithLayer1() = runTest {
        // Verify Layer 1 components
        val identity = tlsProvider.getPeerIdentity()
        assertTrue("Peer ID should be valid", identity.peerId.base58.isNotEmpty())

        // Verify we can derive the same peer ID from the public key
        val derivedPeerId = keyManager.derivePeerId(identity.keyPair.publicKey)
        assertTrue("Derived peer ID should match", derivedPeerId.base58 == identity.peerId.base58)

        // Verify we can create a PeerSignVerifier with the key pair and peer ID
        val verifier = PeerSignVerifier(
            localKeyPair = identity.keyPair,
            localPeerId = identity.peerId
        )
        assertNotNull("PeerSignVerifier should be created", verifier)
    }

    /**
     * Test that key pair sizes match expected Ed25519 sizes.
     *
     * Verifies:
     * - Libp2pSignature uses raw 32-byte keys for signing/verification
     * - Libp2pKeyManager uses raw 32-byte keys for peer ID derivation
     * - Both components use the same key format (raw Ed25519)
     *
     * Note: Both Libp2pSignature and Libp2pKeyManager now use raw 32-byte Ed25519 keys.
     * This format works reliably on both JVM (unit tests) and Android runtime.
     */
    @Test
    fun testEd25519KeySizes() = runTest {
        // Libp2pSignature uses raw 32-byte keys for Ed25519 signing
        val signatureKeys = Libp2pSignature.generateKeyPair()
        assertTrue("Libp2pSignature public key should be 32 bytes (raw Ed25519)",
            signatureKeys.publicKey.size == 32)
        assertTrue("Libp2pSignature private key should be 32 bytes (raw seed)",
            signatureKeys.privateKey.size == 32)

        // Libp2pKeyManager uses raw 32-byte keys for peer ID derivation
        val keyManagerKeys = keyManager.getDefaultKeyPair()
        assertTrue("Libp2pKeyManager public key should be 32 bytes (raw)",
            keyManagerKeys.publicKey.size == 32)
        assertTrue("Libp2pKeyManager private key should be 32 bytes (raw)",
            keyManagerKeys.privateKey.size == 32)

        // Both components now use the same raw key format
        assertTrue("Both key types should have same public key size",
            signatureKeys.publicKey.size == keyManagerKeys.publicKey.size)
        assertTrue("Both key types should have same private key size",
            signatureKeys.privateKey.size == keyManagerKeys.privateKey.size)
    }
}
