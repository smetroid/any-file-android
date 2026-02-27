package com.anyproto.anyfile.data.network

import com.anyproto.anyfile.data.network.model.AccountLimitInfo
import com.anyproto.anyfile.data.network.model.NetworkConfiguration
import com.anyproto.anyfile.data.network.model.NodeInfo
import com.anyproto.anyfile.data.network.model.NodeType
import com.anyproto.anyfile.data.network.model.SpaceInfo
import com.anyproto.anyfile.data.network.model.SpaceReceipt
import com.anyproto.anyfile.data.network.model.SpaceSignResult
import com.anyproto.anyfile.data.network.model.SpaceStatusInfo
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for communicating with the any-sync coordinator service.
 *
 * This implementation uses HTTP/JSON to communicate with the coordinator.
 * The coordinator provides space management, network configuration, and other coordination services.
 *
 * Note: This is an MVP implementation using HTTP/JSON. It can be evolved to use gRPC-web
 * or full gRPC with coroutines in the future.
 */
@Singleton
class CoordinatorClient @Inject constructor(
    private val httpClient: okhttp3.OkHttpClient,
) {

    private var coordinatorBaseUrl: String? = null

    /**
     * Initialize the coordinator client with the specified base URL.
     * This should be called before using other methods.
     *
     * @param baseUrl The base URL of the coordinator service (e.g., "https://coordinator.example.com")
     */
    fun initialize(baseUrl: String) {
        this.coordinatorBaseUrl = baseUrl.trimEnd('/')
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

            val response = postProto(
                endpoint = "/coordinator.SpaceSign",
                request = requestProto,
                responseType = SpaceSignResponse::class.java,
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

            val response = postProto(
                endpoint = "/coordinator.SpaceStatusCheck",
                request = requestProto,
                responseType = SpaceStatusCheckResponse::class.java,
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

            val response = postProto(
                endpoint = "/coordinator.SpaceStatusCheckMany",
                request = requestProto,
                responseType = SpaceStatusCheckManyResponse::class.java,
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

            val response = postProto(
                endpoint = "/coordinator.NetworkConfiguration",
                request = requestProto,
                responseType = NetworkConfigurationResponse::class.java,
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
        if (coordinatorBaseUrl == null) {
            throw IllegalStateException("CoordinatorClient not initialized. Call initialize() first.")
        }
    }

    /**
     * Make a POST request with protobuf message, expecting a protobuf response.
     * Uses binary protobuf encoding for request and response.
     */
    private fun <T : com.google.protobuf.MessageLite> postProto(
        endpoint: String,
        request: com.google.protobuf.MessageLite,
        responseType: Class<T>,
    ): T {
        val url = "$coordinatorBaseUrl$endpoint"

        // Convert proto request to bytes
        val requestBytes = request.toByteArray()

        // Create request body with binary protobuf
        val mediaType = "application/x-protobuf".toMediaType()
        val requestBody: RequestBody = requestBytes.toRequestBody(mediaType)

        // Build and execute request
        val httpRequest = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Content-Type", "application/x-protobuf")
            .addHeader("Accept", "application/x-protobuf")
            .build()

        val response = httpClient.newCall(httpRequest).execute()

        return try {
            if (!response.isSuccessful) {
                throw CoordinatorException("HTTP ${response.code}: ${response.message}")
            }

            parseProtoResponse(response, responseType)
        } finally {
            response.close()
        }
    }

    /**
     * Parse binary protobuf response body into protobuf message.
     */
    private fun <T : com.google.protobuf.MessageLite> parseProtoResponse(
        response: okhttp3.Response,
        responseType: Class<T>,
    ): T {
        val responseBody = response.body
            ?: throw CoordinatorException("Empty response body")

        val responseBytes = responseBody.bytes()

        try {
            // Use the parseFrom method to parse the response
            val parseFromMethod = responseType.getDeclaredMethod("parseFrom", ByteArray::class.java)
            @Suppress("UNCHECKED_CAST")
            return parseFromMethod.invoke(null, responseBytes) as T
        } catch (e: Exception) {
            throw CoordinatorException("Failed to parse protobuf response", e)
        }
    }

    companion object {
        private const val DEFAULT_BASE_URL = "https://coordinator.any-sync.io"
    }
}

/**
 * Exception thrown when coordinator operations fail.
 */
class CoordinatorException(message: String, cause: Throwable? = null) : Exception(message, cause)
