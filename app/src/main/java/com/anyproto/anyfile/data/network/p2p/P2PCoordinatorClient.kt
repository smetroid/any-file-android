package com.anyproto.anyfile.data.network.p2p

import com.anyproto.anyfile.data.network.drpc.DrpcClient
import com.anyproto.anyfile.data.network.drpc.coordinatorCall
import com.anyproto.anyfile.data.network.model.AccountLimitInfo
import com.anyproto.anyfile.data.network.model.NetworkConfiguration
import com.anyproto.anyfile.data.network.model.NodeInfo
import com.anyproto.anyfile.data.network.model.NodeType
import com.anyproto.anyfile.data.network.model.SpaceInfo
import com.anyproto.anyfile.data.network.model.SpaceReceipt
import com.anyproto.anyfile.data.network.model.SpaceSignResult
import com.anyproto.anyfile.data.network.model.SpaceStatusInfo
import com.anyproto.anyfile.data.network.yamux.YamuxConnectionManager
import com.anyproto.anyfile.data.network.yamux.YamuxSession
import com.anyproto.anyfile.protos.SpaceSignRequest
import com.anyproto.anyfile.protos.SpaceSignResponse
import com.anyproto.anyfile.protos.SpaceStatusCheckRequest
import com.anyproto.anyfile.protos.SpaceStatusCheckResponse
import com.anyproto.anyfile.protos.SpaceStatusCheckManyRequest
import com.anyproto.anyfile.protos.SpaceStatusCheckManyResponse
import com.anyproto.anyfile.protos.NetworkConfigurationRequest
import com.anyproto.anyfile.protos.NetworkConfigurationResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * P2P client for communicating with the any-sync coordinator service.
 *
 * This implementation uses the full protocol stack:
 * - Layer 1: libp2p TLS (via YamuxConnectionManager)
 * - Layer 2: any-sync Handshake (via YamuxConnectionManager)
 * - Layer 3: Yamux multiplexing (via YamuxConnectionManager)
 * - Layer 4: DRPC (via DrpcClient)
 *
 * This is the CORRECT way to communicate with any-sync infrastructure,
 * as the coordinator/filenode services speak yamux/DRPC, not HTTP.
 *
 * @property connectionManager Manages yamux connections with TLS + handshake
 */
