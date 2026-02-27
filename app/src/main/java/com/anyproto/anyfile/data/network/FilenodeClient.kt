package com.anyproto.anyfile.data.network

import com.anyproto.anyfile.data.network.model.AccountInfo
import com.anyproto.anyfile.data.network.model.BlockGetResult
import com.anyproto.anyfile.data.network.model.BlockPushResult
import com.anyproto.anyfile.data.network.model.FileInfo
import com.anyproto.anyfile.data.network.model.FilesGetResult
import com.anyproto.anyfile.data.network.model.FilesInfoResult
import com.anyproto.anyfile.data.network.model.SpaceUsageInfo
import com.anyproto.anyfile.protos.AccountInfoRequest
import com.anyproto.anyfile.protos.AccountInfoResponse
import com.anyproto.anyfile.protos.BlockGetRequest
import com.anyproto.anyfile.protos.BlockGetResponse
import com.anyproto.anyfile.protos.BlockPushRequest
import com.anyproto.anyfile.protos.Ok
import com.anyproto.anyfile.protos.FilesGetRequest
import com.anyproto.anyfile.protos.FilesGetResponse
import com.anyproto.anyfile.protos.FilesInfoRequest
import com.anyproto.anyfile.protos.FilesInfoResponse
import com.anyproto.anyfile.protos.SpaceInfoRequest
import com.anyproto.anyfile.protos.SpaceInfoResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for communicating with the any-sync filenode service.
 *
 * This implementation uses HTTP/protobuf to communicate with the filenode.
 * The filenode provides file storage, block management, and space usage services.
 *
 * Note: This is an MVP implementation using HTTP/protobuf. It can be evolved to use gRPC-web
 * or full gRPC with coroutines in the future.
 */
