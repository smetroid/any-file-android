package com.anyproto.anyfile.data.network.libp2p

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
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
        val certBuilder = JcaX509v3CertificateBuilder(
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

        // Sign certificate with Ed25519 private key
        val signer = JcaContentSignerBuilder("Ed25519")
            .setProvider("BC")
            .build(keyPair.private)

        val certHolder = certBuilder.build(signer)

        // Convert to Java X509Certificate
        val certConverter = JcaX509CertificateConverter()
            .setProvider("BC")
        val certificate = certConverter.getCertificate(certHolder)

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
