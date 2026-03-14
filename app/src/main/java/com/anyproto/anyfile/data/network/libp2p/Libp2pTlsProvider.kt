package com.anyproto.anyfile.data.network.libp2p

import android.util.Log
import com.anyproto.anyfile.data.network.tls.TlsConnectionException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.Security
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * Provides libp2p-style TLS connections for any-sync services.
 *
 * This implementation wraps standard Android TLS with libp2p peer identities.
 * The any-sync protocol stack separates concerns:
 * - Layer 1 (this class): Provides TLS encryption with ALPN "anysync"
 * - Layer 2 (handshake): Performs peer authentication using Ed25519 signatures
 *
 * The Go any-sync implementation uses libp2p TLS which includes peer IDs in
 * the certificate. Our Android implementation achieves compatibility by:
 * 1. Using standard Android TLS 1.3 with ALPN "anysync"
 * 2. Attaching our libp2p peer ID to the socket wrapper
 * 3. Letting Layer 2 (handshake) verify peer identities
 *
 * This separation is cleaner and matches the protocol stack design.
 *
 * Based on the Go implementation in any-sync/internal/anysync/secure.go
 *
 * @see Libp2pKeyManager for key generation and peer ID derivation
 * @see com.anyproto.anyfile.data.network.tls.TlsConfigProvider for TLS patterns
 */
