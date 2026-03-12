package com.anyproto.anyfile.data.network

import com.anyproto.anyfile.data.network.libp2p.Libp2pTlsProvider
import com.anyproto.anyfile.data.network.p2p.P2PCoordinatorClient
import com.anyproto.anyfile.data.network.p2p.P2PFilenodeClient
import com.anyproto.anyfile.data.network.yamux.YamuxConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Facade that wires the full P2P stack for sync operations.
 *
 * This class combines all layers of the protocol stack:
 * - Layer 1: libp2p TLS (Ed25519 peer identity, via [Libp2pTlsProvider])
 * - Layer 2: any-sync Handshake (via [YamuxConnectionManager])
 * - Layer 3: Yamux stream multiplexing (via [YamuxConnectionManager])
 * - Layer 4: DRPC protocol (via [P2PCoordinatorClient] / [P2PFilenodeClient])
 * - Layer 5: Coordinator / Filenode clients
 *
 * Callers should use [connectCoordinator] and [connectFilenode] to obtain
 * initialized clients and [disconnect] to release all connections on shutdown.
 */
@Singleton
class SyncClient @Inject constructor(
    private val connectionManager: YamuxConnectionManager,
    private val coordinatorClient: P2PCoordinatorClient,
    private val filenodeClient: P2PFilenodeClient,
    private val tlsProvider: Libp2pTlsProvider,
) {

    /**
     * Establish a yamux session to the coordinator and initialize the client.
     *
     * The session is cached inside [YamuxConnectionManager] so subsequent
     * calls for the same host/port reuse the existing connection.
     *
     * @param host Coordinator hostname or IP (e.g. "10.0.2.2")
     * @param port Coordinator port (e.g. 1004)
     * @return Initialized [P2PCoordinatorClient] ready for RPC calls
     */
    suspend fun connectCoordinator(host: String, port: Int): P2PCoordinatorClient =
        withContext(Dispatchers.IO) {
            connectionManager.getSession(host, port)
            coordinatorClient.initialize(host, port)
            coordinatorClient
        }

    /**
     * Establish a yamux session to the filenode and initialize the client.
     *
     * The session is cached inside [YamuxConnectionManager] so subsequent
     * calls for the same host/port reuse the existing connection.
     *
     * @param host Filenode hostname or IP (e.g. "10.0.2.2")
     * @param port Filenode port (e.g. 1005)
     * @return Initialized [P2PFilenodeClient] ready for RPC calls
     */
    suspend fun connectFilenode(host: String, port: Int): P2PFilenodeClient =
        withContext(Dispatchers.IO) {
            connectionManager.getSession(host, port)
            filenodeClient.initialize(host, port)
            filenodeClient
        }

    /**
     * Close all cached yamux sessions.
     *
     * Should be called on application shutdown or when the sync service is stopped.
     */
    suspend fun disconnect() {
        connectionManager.closeAll()
    }

    /**
     * Return the local libp2p peer ID as a base58-encoded string.
     *
     * The peer ID is stable across calls because [Libp2pTlsProvider] caches
     * the identity derived from the deterministic Ed25519 key pair.
     *
     * @return Base58-encoded peer ID (e.g. "12D3KooW...")
     */
    fun getPeerID(): String = tlsProvider.getPeerIdentity().peerId.base58
}
