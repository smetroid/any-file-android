package com.anyproto.anyfile.data.network.libp2p

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for Libp2pKeyManager.
 *
 * Tests verify:
 * - Key generation produces valid Ed25519 key pairs
 * - Peer ID derivation is correct and consistent
 * - Key caching works properly
 * - Base58 encoding/decoding works correctly
 */
class Libp2pKeyManagerTest {

    private lateinit var keyManager: Libp2pKeyManager

    @Before
    fun setUp() {
        keyManager = Libp2pKeyManager()
    }

    @Test
    fun `generateKeyPair produces valid Ed25519 key pair`() {
        // When: generating a key pair
        val keyPair = keyManager.generateKeyPair()

        // Then: the keys should be valid Ed25519 (32 bytes each)
        assertThat(keyPair.privateKey).isNotNull()
        assertThat(keyPair.publicKey).isNotNull()
        assertThat(keyPair.privateKey.size).isEqualTo(32)
        assertThat(keyPair.publicKey.size).isEqualTo(32)
    }

    @Test
    fun `generateKeyPair produces unique keys each time`() {
        // When: generating multiple key pairs
        val key1 = keyManager.generateKeyPair()
        val key2 = keyManager.generateKeyPair()

        // Then: they should be different
        assertThat(key1.privateKey).isNotEqualTo(key2.privateKey)
        assertThat(key1.publicKey).isNotEqualTo(key2.publicKey)
    }

    @Test
    fun `derivePeerId produces consistent peer ID for same public key`() {
        // Given: a generated key pair
        val keyPair = keyManager.generateKeyPair()

        // When: deriving peer ID twice from the same public key
        val peerId1 = keyManager.derivePeerId(keyPair.publicKey)
        val peerId2 = keyManager.derivePeerId(keyPair.publicKey)

        // Then: both peer IDs should be identical
        assertThat(peerId1).isEqualTo(peerId2)
        assertThat(peerId1.toBase58()).isEqualTo(peerId2.toBase58())
        assertThat(peerId1.multihash).isEqualTo(peerId2.multihash)
    }

    @Test
    fun `derivePeerId produces different peer IDs for different keys`() {
        // Given: two different key pairs
        val key1 = keyManager.generateKeyPair()
        val key2 = keyManager.generateKeyPair()

        // When: deriving peer IDs from different public keys
        val peerId1 = keyManager.derivePeerId(key1.publicKey)
        val peerId2 = keyManager.derivePeerId(key2.publicKey)

        // Then: peer IDs should be different
        assertThat(peerId1).isNotEqualTo(peerId2)
        assertThat(peerId1.toBase58()).isNotEqualTo(peerId2.toBase58())
    }

    @Test
    fun `derivePeerId produces valid base58 string representation`() {
        // Given: a generated key pair
        val keyPair = keyManager.generateKeyPair()

        // When: deriving peer ID
        val peerId = keyManager.derivePeerId(keyPair.publicKey)

        // Then: the peer ID should have a valid base58 representation
        val base58 = peerId.toBase58()
        assertThat(base58).isNotEmpty()
        assertThat(base58.length).isAtLeast(1) // libp2p peer IDs are typically ~50 chars

        // And: should be parseable back to the same peer ID (multihash should match)
        val parsed = keyManager.parsePeerId(base58)
        assertThat(parsed.base58).isEqualTo(base58)
        assertThat(parsed.multihash).isEqualTo(peerId.multihash)
    }

    @Test
    fun `parsePeerId correctly parses valid base58 string`() {
        // Given: a generated peer ID
        val keyPair = keyManager.generateKeyPair()
        val originalPeerId = keyManager.derivePeerId(keyPair.publicKey)
        val base58 = originalPeerId.toBase58()

        // When: parsing the base58 string
        val parsedPeerId = keyManager.parsePeerId(base58)

        // Then: should have the same base58 and multihash
        assertThat(parsedPeerId.toBase58()).isEqualTo(base58)
        assertThat(parsedPeerId.multihash).isEqualTo(originalPeerId.multihash)
        // Note: publicKeyBytes will be null when parsed, so we don't compare that
    }

    @Test
    fun `parsePeerId throws exception for invalid base58 string`() {
        // Given: an invalid base58 string (contains character not in base58 alphabet)
        val invalidBase58 = "invalid-peer-id-string-with-O-and-I-and-0-and-l"

        // When & Then: parsing should throw Libp2pKeyException
        try {
            keyManager.parsePeerId(invalidBase58)
            throw AssertionError("Expected Libp2pKeyException for invalid peer ID")
        } catch (e: Libp2pKeyException) {
            // Expected
            assertThat(e.message).contains("Failed to parse peer ID")
        }
    }