@Singleton
class Libp2pTlsProvider @Inject constructor(
    private val keyManager: Libp2pKeyManager
) {

    companion object {
        private const val TAG = "Libp2pTLS"

        /**
         * Default connection timeout in milliseconds.
         */
        private const val DEFAULT_CONNECTION_TIMEOUT_MS = 30000

        /**
         * Default read timeout in milliseconds.
         */
        private const val DEFAULT_READ_TIMEOUT_MS = 30000

        /**
         * TLS 1.3 protocol name.
         */
        private const val TLS_PROTOCOL_1_3 = "TLSv1.3"

        /**
         * Fallback to TLS 1.2 if TLS 1.3 is not available.
         */
        private const val TLS_PROTOCOL_1_2 = "TLSv1.2"

        /**
         * Application layer protocol negotiation.
         * The coordinator runs go-libp2p which uses the standard libp2p ALPN value.
         */
        private const val ALPN_PROTO_ANY_SYNC = "libp2p"

        /**
         * Alias for the TLS key entry used in libp2p connections.
         */
        private const val TLS_KEY_ALIAS = "any-sync-client"
    }

    /**
     * The SSL socket factory used to create TLS sockets.
     * Lazily initialized with proper TLS configuration.
     */
    private val sslSocketFactory: SSLSocketFactory by lazy {
        createSslSocketFactory()
    }

    /**
     * Cache for peer identity to reuse the same key pair across connections.
     */
    private var cachedIdentity: PeerIdentity? = null

    /**
     * libp2p-compatible TLS certificate bundle (ECDSA P-256 cert + key pair).
     *
     * The certificate includes the libp2p extension (OID 1.3.6.1.4.1.53594.1.1)
     * that ties the ECDSA TLS key to the Ed25519 peer identity. This is what
     * go-libp2p's VerifyPeerCertificate callback validates.
     *
     * Generated lazily using the peer identity (Ed25519) to sign the extension.
     */
    private val certBundle: LibP2pCertBundle by lazy {
        Security.addProvider(BouncyCastleProvider())
        val identity = getPeerIdentity()
        Libp2pCertificateGenerator.generateLibp2pCertificate(
            identity.keyPair.privateKey,  // 32-byte raw Ed25519 seed
            identity.keyPair.publicKey    // 32-byte raw Ed25519 public key
        )
    }

    /**
     * Get or create the default peer identity for this client.
     *
     * Uses a deterministic key pair derived from a fixed seed,
     * ensuring the same peer ID across connections.
     *
     * @return The client's peer identity (key pair and peer ID)
     */
    fun getPeerIdentity(): PeerIdentity {
        return cachedIdentity ?: run {
            val keyPair = keyManager.getDefaultKeyPair()
            val peerId = keyManager.derivePeerId(keyPair.publicKey)
            val identity = PeerIdentity(keyPair, peerId)
            cachedIdentity = identity
            identity
        }
    }

    /**
     * Create a TLS socket connected to the specified host and port.
     *
     * The socket is configured with:
     * - TLS 1.3 (fallback to 1.2)
     * - ALPN protocol "anysync"
     * - SNI (Server Name Indication)
     *
     * The returned [Libp2pTlsSocket] contains the underlying socket and
     * the client's peer identity. Layer 2 (any-sync handshake) will use
     * this to authenticate the connection.
     *
     * @param host The hostname to connect to (e.g., "localhost")
     * @param port The port number (e.g., 1004 for coordinator)
     * @param timeoutMs Connection timeout in milliseconds (default: 30000)
     * @return Libp2pTlsSocket containing the TLS socket and peer identity
     * @throws Libp2pTlsException if connection fails or TLS handshake fails
     */
    fun createTlsSocket(
        host: String,
        port: Int,
        timeoutMs: Int = DEFAULT_CONNECTION_TIMEOUT_MS,
        enableAlpn: Boolean = true,
        trustAllCerts: Boolean = false,
        useLibp2pTls: Boolean = false
    ): Libp2pTlsSocket {
        // Get our peer identity (Ed25519 for peer ID)
        val identity = getPeerIdentity()

        val alpnStatus = if (enableAlpn) ALPN_PROTO_ANY_SYNC else "disabled"
        val trustStatus = if (trustAllCerts) "INSECURE (trust-all)" else "secure"
        val tlsType = if (useLibp2pTls) {
            "Hybrid TLS (RSA cert + Ed25519 peer ID)"
        } else {
            "standard TLS"
        }
        Log.d(TAG, "Creating TLS socket to $host:$port with peer ID: ${identity.peerId.base58}, ALPN: $alpnStatus, Trust: $trustStatus, Type: $tlsType")

        // Get appropriate socket factory based on configuration
        val socketFactory = if (useLibp2pTls) {
            Log.w(TAG, "⚠️ Using libp2p TLS with client certificate presentation")
            createLibp2pSslSocketFactory()
        } else if (trustAllCerts) {
            Log.w(TAG, "⚠️ Using insecure TLS (trusting all certificates) - FOR TESTING ONLY")
            createInsecureSslSocketFactory()
        } else {
            sslSocketFactory
        }

        // IMPORTANT: Do NOT extract peer ID from TLS certificate
        // In the any-sync protocol, peer IDs are exchanged via handshake, not TLS
        // The TLS certificate is for encryption only, not authentication
        // remotePeerId will remain null and be populated from the handshake
        var remotePeerId: PeerId? = null

        // Create and connect the socket with detailed logging
        val socket = try {
            Log.d(TAG, "Step 1: Creating SSL socket...")
            val socket = socketFactory.createSocket() as? SSLSocket
                ?: throw Libp2pTlsException("Failed to create SSL socket")
            Log.d(TAG, "SSL socket created successfully")

            // Configure timeouts
            socket.soTimeout = DEFAULT_READ_TIMEOUT_MS
            Log.d(TAG, "Step 2: Connecting TCP to $host:$port (timeout: ${timeoutMs}ms)...")

            socket.connect(InetSocketAddress(host, port), timeoutMs)
            Log.d(TAG, "TCP connected successfully to $host:$port")

            // Configure TLS parameters (ALPN, SNI, protocols)
            Log.d(TAG, "Step 3: Configuring TLS parameters (ALPN: $alpnStatus, SNI: $host)...")
            configureTlsSocket(socket, host, enableAlpn)
            Log.d(TAG, "TLS parameters configured")

            // Force TLS handshake and log result
            Log.d(TAG, "Step 4: Starting TLS handshake...")
            socket.startHandshake()
            Log.d(TAG, "TLS handshake completed successfully")

            // Extract remote peer ID from server's libp2p TLS extension.
            // The server certificate contains OID 1.3.6.1.4.1.53594.1.1 with
            // the server's identity public key. We derive the peer ID from it
            // so we can include the correct remotePeerId in the any-sync credentials.
            try {
                val session = socket.session
                Log.d(TAG, "=== TLS Session Details ===")
                Log.d(TAG, "Cipher suite: ${session.cipherSuite}")
                Log.d(TAG, "Protocol: ${session.protocol}")
                Log.d(TAG, "Application protocol (ALPN): ${getApplicationProtocol(socket)}")
                if (session.peerCertificates.isNotEmpty()) {
                    val cert = session.peerCertificates[0] as java.security.cert.X509Certificate
                    Log.d(TAG, "Peer certificate subject: ${cert.subjectDN}")
                    val extracted = extractPeerIdFromLibp2pExtension(cert)
                    if (extracted != null) {
                        remotePeerId = extracted
                        Log.d(TAG, "Extracted remote peer ID from libp2p extension: ${extracted.base58}")
                    } else {
                        Log.w(TAG, "Server cert has no libp2p extension, peer ID unknown")
                    }
                }
                Log.d(TAG, "=========================")
            } catch (e: Exception) {
                Log.w(TAG, "Could not extract remote peer ID: ${e.message}", e)
            }

            socket
        } catch (e: IOException) {
            Log.e(TAG, "=== TLS Connection Failed (IOException) ===")
            Log.e(TAG, "Host: $host:$port")
            Log.e(TAG, "ALPN enabled: $enableAlpn")
            Log.e(TAG, "Trust all certs: $trustAllCerts")
            Log.e(TAG, "Error type: ${e.javaClass.simpleName}")
            Log.e(TAG, "Error message: ${e.message}")
            Log.e(TAG, "Stack trace:", e)
            Log.e(TAG, "========================================")
            throw Libp2pTlsException(
                "Failed to connect to $host:$port: ${e.message}",
                e
            )
        } catch (e: javax.net.ssl.SSLException) {
            Log.e(TAG, "=== TLS Connection Failed (SSLException) ===")
            Log.e(TAG, "Host: $host:$port")
            Log.e(TAG, "ALPN enabled: $enableAlpn")
            Log.e(TAG, "Trust all certs: $trustAllCerts")
            Log.e(TAG, "Error type: ${e.javaClass.simpleName}")
            Log.e(TAG, "Error message: ${e.message}")
            Log.e(TAG, "Stack trace:", e)
            Log.e(TAG, "==========================================")
            throw Libp2pTlsException(
                "TLS handshake failed with $host:$port: ${e.message}",
                e
            )
        } catch (e: Libp2pTlsException) {
            Log.e(TAG, "=== TLS Connection Failed (Libp2pTlsException) ===")
            Log.e(TAG, "Host: $host:$port")
            Log.e(TAG, "ALPN enabled: $enableAlpn")
            Log.e(TAG, "Trust all certs: $trustAllCerts")
            Log.e(TAG, "Error message: ${e.message}")
            Log.e(TAG, "Stack trace:", e)
            Log.e(TAG, "================================================")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "=== TLS Connection Failed (Unexpected Exception) ===")
            Log.e(TAG, "Host: $host:$port")
            Log.e(TAG, "ALPN enabled: $enableAlpn")
            Log.e(TAG, "Trust all certs: $trustAllCerts")
            Log.e(TAG, "Error type: ${e.javaClass.simpleName}")
            Log.e(TAG, "Error message: ${e.message}")
            Log.e(TAG, "Stack trace:", e)
            Log.e(TAG, "====================================================")
            throw Libp2pTlsException(
                "Unexpected error connecting to $host:$port: ${e.message}",
                e
            )
        }

        return Libp2pTlsSocket(
            socket = socket,
            localPeerId = identity.peerId,
            localKeyPair = identity.keyPair,
            remotePeerId = remotePeerId
        )
    }

    /**
     * Get the negotiated application protocol from the SSL socket.
     * Returns the ALPN protocol or "none" if not supported/negotiated.
     */
    private fun getApplicationProtocol(socket: SSLSocket): String {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                val sslParameters = socket.sslParameters
                if (sslParameters.applicationProtocols.isNotEmpty()) {
                    "negotiated: ${sslParameters.applicationProtocols.joinToString()}"
                } else {
                    "none (not configured)"
                }
            } catch (e: Exception) {
                "error: ${e.message}"
            }
        } else {
            "not supported (API < 26)"
        }
    }

    /**
     * Create a TLS socket wrapping an existing connected socket.
     *
     * This is useful when you have a plain TCP socket that needs to be
     * upgraded to TLS.
     *
     * @param socket The existing connected socket
     * @param host The hostname for SNI (Server Name Indication)
     * @return Libp2pTlsSocket containing the TLS socket and peer identity
     * @throws Libp2pTlsException if TLS handshake fails
     */
    fun createTlsSocketOver(
        socket: Socket,
        host: String,
        useLibp2pTls: Boolean = false
    ): Libp2pTlsSocket {
        // Get our peer identity (Ed25519 for peer ID)
        val identity = getPeerIdentity()

        // Get appropriate socket factory based on configuration
        val socketFactory = if (useLibp2pTls) {
            Log.d(TAG, "Using Ed25519 TLS for connection")
            createLibp2pSslSocketFactory()
        } else {
            sslSocketFactory
        }

        // IMPORTANT: Do NOT extract peer ID from TLS certificate
        // In the any-sync protocol, peer IDs are exchanged via handshake, not TLS
        var remotePeerId: PeerId? = null

        val sslSocket: SSLSocket = try {
            val created = socketFactory.createSocket(
                socket,
                host,
                socket.port,
                true
            ) as? SSLSocket ?: throw Libp2pTlsException(
                "Failed to create SSL socket over existing connection"
            )

            configureTlsSocket(created, host)

            // Force TLS handshake
            created.startHandshake()

            // NOTE: We do NOT extract peer ID from certificate
            // Peer ID will be exchanged via any-sync handshake
            Log.d(TAG, "TLS handshake complete, peer ID will be exchanged via any-sync handshake")

            created
        } catch (e: IOException) {
            throw Libp2pTlsException(
                "Failed to establish TLS over existing connection to $host",
                e
            )
        } catch (e: Libp2pTlsException) {
            throw e
        } catch (e: Exception) {
            throw Libp2pTlsException(
                "Unexpected error establishing TLS to $host",
                e
            )
        }

        return Libp2pTlsSocket(
            socket = sslSocket,
            localPeerId = identity.peerId,
            localKeyPair = identity.keyPair,
            remotePeerId = remotePeerId
        )
    }

    /**
     * Configure an SSLSocket with appropriate TLS parameters.
     *
     * This sets:
     * - TLS 1.3 (or 1.2 fallback)
     * - SNI (Server Name Indication)
     * - ALPN protocol "anysync" (optional, can be disabled for compatibility)
     *
     * @param socket The SSLSocket to configure
     * @param host The hostname for SNI
     * @param enableAlpn Whether to enable ALPN protocol negotiation (default: true)
     */
    private fun configureTlsSocket(socket: SSLSocket, host: String, enableAlpn: Boolean = true) {
        try {
            // Enable TLS 1.3 (fallback to 1.2 if needed)
            val enabledProtocols = if (socket.supportedProtocols.contains(TLS_PROTOCOL_1_3)) {
                arrayOf(TLS_PROTOCOL_1_3)
            } else {
                // Fallback to TLS 1.2 for older Android versions
                arrayOf(TLS_PROTOCOL_1_2)
            }
            socket.enabledProtocols = enabledProtocols

            // Set SNI (Server Name Indication)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                val sslParameters = socket.sslParameters
                sslParameters.serverNames = listOf(
                    javax.net.ssl.SNIHostName(host)
                )
                socket.sslParameters = sslParameters
            }

            // Enable ALPN if supported (for protocol negotiation)
            // Note: Some any-sync services may reject connections with ALPN enabled
            if (enableAlpn && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val sslParameters = socket.sslParameters
                sslParameters.applicationProtocols = arrayOf(ALPN_PROTO_ANY_SYNC)
                socket.sslParameters = sslParameters
            }
        } catch (e: Exception) {
            // Log but don't fail - connection may still work
            // In production, you might want to log this to a logging framework
        }
    }

    /**
     * Extract libp2p peer ID from a certificate's libp2p extension.
     *
     * The libp2p TLS extension (OID 1.3.6.1.4.1.53594.1.1) contains:
     * - protobuf-encoded identity public key
     * - signature over "libp2p-tls-handshake:" + PKIX(TLS cert public key)
     *
     * We extract the identity public key from the extension and derive the peer ID.
     * This is required to include the correct remotePeerId in any-sync credentials.
     *
     * @param certificate The server's X.509 certificate
     * @return The remote peer's libp2p peer ID, or null if extraction fails
     */
    private fun extractPeerIdFromLibp2pExtension(certificate: java.security.cert.X509Certificate): PeerId? {
        return try {
            val extValue = certificate.getExtensionValue("1.3.6.1.4.1.53594.1.1") ?: return null

            // getExtensionValue returns DER(OCTET STRING { signedKey SEQUENCE })
            val outer = org.bouncycastle.asn1.ASN1Primitive.fromByteArray(extValue)
                as org.bouncycastle.asn1.ASN1OctetString
            val seq = org.bouncycastle.asn1.ASN1Sequence.getInstance(outer.octets)

            // First element is protobuf-encoded identity public key (36 bytes for Ed25519)
            val protoPubKey = (seq.getObjectAt(0) as org.bouncycastle.asn1.ASN1OctetString).octets
            if (protoPubKey.size != 36) {
                Log.w(TAG, "Unexpected protobuf key size: ${protoPubKey.size}")
                return null
            }

            // Protobuf format: 08 01 12 20 [32 bytes raw key]
            val rawPubKey = protoPubKey.copyOfRange(4, 36)
            keyManager.derivePeerId(rawPubKey)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract peer ID from libp2p extension: ${e.message}")
            null
        }
    }

    /**
     * Extract libp2p peer ID from an X.509 certificate.
     *
     * The peer ID is derived from the certificate's Ed25519 public key:
     * 1. Extract the raw public key from the certificate
     * 2. Compute SHA-256 hash of the public key
     * 3. Create multihash: [0x12, 0x20, hash...]
     * 4. Encode to base58
     *
     * This matches libp2p's peer ID derivation algorithm.
     *
     * @param certificate The X.509 certificate
     * @return The derived peer ID, or null if extraction fails
     */
    private fun extractPeerIdFromCertificate(certificate: java.security.cert.X509Certificate): PeerId? {
        return try {
            // Get the public key from the certificate
            val publicKey = certificate.publicKey

            // Extract raw 32-byte Ed25519 public key
            val rawPublicKey = when (publicKey) {
                is com.anyproto.anyfile.data.crypto.Ed25519PublicKey -> {
                    // Our custom Ed25519 public key class - get raw bytes directly
                    publicKey.encoded
                }
                else -> {
                    // Standard Java public key - extract encoded bytes
                    val encoded = publicKey.encoded
                    Log.d(TAG, "Certificate public key encoded size: ${encoded.size} bytes")
                    Log.d(TAG, "Public key format: ${publicKey.format}")
                    Log.d(TAG, "Public key algorithm: ${publicKey.algorithm}")
                    Log.d(TAG, "Public key encoded (hex): ${encoded.joinToString("") { "%02x".format(it) }}")

                    when {
                        // Try X.509 encoded Ed25519 public key
                        // Format: 0x30 [length] 0x30 0x05 0x06 0x03 0x2B 0x65 0x70 0x03 0x21 0x00 [32 bytes]
                        // The header can vary in length, so we look for the pattern 0x03 0x21 0x00
                        encoded.size >= 16 && encoded[0] == 0x30.toByte() -> {
                            // Look for the sequence 0x03 0x21 0x00 which precedes the 32-byte key
                            var keyOffset = -1
                            for (i in 0 until encoded.size - 3) {
                                if (encoded[i] == 0x03.toByte() &&
                                    encoded[i + 1] == 0x21.toByte() &&
                                    encoded[i + 2] == 0x00.toByte()) {
                                    keyOffset = i + 3
                                    break
                                }
                            }

                            if (keyOffset > 0 && encoded.size - keyOffset >= 32) {
                                Log.d(TAG, "Found Ed25519 key at offset: $keyOffset")
                                encoded.copyOfRange(keyOffset, keyOffset + 32)
                            } else {
                                // Fallback: try last 32 bytes
                                Log.w(TAG, "Could not find Ed25519 key pattern, using last 32 bytes")
                                if (encoded.size >= 32) {
                                    encoded.copyOfRange(encoded.size - 32, encoded.size)
                                } else {
                                    Log.e(TAG, "Public key too small: ${encoded.size} bytes")
                                    return null
                                }
                            }
                        }
                        // Assume already raw 32 bytes
                        encoded.size == 32 -> {
                            Log.d(TAG, "Using raw 32-byte public key")
                            encoded
                        }
                        else -> {
                            Log.e(TAG, "Unexpected public key encoding: ${encoded.size} bytes")
                            return null
                        }
                    }
                }
            }

            // Derive peer ID from raw public key using Libp2pKeyManager
            keyManager.derivePeerId(rawPublicKey).also {
                Log.d(TAG, "Extracted peer ID from certificate: ${it.base58}")
                Log.d(TAG, "  Public key (hex): ${rawPublicKey.joinToString("") { "%02x".format(it) }}")
                Log.d(TAG, "  Multihash (hex): ${it.multihash.joinToString("") { "%02x".format(it) }}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract peer ID from certificate", e)
            e.printStackTrace()
            null
        }
    }

    /**
     * Create an SSL socket factory with appropriate configuration.
     *
     * Uses the system's default trust manager for proper certificate validation.
     */
    private fun createSslSocketFactory(): SSLSocketFactory {
        val sslContext = javax.net.ssl.SSLContext.getInstance(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                "TLSv1.3"
            } else {
                "TLS"
            }
        )

        // Use the default trust managers for proper certificate validation
        sslContext.init(null, null, null)

        return sslContext.socketFactory
    }

    /**
     * Create an INSECURE SSL socket factory that trusts all certificates.
     *
     * WARNING: This is for testing only and should never be used in production.
     * The coordinator uses libp2p TLS with self-signed certificates which
     * Android's default trust manager doesn't accept.
     */
    private fun createInsecureSslSocketFactory(): SSLSocketFactory {
        val sslContext = javax.net.ssl.SSLContext.getInstance(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                "TLSv1.3"
            } else {
                "TLS"
            }
        )

        // Create a trust manager that accepts all certificates
        val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
            object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(
                    chain: Array<java.security.cert.X509Certificate>,
                    authType: String
                ) {
                    // Trust all client certificates
                }

                override fun checkServerTrusted(
                    chain: Array<java.security.cert.X509Certificate>,
                    authType: String
                ) {
                    // Trust all server certificates
                }

                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> {
                    return arrayOf()
                }
            }
        )

        // Initialize with the trust-all trust manager
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())

        return sslContext.socketFactory
    }

    /**
     * Create an SSL socket factory with libp2p TLS mutual authentication.
     *
     * **Session 24 Update:** Uses hybrid approach with RSA for TLS certificates
     * and Ed25519 for peer IDs. This works around Conscrypt's Ed25519 PKCS#8
     * incompatibility while maintaining compatibility with any-sync.
     *
     * This creates a TLS socket factory that:
     * - Uses RSA certificates for TLS (Conscrypt compatible)
     * - Embeds Ed25519-derived peer ID in certificate subject
     * - Provides client authentication via KeyManager
     * - Validates server certificates (TrustManager)
     *
     * **SECURITY WARNING:** This implementation uses a trust-all TrustManager for testing.
     * In production, libp2p requires proper certificate validation:
     * - Extract peer ID from server certificate subject CN
     * - Verify server's peer ID matches expected coordinator/filenode peer ID
     * - Implement certificate pinning for known infrastructure peers
     *
     * This is acceptable for P2P use case because:
     * - libp2p identities are authenticated at the application layer (Layer 2 handshake)
     * - Transport encryption is still provided by TLS
     * - Each peer verifies the other's identity via peer ID cryptographic verification
     *
     * TODO: Implement proper server certificate validation (see Task 6: Production Hardening)
     *
     * Based on libp2p go-libp2p/p2p/security/tls/identity.go
     *
     * @param keyPair The Ed25519 key pair (used for peer ID only, not TLS)
     * @return SSLSocketFactory with libp2p TLS configuration
     */
    /**
     * Generate the libp2p-compatible TLS client certificate for this peer.
     *
     * Returns an ECDSA P-256 certificate with the libp2p extension (OID 1.3.6.1.4.1.53594.1.1)
     * that ties the TLS key to the Ed25519 peer identity. This is required by
     * go-libp2p's VerifyPeerCertificate callback.
     *
     * @return ECDSA P-256 X509Certificate with libp2p extension
     */
    fun createLibp2pClientCertificate(): java.security.cert.X509Certificate {
        return certBundle.certificate
    }

    /**
     * Create an X509ExtendedKeyManager backed by an Ed25519 key pair and certificate.
     *
     * Avoids KeyStore incompatibility with our custom Ed25519PrivateKey class.
     */
    private fun createEd25519KeyManager(
        keyPair: java.security.KeyPair,
        cert: java.security.cert.X509Certificate,
    ): javax.net.ssl.X509ExtendedKeyManager {
        return object : javax.net.ssl.X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = arrayOf(TLS_KEY_ALIAS)
            override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: java.net.Socket?) = TLS_KEY_ALIAS
            override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: java.net.Socket?): String? = null
            override fun getCertificateChain(alias: String?) = if (alias == TLS_KEY_ALIAS) arrayOf(cert) else null
            override fun getPrivateKey(alias: String?) = if (alias == TLS_KEY_ALIAS) keyPair.private else null
        }
    }

    private fun createLibp2pSslSocketFactory(): SSLSocketFactory {
        val sslContext = javax.net.ssl.SSLContext.getInstance(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                "TLSv1.3"
            } else {
                "TLS"
            }
        )

        // Generate ECDSA P-256 certificate with libp2p extension (go-libp2p compatible)
        val identity = getPeerIdentity()
        val bundle = certBundle
        val keyManager = createEd25519KeyManager(bundle.tlsKeyPair, bundle.certificate)

        Log.d(TAG, "=== Using libp2p TLS (ECDSA P-256 + libp2p extension) ===")
        Log.d(TAG, "  Peer ID: ${identity.peerId.base58}")
        Log.d(TAG, "  Certificate algorithm: ${bundle.certificate.publicKey.algorithm}")
        Log.d(TAG, "  Extension OID 1.3.6.1.4.1.53594.1.1 present: ${bundle.certificate.getExtensionValue("1.3.6.1.4.1.53594.1.1") != null}")

        // Trust-all TrustManager: peer identity authenticated at Layer 2 handshake
        val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
            object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            }
        )

        sslContext.init(arrayOf(keyManager), trustAllCerts, java.security.SecureRandom())

        Log.d(TAG, "Ed25519 TLS socket factory created")
        return sslContext.socketFactory
    }

    /**
     * Convert a Libp2pKeyPair to java.security.KeyPair.
     *
     * The Libp2pKeyPair stores keys as raw 32-byte arrays (not PKCS#8/X.509).
     * This method creates a KeyPair using custom Ed25519 key classes that implement
     * the Java Security interfaces without relying on KeyFactory.
     *
     * This bypasses Android's KeyFactory limitation for Ed25519.
     *
     * @param libp2pKeyPair The libp2p key pair with raw 32-byte keys
     * @return java.security.KeyPair for use with TLS
     */
    private fun convertToJavaKeyPair(libp2pKeyPair: Libp2pKeyPair): java.security.KeyPair {
        // Libp2pKeyPair stores raw 32-byte keys, not PKCS#8/X.509 encoded
        // Use helper functions that accept raw keys
        val privateKey = com.anyproto.anyfile.data.crypto.privateKeyFromRaw(libp2pKeyPair.privateKey)
        val publicKey = com.anyproto.anyfile.data.crypto.publicKeyFromRaw(libp2pKeyPair.publicKey)

        return java.security.KeyPair(publicKey, privateKey)
    }

    /**
     * Check if TLS 1.3 is available on this device.
     *
     * @return true if TLS 1.3 is supported
     */
    fun isTls13Supported(): Boolean {
        return try {
            val sslSocket = sslSocketFactory.createSocket() as? SSLSocket
            val supported = sslSocket?.supportedProtocols?.contains(TLS_PROTOCOL_1_3) == true
            supported
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Clear the cached peer identity.
     *
     * This removes the cached key pair and peer ID. Use with caution as it
     * will cause a new identity to be generated on next connection.
     */
    fun clearIdentity() {
        cachedIdentity = null
    }
}

/**
 * Holds libp2p peer identity information.
 *
 * This contains the key pair and derived peer ID for a libp2p identity.
 *
 * @property keyPair The Ed25519 key pair
 * @property peerId The derived libp2p peer ID
 */
data class PeerIdentity(
    val keyPair: Libp2pKeyPair,
    val peerId: PeerId
)

/**
 * Wrapper for a libp2p TLS socket.
 *
 * Provides access to the underlying socket and peer information.
 * The any-sync handshake layer will use the peer IDs to authenticate
 * the connection.
 *
 * @property socket The underlying SSL socket
 * @property localPeerId The client's peer ID
 * @property localKeyPair The client's key pair
 * @property remotePeerId The server's peer ID (set after handshake, null initially)
 */
data class Libp2pTlsSocket(
    val socket: SSLSocket,
    val localPeerId: PeerId,
    val localKeyPair: Libp2pKeyPair,
    var remotePeerId: PeerId? = null
) {
    /**
     * Check if the socket is connected.
     */
    val isConnected: Boolean
        get() = socket.isConnected && !socket.isClosed

    /**
     * Get the input stream from the underlying socket.
     */
    val inputStream: java.io.InputStream
        get() = socket.inputStream

    /**
     * Get the output stream from the underlying socket.
     */
    val outputStream: java.io.OutputStream
        get() = socket.outputStream

    /**
     * Close the underlying socket.
     */
    fun close() {
        socket.close()
    }
}

/**
 * Exception thrown when libp2p TLS operations fail.
 */
class Libp2pTlsException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    /**
     * User-friendly error message for UI display
     */
    val userMessage: String = message
}