@Singleton
class FilenodeClient @Inject constructor(
    private val httpClient: okhttp3.OkHttpClient,
) {

    private var filenodeBaseUrl: String? = null

    /**
     * Initialize the filenode client with the specified base URL.
     * This should be called before using other methods.
     *
     * @param baseUrl The base URL of the filenode service (e.g., "http://127.0.0.1:1005")
     */
    fun initialize(baseUrl: String) {
        this.filenodeBaseUrl = baseUrl.trimEnd('/')
    }

    /**
     * Push a block to the filenode.
     *
     * @param spaceId The ID of the space
     * @param fileId The ID of the file
     * @param cid The content identifier of the block
     * @param data The block data
     * @return BlockPushResult indicating success
     */
    suspend fun blockPush(
        spaceId: String,
        fileId: String,
        cid: ByteArray,
        data: ByteArray,
    ): Result<BlockPushResult> = withContext(Dispatchers.IO) {
        try {
            ensureInitialized()

            val requestProto = BlockPushRequest.newBuilder()
                .setSpaceId(spaceId)
                .setFileId(fileId)
                .setCid(com.google.protobuf.ByteString.copyFrom(cid))
                .setData(com.google.protobuf.ByteString.copyFrom(data))
                .build()

            postProto(
                endpoint = "/filenode.BlockPush",
                request = requestProto,
                responseType = Ok::class.java,
            )

            Result.success(BlockPushResult(success = true))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get a block from the filenode by CID.
     *
     * @param spaceId The ID of the space
     * @param cid The content identifier of the block
     * @return BlockGetResult containing the block data
     */
    suspend fun blockGet(
        spaceId: String,
        cid: ByteArray,
    ): Result<BlockGetResult> = withContext(Dispatchers.IO) {
        try {
            ensureInitialized()

            val requestProto = BlockGetRequest.newBuilder()
                .setSpaceId(spaceId)
                .setCid(com.google.protobuf.ByteString.copyFrom(cid))
                .build()

            val response = postProto(
                endpoint = "/filenode.BlockGet",
                request = requestProto,
                responseType = BlockGetResponse::class.java,
            )

            val blockGetResult = BlockGetResult(
                cid = response.cid.toByteArray(),
                data = response.data.toByteArray(),
            )
            Result.success(blockGetResult)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get information about multiple files.
     *
     * @param spaceId The ID of the space
     * @param fileIds List of file IDs to get info for
     * @return FilesInfoResult containing list of FileInfo
     */
    suspend fun filesInfo(
        spaceId: String,
        fileIds: List<String>,
    ): Result<FilesInfoResult> = withContext(Dispatchers.IO) {
        try {
            ensureInitialized()

            val requestProto = FilesInfoRequest.newBuilder()
                .setSpaceId(spaceId)
                .addAllFileIds(fileIds)
                .build()

            val response = postProto(
                endpoint = "/filenode.FilesInfo",
                request = requestProto,
                responseType = FilesInfoResponse::class.java,
            )

            val files = response.filesInfoList.map { FileInfo.fromProto(it) }
            Result.success(FilesInfoResult(files))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get file data for multiple files.
     *
     * @param spaceId The ID of the space
     * @param fileIds List of file IDs to get
     * @return FilesGetResult containing the file IDs
     */
    suspend fun filesGet(
        spaceId: String,
        fileIds: List<String>,
    ): Result<List<FilesGetResult>> = withContext(Dispatchers.IO) {
        try {
            ensureInitialized()

            val results = mutableListOf<FilesGetResult>()

            for (fileId in fileIds) {
                val requestProto = FilesGetRequest.newBuilder()
                    .setSpaceId(spaceId)
                    .build()

                val response = postProto(
                    endpoint = "/filenode.FilesGet",
                    request = requestProto,
                    responseType = FilesGetResponse::class.java,
                )

                results.add(FilesGetResult(response.fileId))
            }

            Result.success(results)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get space usage information.
     *
     * @param spaceId The ID of the space
     * @return SpaceUsageInfo with usage details
     */
    suspend fun spaceInfo(spaceId: String): Result<SpaceUsageInfo> = withContext(Dispatchers.IO) {
        try {
            ensureInitialized()

            val requestProto = SpaceInfoRequest.newBuilder()
                .setSpaceId(spaceId)
                .build()

            val response = postProto(
                endpoint = "/filenode.SpaceInfo",
                request = requestProto,
                responseType = SpaceInfoResponse::class.java,
            )

            val spaceUsageInfo = SpaceUsageInfo.fromProto(response)
            Result.success(spaceUsageInfo)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get account information.
     *
     * @return AccountInfo with account-level details
     */
    suspend fun accountInfo(): Result<AccountInfo> = withContext(Dispatchers.IO) {
        try {
            ensureInitialized()

            val requestProto = AccountInfoRequest.newBuilder()
                .build()

            val response = postProto(
                endpoint = "/filenode.AccountInfo",
                request = requestProto,
                responseType = AccountInfoResponse::class.java,
            )

            val accountInfo = AccountInfo.fromProto(response)
            Result.success(accountInfo)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun ensureInitialized() {
        if (filenodeBaseUrl == null) {
            throw IllegalStateException("FilenodeClient not initialized. Call initialize() first.")
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
        val url = "$filenodeBaseUrl$endpoint"

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
                throw FilenodeException("HTTP ${response.code}: ${response.message}")
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
            ?: throw FilenodeException("Empty response body")

        val responseBytes = responseBody.bytes()

        try {
            // Use the parseFrom method to parse the response
            val parseFromMethod = responseType.getDeclaredMethod("parseFrom", ByteArray::class.java)
            @Suppress("UNCHECKED_CAST")
            return parseFromMethod.invoke(null, responseBytes) as T
        } catch (e: Exception) {
            throw FilenodeException("Failed to parse protobuf response", e)
        }
    }

    companion object {
        private const val DEFAULT_BASE_URL = "http://127.0.0.1:1005"
    }
}

/**
 * Exception thrown when filenode operations fail.
 */
class FilenodeException(message: String, cause: Throwable? = null) : Exception(message, cause)
