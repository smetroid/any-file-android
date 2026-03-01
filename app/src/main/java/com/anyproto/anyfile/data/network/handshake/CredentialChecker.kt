package com.anyproto.anyfile.data.network.handshake

import com.anyproto.anyfile.data.network.libp2p.PeerId

/**
 * Interface for generating and validating handshake credentials.
 *
 * Implementations provide different credential verification strategies:
 * - [NoVerifyChecker]: Skip identity verification (for P2P)
 * - [PeerSignVerifier]: Ed25519 signature verification
 *
 * Based on the Go implementation in any-sync/net/secureservice/credential.go
 */
interface CredentialChecker {
    /**
     * Generate credentials for the local peer to send to the remote peer.
     *
     * @param remotePeerId The peer ID of the remote peer
     * @return Credentials message to send
     * @throws HandshakeProtocolException if credential generation fails
     */
    fun makeCredentials(remotePeerId: PeerId): HandshakeCredentials

    /**
     * Validate credentials received from the remote peer.
     *
     * @param remotePeerId The peer ID of the remote peer
     * @param cred Credentials received from the remote peer
     * @return Handshake result with identity info and versions
     * @throws HandshakeProtocolException if validation fails
     */
    fun checkCredential(remotePeerId: PeerId, cred: HandshakeCredentials): HandshakeResult
}
