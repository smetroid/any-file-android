package com.anyproto.anyfile.data.network.handshake

import com.anyproto.anyfile.data.network.libp2p.Libp2pTlsSocket
import com.anyproto.anyfile.data.network.libp2p.PeerId
import java.io.InputStream
import java.io.OutputStream

/**
 * Wrapper for an authenticated socket after successful handshake.
 *
 * Contains the TLS socket along with peer information and negotiated protocol versions.
 *
 * @property socket The underlying TLS socket
 * @property localPeerId The local peer's libp2p peer ID
 * @property remotePeerId The remote peer's libp2p peer ID
 * @property remoteIdentity The remote peer's Ed25519 public key (null for SkipVerify)
 * @property protoVersion Negotiated protocol version
 * @property clientVersion Remote peer's client version string
 */
data class SecureSession(
    val socket: Libp2pTlsSocket,
    val localPeerId: PeerId,
    val remotePeerId: PeerId,
    val remoteIdentity: ByteArray?,
    val protoVersion: UInt,
    val clientVersion: String
) {
    /**
     * Input stream for reading data from the remote peer.
     */
    val inputStream: InputStream
        get() = socket.socket.inputStream

    /**
     * Output stream for writing data to the remote peer.
     */
    val outputStream: OutputStream
        get() = socket.socket.outputStream

    /**
     * Close the underlying socket and release resources.
     */
    fun close() {
        socket.socket.close()
    }

    /**
     * Check if the session is still open.
     */
    val isClosed: Boolean
        get() = socket.socket.isClosed

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SecureSession

        if (socket != other.socket) return false
        if (localPeerId != other.localPeerId) return false
        if (remotePeerId != other.remotePeerId) return false
        if (remoteIdentity != null) {
            if (other.remoteIdentity == null) return false
            if (!remoteIdentity.contentEquals(other.remoteIdentity)) return false
        } else if (other.remoteIdentity != null) return false
        if (protoVersion != other.protoVersion) return false
        if (clientVersion != other.clientVersion) return false

        return true
    }

    override fun hashCode(): Int {
        var result = socket.hashCode()
        result = 31 * result + localPeerId.hashCode()
        result = 31 * result + remotePeerId.hashCode()
        result = 31 * result + (remoteIdentity?.contentHashCode() ?: 0)
        result = 31 * result + protoVersion.hashCode()
        result = 31 * result + clientVersion.hashCode()
        return result
    }

    /**
     * Create a SecureSession from a handshake result and socket.
     */
    companion object {
        fun fromHandshake(
            socket: Libp2pTlsSocket,
            result: HandshakeResult
        ): SecureSession {
            return SecureSession(
                socket = socket,
                localPeerId = socket.localPeerId,
                remotePeerId = socket.remotePeerId ?: PeerId("", byteArrayOf(), byteArrayOf()),
                remoteIdentity = result.identity,
                protoVersion = result.protoVersion,
                clientVersion = result.clientVersion
            )
        }
    }
}
