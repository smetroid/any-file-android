package com.anyproto.anyfile.data.network.libp2p

import com.anyproto.anyfile.data.crypto.PureEd25519
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date

/**
 * Generates X.509 certificates for libp2p TLS.
 *
 * The primary method is [generateLibp2pCertificate] which produces a certificate
 * compatible with go-libp2p's TLS security protocol:
 *
 * - TLS certificate uses ECDSA P-256 (standard, supported by Android Conscrypt)
 * - Certificate includes libp2p extension (OID 1.3.6.1.4.1.53594.1.1) containing:
 *   - Protobuf-encoded Ed25519 identity public key
 *   - Ed25519 signature over "libp2p-tls-handshake:" + PKIX(ECDSA cert public key)
 *
 * This matches go-libp2p/p2p/security/tls/crypto.go's keyToCertificate() and
 * GenerateSignedExtension() functions exactly.
 *
 * Based on libp2p spec: https://github.com/libp2p/specs/blob/master/tls/tls.md
 */
object Libp2pCertificateGenerator {

    /**
     * OID for the libp2p TLS extension: 1.3.6.1.4.1.53594.1.1
     * (prefix 1.3.6.1.4.1.53594 + suffix 1.1)
     */
    private const val LIBP2P_EXTENSION_OID = "1.3.6.1.4.1.53594.1.1"

    /**
     * Prefix for the libp2p certificate signature.
     * The identity key signs this prefix + PKIX(TLS cert public key).
     */
    private const val CERTIFICATE_PREFIX = "libp2p-tls-handshake:"

    private const val CERTIFICATE_VALIDITY_YEARS = 1L

    init {
        // Register BouncyCastle as security provider
        Security.addProvider(BouncyCastleProvider())
    }

    /**
     * Generate a libp2p-compatible TLS certificate bundle.
     *
     * Creates an ECDSA P-256 TLS certificate with the libp2p extension that
     * cryptographically ties the TLS key to the Ed25519 libp2p identity.
     *
     * This is what go-libp2p's VerifyPeerCertificate callback expects.
     *
     * @param identityPrivKey 32-byte raw Ed25519 private key seed
     * @param identityPubKey 32-byte raw Ed25519 public key
     * @return LibP2pCertBundle containing the certificate and ECDSA TLS key pair
     */
    fun generateLibp2pCertificate(
        identityPrivKey: ByteArray,
        identityPubKey: ByteArray
    ): LibP2pCertBundle {
        require(identityPrivKey.size == 32) { "Ed25519 private key must be 32 bytes" }
        require(identityPubKey.size == 32) { "Ed25519 public key must be 32 bytes" }

        val now = Instant.now()
        // Match go-libp2p: start 1h ago to handle clock skew, valid 100 years
        val notBefore = Date.from(now.minus(1, ChronoUnit.HOURS))
        val notAfter = Date.from(now.plus(100L * 365, ChronoUnit.DAYS))

        // Generate ECDSA P-256 key pair for the TLS certificate.
        // Android Conscrypt supports ECDSA natively and can use these keys for TLS signing.
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val ecdsaKeyPair = kpg.generateKeyPair()

        // Get PKIX (SubjectPublicKeyInfo) encoding of the ECDSA cert public key.
        // go-libp2p uses x509.MarshalPKIXPublicKey(cert.PublicKey) for verification —
        // this matches Java's PublicKey.getEncoded() for standard EC keys.
        val pkixEcdsaPubKey = ecdsaKeyPair.public.encoded

        // Build protobuf-encoded Ed25519 identity public key (36 bytes).
        // Proto3 PublicKey { KeyType Type = 1; bytes Data = 2; }
        // Ed25519 = KeyType 1, Data = 32-byte raw public key
        val protoPubKey = ByteArray(36)
        protoPubKey[0] = 0x08; protoPubKey[1] = 0x01  // field 1 (Type), varint 1 (Ed25519)
        protoPubKey[2] = 0x12; protoPubKey[3] = 0x20  // field 2 (Data), length 32
        System.arraycopy(identityPubKey, 0, protoPubKey, 4, 32)

        // Sign "libp2p-tls-handshake:" + PKIX(ECDSA cert pubkey) with identity Ed25519 key.
        val dataToSign = CERTIFICATE_PREFIX.toByteArray(Charsets.UTF_8) + pkixEcdsaPubKey
        val privateKeyParams = Ed25519PrivateKeyParameters(identityPrivKey)
        val edSigner = Ed25519Signer()
        edSigner.init(true, privateKeyParams)
        edSigner.update(dataToSign, 0, dataToSign.size)
        val signature = edSigner.generateSignature()  // 64 bytes

        // Build ASN1 SignedKey: SEQUENCE { OCTET STRING(pubKey), OCTET STRING(signature) }
        // This matches Go's signedKey struct serialized by encoding/asn1.
        val signedKeyAsn1 = DERSequence(arrayOf(
            DEROctetString(protoPubKey),
            DEROctetString(signature)
        ))

        // Build certificate with libp2p extension.
        // Subject/issuer use a simple CN (matching go-libp2p's certTemplate which uses serial number).
        val subject = X500Name("CN=libp2p")
        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(System.currentTimeMillis()),
            notBefore,
            notAfter,
            subject,
            ecdsaKeyPair.public
        ).addExtension(
            ASN1ObjectIdentifier(LIBP2P_EXTENSION_OID),
            false,  // not critical
            signedKeyAsn1
        )

