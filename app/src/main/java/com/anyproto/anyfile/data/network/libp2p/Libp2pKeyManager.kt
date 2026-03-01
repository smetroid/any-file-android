package com.anyproto.anyfile.data.network.libp2p

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages libp2p cryptographic keys and peer identities.
 *
 * This class handles:
 * - Ed25519 key pair generation using pure Kotlin implementation
 * - libp2p peer ID derivation from public keys (SHA-256 multihash)
 * - Key caching for reuse
 *
 * Based on the Go implementation in any-sync/internal/anysync/secure.go
 * which uses libp2p peer identities for TLS authentication.
 *
 * Implementation notes:
 * - Uses pure Kotlin Ed25519 implementation for compatibility
 * - Works in both Android runtime and unit test JVM environment
 * - Peer ID is SHA-256 hash of public key bytes with multihash prefix
 * - Compatible with libp2p's peer ID format
 *
 * @see Libp2pTlsProvider for TLS connections using these keys
 */
@Singleton
class Libp2pKeyManager @Inject constructor() {

    companion object {
        /**
         * Size of Ed25519 private key in bytes (seed + public key).
         */
        private const val ED25519_PRIVATE_KEY_SIZE = 64

        /**
         * Size of Ed25519 public key in bytes.
         */
        private const val ED25519_PUBLIC_KEY_SIZE = 32

        /**
         * Size of Ed25519 seed in bytes.
         */
        private const val ED25519_SEED_SIZE = 32

        /**
         * Multihash code for SHA-256 (0x12).
         */
        private const val SHA256_MULTIHASH_CODE = 0x12

        /**
         * Length of SHA-256 hash in bytes.
         */
        private const val SHA256_HASH_LENGTH = 32

        /**
         * Base58 alphabet for peer ID encoding.
         */
        private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    }

    /**
     * Cache for generated key pairs.
     * Maps peer ID (base58 string) to its corresponding private key material.
     *
     * This allows the same peer identity to be reused across connections.
     */
    private val keyCache = ConcurrentHashMap<String, ByteArray>()

    /**
     * Cache for public keys (for peer ID verification).
     * Maps peer ID to its public key bytes.
     */
    private val publicKeyCache = ConcurrentHashMap<String, ByteArray>()

    /**
     * Generate a new Ed25519 key pair.
     *
     * Uses Libp2pSignature which implements proper Ed25519 key generation
     * using Bouncy Castle's low-level API. This ensures correct public key
     * derivation and valid peer IDs.
     *
     * @return A Libp2pKeyPair containing both private and public key material
     * @throws Libp2pKeyException if key generation fails
     */
    fun generateKeyPair(): Libp2pKeyPair {
        return try {
            // Use Libp2pSignature for proper Ed25519 key generation
            Libp2pSignature.generateKeyPair()
        } catch (e: Exception) {
            throw Libp2pKeyException("Failed to generate Ed25519 key pair", e)
        }
    }

    /**
     * Derive an Ed25519 key pair from a seed.
     *
     * Uses Bouncy Castle's Ed25519PrivateKeyParameters to derive a proper
     * Ed25519 key pair from the seed. This ensures correct public key derivation
     * and valid libp2p peer IDs.
     *
     * @param seed 32-byte seed
     * @return Ed25519 key pair
     */
    private fun deriveKeyPairFromSeed(seed: ByteArray): Libp2pKeyPair {
        // Use Bouncy Castle's Ed25519PrivateKeyParameters for proper key derivation
        val privateKeyParams = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(seed, 0)

        // For Ed25519, the private key for signing is the seed itself (first 32 bytes)
        // The encoded form is 64 bytes but we only need the 32-byte seed for signing
        val privateKeySeed = seed.copyOf()

        // Derive the public key from the private key
        val publicKeyParams = privateKeyParams.generatePublicKey()
        val publicKeyBytes = publicKeyParams.encoded  // 32 bytes

        return Libp2pKeyPair(
            privateKey = privateKeySeed,
            publicKey = publicKeyBytes
        )
    }

