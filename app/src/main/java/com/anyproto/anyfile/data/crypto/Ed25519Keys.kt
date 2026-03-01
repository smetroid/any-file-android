package com.anyproto.anyfile.data.crypto

import java.security.PrivateKey
import java.security.PublicKey
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Arrays

/**
 * Ed25519 private key that implements the Java Security PrivateKey interface.
 *
 * This class wraps the raw 32-byte Ed25519 private key seed and provides
 * the standard Java Security Key interface without relying on KeyFactory.
 *
 * The encoded format is PKCS#8 (48 bytes for Ed25519).
 */
class Ed25519PrivateKey(
    private val rawSeed: ByteArray
) : PrivateKey {

    override fun getAlgorithm(): String = "Ed25519"

    override fun getFormat(): String = "PKCS#8"

    override fun getEncoded(): ByteArray {
        // Convert raw seed to PKCS#8 format
        // PKCS#8 structure for Ed25519:
        // 0x30 0x2F 0x02 0x01 0x00 0x30 0x05 0x06 0x03 0x2B 0x65 0x70
        // 0x04 0x20 [32-byte seed] 0xA1 0x01 0x01
        // Total: 49 bytes

        val result = ByteArray(49)
        var offset = 0

        // SEQUENCE
        result[offset++] = 0x30.toByte()
        result[offset++] = 0x2F.toByte()  // Fixed: was 0x2E (46), should be 0x2F (47)

        // INTEGER 0 (version)
        result[offset++] = 0x02.toByte()
        result[offset++] = 0x01.toByte()
        result[offset++] = 0x00.toByte()

        // SEQUENCE (AlgorithmIdentifier)
        result[offset++] = 0x30.toByte()
        result[offset++] = 0x05.toByte()
        result[offset++] = 0x06.toByte()
        result[offset++] = 0x03.toByte()
        result[offset++] = 0x2B.toByte()
        result[offset++] = 0x65.toByte()
        result[offset++] = 0x70.toByte()

        // OCTET STRING (private key) - Fixed: removed extra 0x04 byte that was causing issues
        result[offset++] = 0x04.toByte()
        result[offset++] = 0x20.toByte()

        // The seed (32 bytes)
        System.arraycopy(rawSeed, 0, result, offset, rawSeed.size)
        offset += rawSeed.size

        // [1] (context tag for attributes)
        result[offset++] = 0xA1.toByte()
        result[offset++] = 0x01.toByte()

        // BOOLEAN TRUE
        result[offset++] = 0x01.toByte()

        return result
    }

    /**
     * Get the raw 32-byte seed for use with Bouncy Castle's Ed25519Signer.
     */
    fun getRawSeed(): ByteArray = rawSeed.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Ed25519PrivateKey) return false
        return Arrays.equals(rawSeed, other.rawSeed)
    }

    override fun hashCode(): Int = Arrays.hashCode(rawSeed)

    override fun toString(): String = "Ed25519PrivateKey(seed=[${rawSeed.size} bytes])"
}

/**
 * Ed25519 public key that implements the Java Security PublicKey interface.
 *
 * This class wraps the raw 32-byte Ed25519 public key and provides
 * the standard Java Security Key interface without relying on KeyFactory.
 *
 * The encoded format is X.509 (44 bytes for Ed25519).
 */
class Ed25519PublicKey(
    private val rawKey: ByteArray
) : PublicKey {

    override fun getAlgorithm(): String = "Ed25519"

    override fun getFormat(): String = "X.509"

    override fun getEncoded(): ByteArray {
        // Convert raw key to X.509 format
        // X.509 structure for Ed25519:
        // 0x30 0x2A 0x30 0x05 0x06 0x03 0x2B 0x65 0x70 0x03 0x21 0x00 [32-byte key]

        val result = ByteArray(44)
        var offset = 0

        // SEQUENCE
        result[offset++] = 0x30.toByte()
        result[offset++] = 0x2A.toByte()

        // SEQUENCE (AlgorithmIdentifier)
        result[offset++] = 0x30.toByte()
        result[offset++] = 0x05.toByte()
        result[offset++] = 0x06.toByte()
        result[offset++] = 0x03.toByte()
        result[offset++] = 0x2B.toByte()
        result[offset++] = 0x65.toByte()
        result[offset++] = 0x70.toByte()

        // BIT STRING
        result[offset++] = 0x03.toByte()
        result[offset++] = 0x21.toByte()
        result[offset++] = 0x00.toByte()

        // The public key
        System.arraycopy(rawKey, 0, result, offset, rawKey.size)

        return result
    }

    /**
     * Get the raw 32-byte public key for use with Bouncy Castle's Ed25519Signer.
     */
    fun getRawKey(): ByteArray = rawKey.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Ed25519PublicKey) return false
        return Arrays.equals(rawKey, other.rawKey)
    }

    override fun hashCode(): Int = Arrays.hashCode(rawKey)

    override fun toString(): String = "Ed25519PublicKey(key=[${rawKey.size} bytes])"
}

/**
 * Create an Ed25519PrivateKey from PKCS#8 encoded bytes.
 *
 * Extracts the raw 32-byte seed from the PKCS#8 format.
 */
fun privateKeyFromPkcs8(pkcs8: ByteArray): Ed25519PrivateKey {
    val rawSeed = PureEd25519.extractRawPrivateKey(pkcs8)
    return Ed25519PrivateKey(rawSeed)
}

/**
 * Create an Ed25519PublicKey from X.509 encoded bytes.
 *
 * Extracts the raw 32-byte public key from the X.509 format.
 */
fun publicKeyFromX509(x509: ByteArray): Ed25519PublicKey {
    val rawKey = PureEd25519.extractRawPublicKey(x509)
    return Ed25519PublicKey(rawKey)
}

/**
 * Create an Ed25519PrivateKey from raw 32-byte seed.
 *
 * This is the format used by Libp2pKeyPair.
 */
fun privateKeyFromRaw(rawSeed: ByteArray): Ed25519PrivateKey {
    require(rawSeed.size == 32) {
        "Raw private key seed must be 32 bytes, got ${rawSeed.size}"
    }
    return Ed25519PrivateKey(rawSeed.copyOf())
}

/**
 * Create an Ed25519PublicKey from raw 32-byte public key.
 *
 * This is the format used by Libp2pKeyPair.
 */
fun publicKeyFromRaw(rawKey: ByteArray): Ed25519PublicKey {
    require(rawKey.size == 32) {
        "Raw public key must be 32 bytes, got ${rawKey.size}"
    }
    return Ed25519PublicKey(rawKey.copyOf())
}