        // Sign certificate with ECDSA P-256 using BC.
        val contentSigner = JcaContentSignerBuilder("SHA256withECDSA")
            .setProvider(BouncyCastleProvider())
            .build(ecdsaKeyPair.private)
        val certHolder = certBuilder.build(contentSigner)

        // Convert to Java X509Certificate using CertificateFactory (works on Android).
        val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
        val certificate = certFactory.generateCertificate(
            java.io.ByteArrayInputStream(certHolder.encoded)
        ) as java.security.cert.X509Certificate

        return LibP2pCertBundle(certificate, ecdsaKeyPair)
    }

    /**
     * Generate a self-signed X.509 certificate from an Ed25519 key pair.
     *
     * Legacy method kept for backward compatibility with existing tests.
     * For libp2p TLS, use [generateLibp2pCertificate] instead.
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
        val notAfter = Date.from(now.plus(CERTIFICATE_VALIDITY_YEARS * 365, ChronoUnit.DAYS))

        val subject = X500Name("CN=$peerId")
        val issuer = subject

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

        // Extract raw 32-byte seed from private key for signing
        val rawSeed = if (keyPair.private is com.anyproto.anyfile.data.crypto.Ed25519PrivateKey) {
            (keyPair.private as com.anyproto.anyfile.data.crypto.Ed25519PrivateKey).getRawSeed()
        } else {
            val encoded = keyPair.private.encoded
            if (encoded.size >= 48) {
                PureEd25519.extractRawPrivateKey(encoded)
            } else {
                encoded
            }
        }

        val signer = Ed25519ContentSigner(rawSeed)
        val certHolder = certBuilder.build(signer)

        val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
        val certificate = certFactory.generateCertificate(
            java.io.ByteArrayInputStream(certHolder.encoded)
        ) as java.security.cert.X509Certificate

        return certificate
    }

    /**
     * Encode an X509Certificate to PEM format.
     */
    fun encodeToPem(certificate: X509Certificate): String {
        val encoder = java.util.Base64.getMimeEncoder(76, "\n".toByteArray())
        val certBase64 = String(encoder.encode(certificate.encoded))
        return "-----BEGIN CERTIFICATE-----\n$certBase64\n-----END CERTIFICATE-----"
    }
}

/**
 * Bundle containing a libp2p TLS certificate and the corresponding ECDSA key pair.
 *
 * The certificate is an ECDSA P-256 self-signed cert with the libp2p extension.
 * The tlsKeyPair is the ECDSA key used for TLS — it must be passed to the key manager.
 */
data class LibP2pCertBundle(
    val certificate: X509Certificate,
    val tlsKeyPair: KeyPair
)

/**
 * Custom ContentSigner that uses Ed25519Signer directly.
 * Used only by the legacy generateSelfSignedCertificate method.
 */
private class Ed25519ContentSigner(
    private val rawSeed: ByteArray
) : ContentSigner {

    private val outputStream = ByteArrayOutputStream()
    private var signature: ByteArray? = null

    override fun getAlgorithmIdentifier() =
        org.bouncycastle.asn1.x509.AlgorithmIdentifier(
            org.bouncycastle.asn1.ASN1ObjectIdentifier("1.3.101.112")
        )

    override fun getOutputStream(): OutputStream = outputStream

    override fun getSignature(): ByteArray {
        if (signature == null) {
            val dataToSign = outputStream.toByteArray()
            val privateKeyParams = Ed25519PrivateKeyParameters(rawSeed)
            val signer = Ed25519Signer()
            signer.init(true, privateKeyParams)
            signer.update(dataToSign, 0, dataToSign.size)
            signature = signer.generateSignature()
        }
        return signature ?: byteArrayOf()
    }
}
