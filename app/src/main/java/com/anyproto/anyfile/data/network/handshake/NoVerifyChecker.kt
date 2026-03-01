package com.anyproto.anyfile.data.network.handshake

import com.anyproto.anyfile.data.network.libp2p.PeerId

/**
 * Credential checker for SkipVerify mode (no identity verification).
 *
 * This implementation is used for P2P connections where identity verification
 * is not required. It sends credentials without any payload (just version info).
 *
 * @property protoVersion Protocol version (default: 8)
 * @property compatibleVersions List of compatible protocol versions
 * @property clientVersion Client version string (e.g., "any-file/v0.1.0")
 *
 * Based on the Go implementation in any-sync/net/secureservice/credential.go
 */
class NoVerifyChecker(
    private val protoVersion: UInt = 8u,
    private val compatibleVersions: List<UInt> = listOf(8u, 9u),
    private val clientVersion: String = DEFAULT_CLIENT_VERSION
) : CredentialChecker {

    companion object {
        /** Default client version string */
        const val DEFAULT_CLIENT_VERSION = "any-file/v0.1.0"
    }

    override fun makeCredentials(remotePeerId: PeerId): HandshakeCredentials {
        // SkipVerify sends no payload, just version info
        return HandshakeCredentials(
            type = CredentialsType.SKIP_VERIFY,
            payload = null,
            version = protoVersion,
            clientVersion = clientVersion
        )
    }

    override fun checkCredential(
        remotePeerId: PeerId,
        cred: HandshakeCredentials
    ): HandshakeResult {
        // Verify credentials type
        if (cred.type != CredentialsType.SKIP_VERIFY) {
            throw HandshakeProtocolException(
                "Expected SkipVerify credentials, got ${cred.type}"
            )
        }

        // Verify protocol version compatibility
        if (cred.version !in compatibleVersions) {
            throw HandshakeProtocolException(
                "Incompatible protocol version: ${cred.version} (compatible: $compatibleVersions)"
            )
        }

        // SkipVerify mode returns no identity
        return HandshakeResult(
            identity = null,
            protoVersion = cred.version,
            clientVersion = cred.clientVersion
        )
    }
}
