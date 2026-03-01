package com.anyproto.anyfile.data.crypto

import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.SignatureException
import java.util.Arrays

/**
 * Android-compatible Ed25519 signing using built-in Signature API.
 *
 * This implementation uses Android's built-in Ed25519 Signature support
 * with our custom PrivateKey/PublicKey implementations.
 *
 * Android's Signature class supports Ed25519 for signing/verification even though
 * KeyFactory doesn't support Ed25519 for loading keys. Our custom key classes
 * provide the PrivateKey/PublicKey interfaces that Signature expects.
 */
object AndroidEd25519 {

    private const val SIGNATURE_ALGORITHM = "Ed25519"
    private const val SIGNATURE_SIZE = 64

    /**
     * Sign a message with an Ed25519 private key.
     *
     * Uses Android's built-in Ed25519 Signature implementation.
     *
     * @param privateKey Ed25519PrivateKey (our custom implementation)
     * @param message Message to sign
     * @return 64-byte Ed25519 signature
     */
    fun sign(privateKey: Ed25519PrivateKey, message: ByteArray): ByteArray {
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(privateKey)
        signature.update(message)
        return signature.sign()
    }

    /**
     * Sign a message with a raw 32-byte private key seed.
     *
     * Uses PureEd25519 (Bouncy Castle low-level API) since Android's
     * Signature class doesn't accept custom PrivateKey implementations.
     *
     * @param rawSeed 32-byte Ed25519 private key seed
     * @param message Message to sign
     * @return 64-byte Ed25519 signature
     */
    fun signRaw(rawSeed: ByteArray, message: ByteArray): ByteArray {
        // Use PureEd25519 instead of Android's Signature API
        // because Signature doesn't accept custom PrivateKey implementations
        val privateKeyParams = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(rawSeed)
        return PureEd25519.sign(privateKeyParams, message)
    }

    /**
     * Verify an Ed25519 signature.
     *
     * Uses Android's built-in Ed25519 Signature implementation.
     *
     * @param publicKey Ed25519PublicKey (our custom implementation)
     * @param message Message that was signed
     * @param signature 64-byte Ed25519 signature
     * @return true if signature is valid, false otherwise
     */
    fun verify(publicKey: Ed25519PublicKey, message: ByteArray, signature: ByteArray): Boolean {
        val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
        verifier.initVerify(publicKey)
        verifier.update(message)
        return try {
            verifier.verify(signature)
        } catch (e: SignatureException) {
            false
        }
    }

    /**
     * Verify an Ed25519 signature with a raw 32-byte public key.
     *
     * Uses PureEd25519 (Bouncy Castle low-level API) since Android's
     * Signature class doesn't accept custom PublicKey implementations.
     *
     * @param rawKey 32-byte Ed25519 public key
     * @param message Message that was signed
     * @param signature 64-byte Ed25519 signature
     * @return true if signature is valid, false otherwise
     */
    fun verifyRaw(rawKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        // Use PureEd25519 instead of Android's Signature API
        // because Signature doesn't accept custom PublicKey implementations
        val publicKeyParams = org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(rawKey)
        return PureEd25519.verify(publicKeyParams, message, signature)
    }

    /**
     * Sign a message with PKCS#8 encoded private key.
     *
     * @param pkcs8Key PKCS#8 encoded Ed25519 private key (48 bytes)
     * @param message Message to sign
     * @return 64-byte Ed25519 signature
     */
    fun signPkcs8(pkcs8Key: ByteArray, message: ByteArray): ByteArray {
        val rawSeed = PureEd25519.extractRawPrivateKey(pkcs8Key)
        return signRaw(rawSeed, message)
    }

    /**
     * Verify an Ed25519 signature with X.509 encoded public key.
     *
     * @param x509Key X.509 encoded Ed25519 public key (44 bytes)
     * @param message Message that was signed
     * @param signature 64-byte Ed25519 signature
     * @return true if signature is valid, false otherwise
     */
    fun verifyX509(x509Key: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        val rawKey = PureEd25519.extractRawPublicKey(x509Key)
        return verifyRaw(rawKey, message, signature)
    }
}