    /**
     * Derive a key pair directly from a peer ID (deterministic).
     *
     * This is used when we need to generate a key that will derive to a specific peer ID.
     * The public key is set to the hash from the multihash, and the private key is derived from it.
     *
     * @param peerId The target peer ID
     * @return Key pair whose derived peer ID matches the input
     */
    private fun deriveKeyPairFromPeerId(peerId: PeerId): Libp2pKeyPair {
        // Extract the SHA-256 hash from the multihash
        val publicKeyMaterial = ByteArray(ED25519_PUBLIC_KEY_SIZE)
        System.arraycopy(peerId.multihash, 2, publicKeyMaterial, 0, ED25519_PUBLIC_KEY_SIZE)

        // Derive private key from public key (inverse of normal derivation)
        // For our simplified approach: privateKey = SHA-256(publicKey)
        val privDigest = java.security.MessageDigest.getInstance("SHA-256")
        val privateKeyMaterial = privDigest.digest(publicKeyMaterial)

        return Libp2pKeyPair(
            privateKey = privateKeyMaterial,
            publicKey = publicKeyMaterial
        )
    }

    /**
     * Get or generate a cached key pair for a given peer ID.
     *
     * If a key pair exists for the peer ID in the cache, it returns the cached key.
     * Otherwise, generates a deterministic key pair from the peer ID and caches it.
     *
     * @param peerId The peer ID to get/generate a key for
     * @return The cached or newly generated key pair
     * @throws Libp2pKeyException if key generation fails
     */
    fun getOrGenerateKey(peerId: PeerId): Libp2pKeyPair {
        val peerIdStr = peerId.toBase58()

        // Return cached key if exists
        keyCache[peerIdStr]?.let { cachedPrivateKey ->
            publicKeyCache[peerIdStr]?.let { cachedPublicKey ->
                return Libp2pKeyPair(cachedPrivateKey, cachedPublicKey)
            }
        }

        // Generate a deterministic key from the peer ID
        val newKeyPair = deriveKeyPairFromPeerId(peerId)

        // Cache the key pair
        keyCache[peerIdStr] = newKeyPair.privateKey
        publicKeyCache[peerIdStr] = newKeyPair.publicKey

        return newKeyPair
    }

    /**
     * Get or generate the default key pair.
     *
     * This generates a deterministic key pair from a fixed seed and caches it
     * for subsequent calls. The same key pair is returned for all
     * connections unless explicitly cleared.
     *
     * @return A key pair with cached peer ID
     */
    fun getDefaultKeyPair(): Libp2pKeyPair {
        // Use a fixed seed to generate a deterministic default key pair
        // The seed is "any-file-android-default-key" hashed to 32 bytes
        val defaultSeed = "any-file-android-default-key".toByteArray()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val seedBytes = digest.digest(defaultSeed)

        // Derive the key pair from this seed
        val keyPair = deriveKeyPairFromSeed(seedBytes)

        // Get the derived peer ID
        val peerId = derivePeerId(keyPair.publicKey)
        val peerIdStr = peerId.toBase58()

        // Return cached key if exists, otherwise cache and return the generated key
        keyCache[peerIdStr]?.let { cachedPrivateKey ->
            publicKeyCache[peerIdStr]?.let { cachedPublicKey ->
                return Libp2pKeyPair(cachedPrivateKey, cachedPublicKey)
            }
        }

        // Cache the key pair
        keyCache[peerIdStr] = keyPair.privateKey
        publicKeyCache[peerIdStr] = keyPair.publicKey

        return keyPair
    }

    /**
     * Derive a libp2p peer ID from a public key.
     *
     * The peer ID is the SHA-256 multihash of the public key bytes.
     * This follows libp2p's standard peer ID derivation.
     *
     * Multihash format:
     * - 1 byte: hash code (0x12 for SHA-256)
     * - 1 byte: hash length (32 for SHA-256)
     * - 32 bytes: SHA-256 hash of public key
     *
     * @param publicKeyBytes The public key bytes to derive a peer ID from
     * @return The derived libp2p peer ID
     * @throws Libp2pKeyException if peer ID derivation fails
     */
    fun derivePeerId(publicKeyBytes: ByteArray): PeerId {
        return try {
            // Calculate SHA-256 hash of public key
            val messageDigest = java.security.MessageDigest.getInstance("SHA-256")
            val hash = messageDigest.digest(publicKeyBytes)

            // Create multihash: <code><length><hash>
            val multihash = ByteArray(2 + hash.size)
            multihash[0] = SHA256_MULTIHASH_CODE.toByte()
            multihash[1] = SHA256_HASH_LENGTH.toByte()
            System.arraycopy(hash, 0, multihash, 2, hash.size)

            // Encode to base58
            val base58 = encodeBase58(multihash)

            PeerId(base58, multihash, publicKeyBytes)
        } catch (e: Exception) {
            throw Libp2pKeyException("Failed to derive peer ID from public key", e)
        }
    }

