package com.anyproto.anyfile.data.network.libp2p

import com.anyproto.anyfile.data.crypto.AndroidEd25519
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.SecureRandom
import java.security.Security

/**
 * Ed25519 signing and verification for any-sync handshake.
 *
 * Provides Ed25519 signature operations for the any-sync handshake protocol.
 * Uses Bouncy Castle's low-level API (Ed25519Signer) to bypass Android's KeyFactory limitation.
 *
 * Based on the Go implementation in any-sync/net/secureservice/handshake/credential.go
 *
 * @throws Libp2pSignatureException if signing or verification fails
 */
object Libp2pSignature {

    private const val ED25519_ALGORITHM = "Ed25519"
    private const val SIGNATURE_ALGORITHM = "Ed25519"
    // Raw key sizes (32 bytes for Ed25519)
    private const val PRIVATE_KEY_SIZE = 32  // Raw 32-byte seed
    private const val PUBLIC_KEY_SIZE = 32   // Raw 32-byte public key
    private const val SIGNATURE_SIZE = 64

    init {
        // Ensure Bouncy Castle provider is available
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    /**
     * Generate a new Ed25519 key pair.
     *
     * Generates raw 32-byte keys for reliable Ed25519 operations on Android.
     * Uses Bouncy Castle's low-level Ed25519PrivateKeyParameters API directly,
     * bypassing JCA's KeyPairGenerator which is unreliable on Android.
     *
     * NOTE: We do NOT use JCA's KeyPairGenerator (Android's or BC provider) because
     * Ed25519 algorithm is not reliably available on Android runtime.
     *
     * @return A Libp2pKeyPair containing 32-byte raw private key seed and 32-byte raw public key
     * @throws Libp2pSignatureException if key generation fails
     */
    fun generateKeyPair(): Libp2pKeyPair {
        return try {
            // Use Bouncy Castle's Ed25519PrivateKeyParameters with SecureRandom to generate a new key pair
            val secureRandom = SecureRandom()
            val privateKeyParams = Ed25519PrivateKeyParameters(secureRandom)

            // Get the 32-byte seed (what we call "private key" for signing)
            val privateKeySeed = privateKeyParams.encoded  // 32 bytes

            // Derive the public key from the private key
            val publicKeyParams = privateKeyParams.generatePublicKey()
            val publicKeyBytes = publicKeyParams.encoded  // 32 bytes

            Libp2pKeyPair(
                privateKey = privateKeySeed,  // 32-byte seed for signing
                publicKey = publicKeyBytes     // 32-byte public key for verification
            )
        } catch (e: Exception) {
            throw Libp2pSignatureException("Failed to generate Ed25519 key pair: ${e.message}", e)
        }
    }

    /**
     * Sign a message with an Ed25519 private key.
     *
     * Uses Android's built-in Ed25519 Signature support.
     *
     * @param privateKey 32-byte raw Ed25519 private key seed
     * @param message Message to sign
     * @return 64-byte Ed25519 signature
     * @throws Libp2pSignatureException if signing fails
     */
    fun sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        require(privateKey.size == PRIVATE_KEY_SIZE) {
            "Private key must be $PRIVATE_KEY_SIZE bytes, got ${privateKey.size}"
        }

        return try {
            AndroidEd25519.signRaw(privateKey, message)
        } catch (e: Exception) {
            throw Libp2pSignatureException("Failed to sign message", e)
        }
    }

    /**
     * Verify an Ed25519 signature.
     *
     * Uses Android's built-in Ed25519 Signature support.
     *
     * @param publicKey 32-byte raw Ed25519 public key
     * @param message Message that was signed
     * @param signature 64-byte Ed25519 signature
     * @return true if signature is valid, false otherwise
     * @throws Libp2pSignatureException if verification fails (except invalid signature)
     */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        require(publicKey.size == PUBLIC_KEY_SIZE) {
            "Public key must be $PUBLIC_KEY_SIZE bytes, got ${publicKey.size}"
        }
        require(signature.size == SIGNATURE_SIZE) {
            "Signature must be $SIGNATURE_SIZE bytes, got ${signature.size}"
        }

        return try {
            AndroidEd25519.verifyRaw(publicKey, message, signature)
        } catch (e: Exception) {
            throw Libp2pSignatureException("Failed to verify signature", e)
        }
    }
}

/**
 * Exception thrown when Ed25519 signature operations fail.
 */
class Libp2pSignatureException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
