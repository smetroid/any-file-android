package com.anyproto.anyfile.data.network.libp2p

import java.net.Socket
import java.security.PrivateKey
import java.security.Principal
import java.security.cert.X509Certificate
import javax.net.ssl.X509KeyManager

/**
 * KeyManager that provides libp2p TLS client certificates derived from Ed25519 keys.
 *
 * libp2p TLS requires the client to present a certificate during TLS handshake.
 * This KeyManager wraps our Ed25519 key pair and presents the generated X.509 certificate.
 *
 * Based on libp2p go-libp2p/p2p/security/tls/identity.go
 *
 * Note: This is named Libp2pTlsKeyManager to avoid conflict with the existing
 * Libp2pKeyManager class which handles Ed25519 key generation and peer ID derivation.
 *
 * Note: We implement X509KeyManager (interface) instead of X509ExtendedKeyManager (abstract class)
 * because on Android, X509ExtendedKeyManager has a protected constructor that cannot be called
 * from Kotlin code. For our purposes, X509KeyManager provides all the methods we need.
 */
class Libp2pTlsKeyManager(
    private val keyPair: java.security.KeyPair,
    private val certificate: X509Certificate,
    private val peerId: String
) : X509KeyManager {

    /**
     * Choose the client alias for the given key type.
     *
     * @param keyType The key type list (e.g., ["RSA"], ["EC"], ["Ed25519"])
     * @param issuers The list of CA certificate subjects
     * @param socket The socket
     * @return The client alias (our peer ID)
     */
    override fun chooseClientAlias(
        keyType: Array<String>,
        issuers: Array<Principal>,
        socket: Socket
    ): String {
        return peerId
    }

    /**
     * Get the certificate chain for the client.
     *
     * @param alias The client alias (peer ID)
     * @return Array containing the certificate (self-signed, so chain length is 1)
     */
    override fun getCertificateChain(alias: String): Array<X509Certificate> {
        if (alias != peerId) {
            return arrayOf()
        }
        return arrayOf(certificate)
    }

    /**
     * Get the private key for the client.
     *
     * @param alias The client alias (peer ID)
     * @return The Ed25519 private key
     */
    override fun getPrivateKey(alias: String): PrivateKey {
        if (alias != peerId) {
            throw IllegalStateException("Unknown alias: $alias")
        }
        return keyPair.private
    }

    // Server-side methods (not used for client connections)

    override fun chooseServerAlias(
        keyType: String,
        issuers: Array<Principal>,
        socket: Socket
    ): String {
        // Not used for outbound connections
        return peerId
    }

    override fun getServerAliases(keyType: String, issuers: Array<Principal>): Array<String> {
        // Not used for outbound connections
        return arrayOf(peerId)
    }

    override fun getClientAliases(keyType: String, issuers: Array<Principal>): Array<String> {
        // Not used for outbound connections
        return arrayOf(peerId)
    }

    /**
     * Create a KeyManagerFactory from this KeyManager.
     *
     * Since KeyManagerFactory is final and cannot be extended, we create
     * a custom factory that provides this KeyManager instance.
     *
     * @return KeyManagerFactory wrapping this KeyManager
     */
    fun toKeyManagerFactory(): Libp2pKeyManagerFactory {
        return Libp2pKeyManagerFactory(this)
    }

    /**
     * Get the certificate (for external access).
     */
    fun getCertificate(): X509Certificate = certificate
}

/**
 * Custom KeyManagerFactory that wraps a Libp2pTlsKeyManager.
 *
 * This factory provides the Libp2pTlsKeyManager as the only KeyManager.
 * Used to integrate with SSLContext.init().
 */
class Libp2pKeyManagerFactory(
    private val keyManager: Libp2pTlsKeyManager
) : javax.net.ssl.KeyManagerFactorySpi() {

    override fun engineInit(keyStore: java.security.KeyStore, password: CharArray) {
        // Not used - we have the key manager already
    }

    override fun engineInit(spec: javax.net.ssl.ManagerFactoryParameters) {
        // Not used - we have the key manager already
    }

    override fun engineGetKeyManagers(): Array<javax.net.ssl.KeyManager> {
        return arrayOf(keyManager)
    }
}