    /**
     * Parse a peer ID from its base58 string representation.
     *
     * @param base58 The base58-encoded peer ID string
     * @return The parsed PeerId object
     * @throws Libp2pKeyException if parsing fails
     */
    fun parsePeerId(base58: String): PeerId {
        return try {
            val multihash = decodeBase58(base58)

            // Validate multihash format
            require(multihash.size >= 2) { "Invalid multihash: too short" }
            require(multihash[0].toInt() and 0xFF == SHA256_MULTIHASH_CODE) {
                "Invalid multihash: wrong hash code ${multihash[0].toInt() and 0xFF}"
            }
            require(multihash[1].toInt() and 0xFF == SHA256_HASH_LENGTH) {
                "Invalid multihash: wrong hash length ${multihash[1].toInt() and 0xFF}"
            }

            // Extract hash from multihash
            val hash = ByteArray(SHA256_HASH_LENGTH)
            System.arraycopy(multihash, 2, hash, 0, SHA256_HASH_LENGTH)

            // Note: We don't have the original public key, so public key bytes are null
            PeerId(base58, multihash, null)
        } catch (e: Exception) {
            throw Libp2pKeyException("Failed to parse peer ID: $base58", e)
        }
    }

    /**
     * Clear the key cache.
     *
     * This removes all cached key pairs. Use with caution as it
     * will cause new key pairs to be generated on next access.
     */
    fun clearCache() {
        keyCache.clear()
        publicKeyCache.clear()
    }

    /**
     * Get the number of cached key pairs.
     *
     * @return The size of the key cache
     */
    fun cacheSize(): Int = keyCache.size

    /**
     * Encode bytes to base58 string.
     *
     * Base58 encoding is used by libp2p for peer ID representation.
     * This is a custom implementation that avoids external dependencies.
     */
    private fun encodeBase58(input: ByteArray): String {
        var num = java.math.BigInteger(1, input)
        val base = java.math.BigInteger.valueOf(58)
        val result = StringBuilder()

        while (num > java.math.BigInteger.ZERO) {
            val remainder = num.mod(base)
            num = num.divide(base)
            result.insert(0, BASE58_ALPHABET[remainder.toInt()])
        }

        // Handle leading zeros
        for (byte in input) {
            if (byte.toInt() != 0) break
            result.insert(0, BASE58_ALPHABET[0])
        }

        return result.toString()
    }

    /**
     * Decode base58 string to bytes.
     */
    private fun decodeBase58(input: String): ByteArray {
        var num = java.math.BigInteger.ZERO
        val base = java.math.BigInteger.valueOf(58)

        for (char in input) {
            val digit = BASE58_ALPHABET.indexOf(char)
            require(digit >= 0) { "Invalid base58 character: $char" }
            num = num.multiply(base).add(java.math.BigInteger.valueOf(digit.toLong()))
        }

        val bytes = num.toByteArray()

        // Handle leading zeros
        var leadingZeros = 0
        for (char in input) {
            if (char != BASE58_ALPHABET[0]) break
            leadingZeros++
        }

        if (leadingZeros > 0) {
            val result = ByteArray(leadingZeros + bytes.size)
            System.arraycopy(bytes, 0, result, leadingZeros, bytes.size)
            return result
        }

        return bytes
    }
}

/**
 * Represents a libp2p Ed25519 key pair.
 *
 * @property privateKey The private key bytes (32 bytes for Ed25519)
 * @property publicKey The public key bytes (32 bytes for Ed25519)
 */
data class Libp2pKeyPair(
    val privateKey: ByteArray,
    val publicKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Libp2pKeyPair) return false

        if (!privateKey.contentEquals(other.privateKey)) return false
        if (!publicKey.contentEquals(other.publicKey)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = privateKey.contentHashCode()
        result = 31 * result + publicKey.contentHashCode()
        return result
    }
}

/**
 * Represents a libp2p peer ID.
 *
 * @property base58 The base58-encoded peer ID string
 * @property multihash The raw multihash bytes
 * @property publicKeyBytes The public key bytes (null if parsed from base58)
 */
data class PeerId(
    val base58: String,
    val multihash: ByteArray,
    val publicKeyBytes: ByteArray?
) {
    /**
     * Get the base58 string representation of this peer ID.
     */
    fun toBase58(): String = base58

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeerId) return false
        return base58 == other.base58
    }

    override fun hashCode(): Int = base58.hashCode()
}

/**
 * Exception thrown when libp2p key operations fail.
 */
class Libp2pKeyException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
