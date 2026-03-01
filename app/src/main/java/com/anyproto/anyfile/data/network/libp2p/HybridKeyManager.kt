package com.anyproto.anyfile.data.network.libp2p

import android.util.Log
import com.anyproto.anyfile.data.crypto.PureEd25519
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.X509ExtendedKeyManager

/**
 * Hybrid key manager that uses Ed25519 for peer IDs and RSA for TLS certificates.
 *
 * This solves the Conscrypt Ed25519 PKCS#8 incompatibility by:
 * 1. Generating Ed25519 key pair for peer ID derivation (unchanged)
 * 2. Generating RSA key pair for TLS certificates
 * 3. Creating X509 certificates with RSA keys that embed the peer ID
 *
 * The any-sync infrastructure verifies peer IDs at Layer 2 (handshake),
 * so using RSA for TLS transport is acceptable as long as the peer ID matches.
 *
 * Based on Session 24 findings:
 * - Coordinator REQUIRES TLS with client certificates
 * - Conscrypt rejects Ed25519 PKCS#8 format
 * - Solution: Use RSA for TLS, Ed25519 for peer IDs
 */
class HybridKeyManager(
    private val libp2pKeyManager: Libp2pKeyManager
) {
    companion object {
        private const val TAG = "HybridKeyManager"
        private const val RSA_KEY_SIZE = 2048
        private const val CERTIFICATE_VALIDITY_YEARS = 1L

        init {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    /**
     * Combined identity containing both Ed25519 (for peer ID) and RSA (for TLS).
     */
    data class HybridIdentity(
        val ed25519KeyPair: KeyPair,
        val rsaKeyPair: KeyPair,
        val peerId: PeerId,
        val certificate: X509Certificate
    )

    /**
     * Cached identity for reuse across connections.
     */
    private var cachedIdentity: HybridIdentity? = null

    /**
     * Get or create the hybrid identity.
     *
     * @return HybridIdentity with Ed25519 for peer ID and RSA for TLS
     */
    fun getOrCreateIdentity(): HybridIdentity {
        return cachedIdentity ?: run {
            Log.d(TAG, "Creating new hybrid identity...")

            // Step 1: Generate Ed25519 key pair for peer ID (unchanged)
            Log.d(TAG, "Step 1: Generating Ed25519 key pair for peer ID...")
            val ed25519KeyPair = generateEd25519KeyPair()

            // Extract raw 32-byte public key from X.509 encoded format
            // Android's KeyPairGenerator returns X.509 format (44 bytes), but
            // derivePeerId expects raw 32-byte Ed25519 key
            val rawPublicKey = com.anyproto.anyfile.data.crypto.PureEd25519.extractRawPublicKey(
                ed25519KeyPair.public.encoded
            )
            val peerId = libp2pKeyManager.derivePeerId(rawPublicKey)
            Log.d(TAG, "  Peer ID: ${peerId.base58}")

            // Step 2: Generate RSA key pair for TLS certificate
            Log.d(TAG, "Step 2: Generating RSA key pair for TLS certificate...")
            val rsaKeyPair = generateRsaKeyPair()
            Log.d(TAG, "  RSA public key format: ${rsaKeyPair.public.format}")
            Log.d(TAG, "  RSA private key format: ${rsaKeyPair.private.format}")

            // Step 3: Generate X509 certificate with RSA key, embedding peer ID
            Log.d(TAG, "Step 3: Generating X509 certificate with RSA key...")
            val certificate = generateRsaCertificate(rsaKeyPair, peerId.base58)
            Log.d(TAG, "  Certificate subject: ${certificate.subjectDN}")
            Log.d(TAG, "  Certificate issuer: ${certificate.issuerDN}")
            Log.d(TAG, "  Certificate valid from: ${certificate.notBefore} to ${certificate.notAfter}")

            val identity = HybridIdentity(
                ed25519KeyPair = ed25519KeyPair,
                rsaKeyPair = rsaKeyPair,
                peerId = peerId,
                certificate = certificate
            )

            cachedIdentity = identity
            Log.d(TAG, "Hybrid identity created successfully")
            identity
        }
    }

    /**
     * Create a KeyManager for TLS using the RSA certificate.
     *
     * @return X509ExtendedKeyManager configured with the RSA certificate
     */
    fun createKeyManager(): X509ExtendedKeyManager {
        val identity = getOrCreateIdentity()

        // Create KeyManagerFactory
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())

        // Create a KeyStore to hold our RSA key and certificate
        val ks = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType())
        ks.load(null, null)

        // Set the RSA private key and certificate
        // The certificate chain contains just our self-signed certificate
        ks.setKeyEntry(
            "any-sync-client",
            identity.rsaKeyPair.private,
            charArrayOf(), // No password for the private key
            arrayOf(identity.certificate)
        )

        kmf.init(ks, charArrayOf())

        // Get the first key manager (there should be only one)
        val keyManagers = kmf.keyManagers
        if (keyManagers.isNotEmpty()) {
            return keyManagers[0] as X509ExtendedKeyManager
        }

        throw IllegalStateException("KeyManagerFactory produced no key managers")
    }

    /**
     * Clear the cached identity.
     */
    fun clearIdentity() {
        cachedIdentity = null
        Log.d(TAG, "Cached identity cleared")
    }

    /**
     * Generate Ed25519 key pair for peer ID derivation.
     *
     * Uses PureEd25519.generateKeyPairRaw() to avoid Android's KeyPairGenerator limitation.
     * Returns a Java KeyPair with Ed25519 keys for compatibility with existing code.
     */
    private fun generateEd25519KeyPair(): KeyPair {
        // Use PureEd25519.generateKeyPairRaw() which returns raw 32-byte keys
        val rawKeyPair = PureEd25519.generateKeyPairRaw()

        // Convert raw keys to Java Key objects
        val privateKey = com.anyproto.anyfile.data.crypto.privateKeyFromRaw(rawKeyPair.privateKey)
        val publicKey = com.anyproto.anyfile.data.crypto.publicKeyFromRaw(rawKeyPair.publicKey)

        return KeyPair(publicKey, privateKey)
    }

    /**
     * Generate RSA key pair for TLS certificates.
     */
    private fun generateRsaKeyPair(): KeyPair {
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(RSA_KEY_SIZE, SecureRandom())
        return keyGen.generateKeyPair()
    }

    /**
     * Generate X509 certificate using RSA key with embedded peer ID.
     *
     * The certificate subject CN contains the peer ID for identification.
     * This allows the server to verify which peer is connecting.
     *
     * @param rsaKeyPair The RSA key pair for the certificate
     * @param peerId The libp2p peer ID (base58 encoded)
     * @return X509Certificate self-signed certificate
     */
    private fun generateRsaCertificate(
        rsaKeyPair: KeyPair,
        peerId: String
    ): X509Certificate {
        val now = Instant.now()
        val notBefore = Date.from(now)
        val notAfter = Date.from(now.plus(CERTIFICATE_VALIDITY_YEARS * 365, ChronoUnit.DAYS))

        // Create certificate subject with peer ID
        val subject = X500Name("CN=$peerId")
        val issuer = subject // Self-signed

        // Create certificate builder
        val certBuilder = JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(System.currentTimeMillis()),
            notBefore,
            notAfter,
            subject,
            rsaKeyPair.public
        )
            .addExtension(
                org.bouncycastle.asn1.x509.Extension.basicConstraints,
                true,
                BasicConstraints(true) // CA=true
            )
            .addExtension(
                org.bouncycastle.asn1.x509.Extension.extendedKeyUsage,
                false,
                ExtendedKeyUsage(arrayOf(
                    KeyPurposeId.id_kp_serverAuth,
                    KeyPurposeId.id_kp_clientAuth
                ))
            )

        // Sign the certificate with RSA private key using SHA256withRSA
        val signer = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
            .build(rsaKeyPair.private)

        val certHolder = certBuilder.build(signer)

        // Convert to Java X509Certificate
        val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
        return certFactory.generateCertificate(
            java.io.ByteArrayInputStream(certHolder.encoded)
        ) as X509Certificate
    }
}