@Singleton
class P2PCoordinatorClient @Inject constructor(
    private val connectionManager: YamuxConnectionManager,
) {

    private var coordinatorHost: String? = null
    private var coordinatorPort: Int? = null

    /**
     * Initialize the coordinator client with the specified endpoint.
     * This should be called before using other methods.
     *
     * @param host The hostname or IP of the coordinator service
     * @param port The port of the coordinator service
     */
    fun initialize(host: String, port: Int) {
        this.coordinatorHost = host
        this.coordinatorPort = port
    }

    /**
     * Sign a space creation operation with the coordinator.
     *
     * @param spaceId The ID of the space to sign
     * @param header The space header bytes
     * @param forceRequest If true, forces creating space receipt even if space was deleted
     * @return SpaceSignResult containing the receipt and signature
     */
    suspend fun signSpace(
        spaceId: String,
        header: ByteArray,
        forceRequest: Boolean = false,
    ): Result<SpaceSignResult> = withContext(Dispatchers.IO) {
        try {
            ensureInitialized()

            val requestProto = SpaceSignRequest.newBuilder()
                .setSpaceId(spaceId)
                .setHeader(com.google.protobuf.ByteString.copyFrom(header))
                .setForceRequest(forceRequest)
                .build()

            val response = callRpc(
                method = "SpaceSign",
                request = requestProto,
                responseParser = SpaceSignResponse.parser()
            )

            val receiptProto = response.receipt
            val receiptBytes = receiptProto.spaceReceiptPayload.toByteArray()
            val signature = receiptProto.signature.toByteArray()

            // Parse the receipt from the bytes
            val receipt = com.anyproto.anyfile.protos.SpaceReceipt.parseFrom(receiptBytes)
            val spaceReceipt = com.anyproto.anyfile.data.network.model.SpaceReceipt.fromProto(receipt)

            Result.success(SpaceSignResult(spaceReceipt, signature))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Check the status of a single space.
     *
     * @param spaceId The ID of the space to check
     * @return SpaceStatusInfo with current status
     */
    suspend fun checkSpaceStatus(spaceId: String): Result<SpaceStatusInfo> = withContext(Dispatchers.IO) {
        try {
            ensureInitialized()

            val requestProto = SpaceStatusCheckRequest.newBuilder()
                .setSpaceId(spaceId)
                .build()

            val response = callRpc(
                method = "SpaceStatusCheck",
                request = requestProto,
                responseParser = SpaceStatusCheckResponse.parser()
            )

            val statusInfo = SpaceStatusInfo.fromProto(response.payload)
            Result.success(statusInfo)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Check the status of multiple spaces.
     *
     * @param spaceIds List of space IDs to check
     * @return Pair of list of SpaceInfo and AccountLimitInfo
     */
    suspend fun checkSpaceStatusMany(spaceIds: List<String>): Result<Pair<List<SpaceInfo>, AccountLimitInfo?>> =
        withContext(Dispatchers.IO) {
        try {
            ensureInitialized()

            val requestProto = SpaceStatusCheckManyRequest.newBuilder()
                .addAllSpaceIds(spaceIds)
                .build()

            val response = callRpc(
                method = "SpaceStatusCheckMany",
                request = requestProto,
                responseParser = SpaceStatusCheckManyResponse.parser()
            )

            val spaceInfos = response.payloadsList.mapIndexed { index, payload ->
                SpaceInfo.fromProto(spaceIds[index], payload)
            }

            val accountLimits = response.accountLimits?.let { AccountLimitInfo.fromProto(it) }

            Result.success(Pair(spaceInfos, accountLimits))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get the current network configuration from the coordinator.
     *
     * @param currentId Optional current configuration ID to check for updates
     * @return NetworkConfiguration with node list and metadata
     */
    suspend fun getNetworkConfiguration(currentId: String? = null): Result<NetworkConfiguration> =
        withContext(Dispatchers.IO) {
        try {
            ensureInitialized()

            val requestBuilder = NetworkConfigurationRequest.newBuilder()
            currentId?.let { requestBuilder.setCurrentId(it) }
            val requestProto = requestBuilder.build()

            val response = callRpc(
                method = "NetworkConfiguration",
                request = requestProto,
                responseParser = NetworkConfigurationResponse.parser()
            )

            val networkConfig = NetworkConfiguration.fromProto(response)
            Result.success(networkConfig)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Register a peer with the network.
     * This is essentially getting network configuration to discover nodes.
     *
     * @return NetworkConfiguration with available nodes
     */
    suspend fun registerPeer(): Result<NetworkConfiguration> = getNetworkConfiguration()

    /**
     * Create a new space (by signing it with the coordinator).
     *
     * @param spaceId The ID for the new space
     * @param header The space header bytes
     * @return SpaceSignResult containing the signed receipt
     */
    suspend fun createSpace(
        spaceId: String,
        header: ByteArray,
    ): Result<SpaceSignResult> = signSpace(spaceId, header)

    /**
     * Get coordinator API nodes from network configuration.
     *
     * @return List of coordinator node info
     */
    suspend fun getCoordinatorNodes(): Result<List<NodeInfo>> {
        val configResult = getNetworkConfiguration()
        return configResult.map { config ->
            config.nodes.filter { it.types.contains(NodeType.COORDINATOR_API) }
        }
    }

    private fun ensureInitialized() {
        if (coordinatorHost == null || coordinatorPort == null) {
            throw IllegalStateException("P2PCoordinatorClient not initialized. Call initialize(host, port) first.")
        }
    }

    /**
     * Make an RPC call using the DRPC protocol over yamux.
     *
     * This method:
     * 1. Gets or creates a yamux session (with TLS + handshake)
     * 2. Creates a DrpcClient with the session
     * 3. Makes the RPC call using coordinatorCall extension
     */
    private suspend fun <T : com.google.protobuf.MessageLite> callRpc(
        method: String,
        request: com.google.protobuf.MessageLite,
        responseParser: com.google.protobuf.Parser<T>,
    ): T {
        val host = coordinatorHost!!
        val port = coordinatorPort!!

        // Get or create yamux session (includes TLS + handshake)
        val session: YamuxSession = connectionManager.getSession(host, port)

        // Create DRPC client with the session
        val drpcClient = DrpcClient(session)

        // Make RPC call using coordinator extension function
        // This uses service name "coordinator.Coordinator" as defined in proto
        return drpcClient.coordinatorCall(method, request, responseParser)
    }
}

/**
 * Exception thrown when P2P coordinator operations fail.
 */
class P2PCoordinatorException(message: String, cause: Throwable? = null) : Exception(message, cause)