    @Test
    fun `parsePeerId throws exception for empty string`() {
        // Given: an empty string
        val emptyBase58 = ""

        // When & Then: parsing should throw an exception
        try {
            keyManager.parsePeerId(emptyBase58)
            throw AssertionError("Expected exception for empty peer ID")
        } catch (e: Exception) {
            // Expected - could be Libp2pKeyException or arithmetic exception
        }
    }

    @Test
    fun `derivePeerId produces correct multihash format`() {
        // Given: a generated key pair
        val keyPair = keyManager.generateKeyPair()

        // When: deriving peer ID
        val peerId = keyManager.derivePeerId(keyPair.publicKey)

        // Then: multihash should have correct format
        // Multihash: <code><length><hash>
        assertThat(peerId.multihash.size).isEqualTo(34) // 1 + 1 + 32
        assertThat(peerId.multihash[0].toInt() and 0xFF).isEqualTo(0x12) // SHA-256 code
        assertThat(peerId.multihash[1].toInt() and 0xFF).isEqualTo(32) // Hash length
    }

    @Test
    fun `getOrGenerateKey returns cached key for same peer ID`() {
        // Given: a peer ID from a generated key
        val originalKey = keyManager.generateKeyPair()
        val peerId = keyManager.derivePeerId(originalKey.publicKey)

        // Clear cache first, then add the key
        keyManager.clearCache()
        keyManager.getOrGenerateKey(peerId) // This will generate and cache a new key

        // When: getting the key for the same peer ID
        val cachedKey = keyManager.getOrGenerateKey(peerId)

        // Then: should return the cached key
        assertThat(keyManager.cacheSize()).isEqualTo(1)
        assertThat(cachedKey.privateKey).isNotNull()
        assertThat(cachedKey.publicKey).isNotNull()
    }

    @Test
    fun `getOrGenerateKey generates and caches new key for new peer ID`() {
        // Given: a new peer ID (from generated key)
        val key = keyManager.generateKeyPair()
        val peerId = keyManager.derivePeerId(key.publicKey)

        // Clear the cache to simulate "new" peer ID
        keyManager.clearCache()

        // When: getting the key for the peer ID
        val retrievedKey = keyManager.getOrGenerateKey(peerId)

        // Then: should generate and cache a new key
        assertThat(retrievedKey).isNotNull()
        assertThat(keyManager.cacheSize()).isEqualTo(1)
    }

    @Test
    fun `getDefaultKeyPair returns same key on subsequent calls`() {
        // When: getting default key pair multiple times
        keyManager.clearCache()
        val key1 = keyManager.getDefaultKeyPair()
        val key2 = keyManager.getDefaultKeyPair()

        // Then: should return the same key
        assertThat(key1.privateKey).isEqualTo(key2.privateKey)
        assertThat(key1.publicKey).isEqualTo(key2.publicKey)
    }

    @Test
    fun `clearCache removes all cached keys`() {
        // Given: a cache with some keys
        keyManager.clearCache()
        keyManager.getDefaultKeyPair()
        keyManager.generateKeyPair()
        assertThat(keyManager.cacheSize()).isAtLeast(1)

        // When: clearing the cache
        keyManager.clearCache()

        // Then: cache should be empty
        assertThat(keyManager.cacheSize()).isEqualTo(0)
    }

    @Test
    fun `cacheSize returns number of cached keys`() {
        // Given: empty cache
        keyManager.clearCache()
        assertThat(keyManager.cacheSize()).isEqualTo(0)

        // When: adding keys
        keyManager.getDefaultKeyPair()
        assertThat(keyManager.cacheSize()).isEqualTo(1)

        // And: adding more keys
        val key = keyManager.generateKeyPair()
        val peerId = keyManager.derivePeerId(key.publicKey)
        keyManager.getOrGenerateKey(peerId)
        assertThat(keyManager.cacheSize()).isEqualTo(2)
    }

    @Test
    fun `base58 encoding only contains valid characters`() {
        // Given: a generated peer ID
        val keyPair = keyManager.generateKeyPair()
        val peerId = keyManager.derivePeerId(keyPair.publicKey)
        val base58 = peerId.toBase58()

        // Then: should only contain base58 alphabet characters
        val validChars = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        for (char in base58) {
            assertThat(validChars.contains(char)).isTrue()
        }
    }

    @Test
    fun `peer ID base58 string is deterministic`() {
        // Given: the same key pair
        val keyPair = keyManager.generateKeyPair()

        // When: deriving peer ID multiple times
        val peerId1 = keyManager.derivePeerId(keyPair.publicKey)
        val peerId2 = keyManager.derivePeerId(keyPair.publicKey)

        // Then: base58 strings should be identical
        assertThat(peerId1.toBase58()).isEqualTo(peerId2.toBase58())
    }
}
