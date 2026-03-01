package com.anyproto.anyfile.data.network.libp2p

import com.anyproto.anyfile.data.crypto.PureEd25519
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.DefaultDigestAlgorithmIdentifierFinder
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder
import org.bouncycastle.operator.OperatorCreationException
import org.bouncycastle.operator.bc.BcContentSignerBuilder
import org.bouncycastle.operator.bc.BcDSAContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date

/**
 * Generates X.509 certificates from Ed25519 key pairs for libp2p TLS.
 *
 * libp2p TLS requires X.509 certificates that embed the Ed25519 public key.
 * The certificate subject CN contains the peer ID for identification.
 *
 * Certificate format:
 * - Subject: CN={peerId} (libp2p peer ID in base58)
 * - Issuer: CN={peerId} (self-signed)
 * - Public Key: Ed25519 public key
 * - Signature: Ed25519 signature of the certificate
 * - Validity: 1 year (can be renewed)
 * - Key Usage: Digital Signature, Key Encipherment
 * - Extended Key Usage: Server Auth, Client Auth
 *
 * Uses Bouncy Castle's low-level Ed25519Signer API to bypass Android's
 * KeyFactory limitation. The certificate is signed using PureEd25519
 * which uses Ed25519Signer directly.
 *
 * Based on libp2p go-libp2p/p2p/security/tls/certificate.go
 */
object Libp2pCertificateGenerator {

    private const val CERTIFICATE_VALIDITY_YEARS = 1L

    init {
        // Register BouncyCastle as security provider
        Security.addProvider(BouncyCastleProvider())
    }

    /**
     * Generate a self-signed X.509 certificate from an Ed25519 key pair.
     *
     * @param keyPair The Ed25519 key pair
     * @param peerId The libp2p peer ID (base58 encoded)
     * @return X509Certificate self-signed certificate
     */
    fun generateSelfSignedCertificate(
        keyPair: KeyPair,
        peerId: String
    ): X509Certificate {
        val now = Instant.now()
        val notBefore = Date.from(now)
        // Use 365 days per year for validity period
        val notAfter = Date.from(now.plus(CERTIFICATE_VALIDITY_YEARS * 365, ChronoUnit.DAYS))

        // Create certificate subject with peer ID
        val subject = X500Name("CN=$peerId")
        val issuer = subject // Self-signed

        // Create certificate builder
        val certBuilder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(System.currentTimeMillis()),
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )
            .addExtension(
                org.bouncycastle.asn1.x509.Extension.basicConstraints,
                true,
                BasicConstraints(true)
            )
            .addExtension(
                org.bouncycastle.asn1.x509.Extension.extendedKeyUsage,
                false,
                ExtendedKeyUsage(arrayOf(KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth))
            )

        // Extract raw private key for signing
        // Our custom Ed25519PrivateKey stores raw 32-byte seed
        val rawSeed = if (keyPair.private is com.anyproto.anyfile.data.crypto.Ed25519PrivateKey) {
            // Our custom key class - get the raw seed directly
            (keyPair.private as com.anyproto.anyfile.data.crypto.Ed25519PrivateKey).getRawSeed()
        } else {
            // Fallback: try to extract from encoded format
            val encoded = keyPair.private.encoded
            if (encoded.size == 48) {
                // PKCS#8 encoded - extract raw seed
                PureEd25519.extractRawPrivateKey(encoded)
            } else {
                // Assume raw 32-byte seed already
                encoded
            }
        }

        // Create custom content signer using PureEd25519
        val signer = Ed25519ContentSigner(rawSeed)

        // Build the certificate
        val certHolder = certBuilder.build(signer)

        // Convert to Java X509Certificate
        // On Android, we need to use the standard CertificateFactory instead of
        // Bouncy Castle's JcaX509CertificateConverter which doesn't work properly
        val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
        val certificate = certFactory.generateCertificate(
            java.io.ByteArrayInputStream(certHolder.encoded)
        ) as java.security.cert.X509Certificate

        return certificate
    }

    /**
     * Encode an X509Certificate to PEM format.
     *
     * @param certificate The certificate to encode
     * @return PEM formatted string
     */
    fun encodeToPem(certificate: X509Certificate): String {
        val encoder = java.util.Base64.getMimeEncoder(76, "\n".toByteArray())
        val certBase64 = String(encoder.encode(certificate.encoded))

        return "-----BEGIN CERTIFICATE-----\n$certBase64\n-----END CERTIFICATE-----"
    }
}

/**
 * Custom ContentSigner that uses PureEd25519 for Ed25519 signatures.
 *
 * This implementation bypasses Bouncy Castle's JCA integration and uses
 * Ed25519Signer directly for signing certificate data.
 *
 * Bouncy Castle's ContentSigner interface requires:
 * - getAlgorithmIdentifier(): Returns the signature algorithm identifier
 * - getOutputStream(): Returns an OutputStream that captures data to be signed
 * - getSignature(): Returns the signature after all data has been written
 */
private class Ed25519ContentSigner(
    private val rawSeed: ByteArray
) : ContentSigner {

    private val outputStream = ByteArrayOutputStream()
    private var signature: ByteArray? = null

    override fun getAlgorithmIdentifier() =
        org.bouncycastle.asn1.x509.AlgorithmIdentifier(
            // Ed25519 OID: 1.3.101.112
            org.bouncycastle.asn1.ASN1ObjectIdentifier("1.3.101.112")
        )

    override fun getOutputStream(): OutputStream = outputStream

    override fun getSignature(): ByteArray {
        if (signature == null) {
            // Sign the certificate data using PureEd25519
            val dataToSign = outputStream.toByteArray()
            signature = PureEd25519.signPkcs8(
                // Reconstruct PKCS#8 from raw seed for PureEd25519
                reconstructPkcs8FromRawSeed(rawSeed),
                dataToSign
            )
        }
        return signature ?: byteArrayOf()
    }

    /**
     * Reconstruct PKCS#8 encoded private key from raw 32-byte seed.
     *
     * This is the inverse of PureEd25519.extractRawPrivateKey().
     * We need this because PureEd25519.signPkcs8 expects PKCS#8 format.
     */
    private fun reconstructPkcs8FromRawSeed(rawSeed: ByteArray): ByteArray {
        require(rawSeed.size == 32) {
            "Raw seed must be 32 bytes, got ${rawSeed.size}"
        }

        // PKCS#8 structure for Ed25519:
        // 0x30 0x2E 0x02 0x01 0x00 0x30 0x05 0x06 0x03 0x2B 0x65 0x70
        // 0x04 0x20 0x04 0x14 [32-byte seed] 0xA1 0x01 0x01
        val pkcs8 = ByteArray(48)
        val header = byteArrayOf(
            0x30, 0x2E.toByte(), // SEQUENCE, length 46
            0x02, 0x01, 0x00, // INTEGER 0 (version)
            0x30, 0x05, // SEQUENCE, length 5 (algorithm identifier)
            0x06, 0x03, 0x2B.toByte(), 0x65, 0x70, // OID 1.3.101.112 (Ed25519)
            0x04, 0x20, // OCTET STRING, length 32
            0x04, 0x14.toByte() // OCTET STRING, length 20 (public key placeholder)
        )
        System.arraycopy(header, 0, pkcs8, 0, header.size)
        System.arraycopy(rawSeed, 0, pkcs8, 16, 32)
        // Public key placeholder at the end (not used for signing)
        pkcs8[47] = 0x01

        return pkcs8
    }
}
