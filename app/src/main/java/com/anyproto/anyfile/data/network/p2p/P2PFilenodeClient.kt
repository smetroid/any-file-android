package com.anyproto.anyfile.data.network.p2p

import com.anyproto.anyfile.data.network.drpc.DrpcClient
import com.anyproto.anyfile.data.network.drpc.filenodeCall
import com.anyproto.anyfile.data.network.drpc.filenodeStreamingCall
import com.anyproto.anyfile.data.network.model.AccountInfo
import com.anyproto.anyfile.data.network.model.BlockGetResult
import com.anyproto.anyfile.data.network.model.BlockPushResult
import com.anyproto.anyfile.data.network.model.FileInfo
import com.anyproto.anyfile.data.network.model.FilesInfoResult
import com.anyproto.anyfile.data.network.model.SpaceUsageInfo
import com.anyproto.anyfile.data.network.yamux.YamuxConnectionManager
import com.anyproto.anyfile.data.network.yamux.YamuxSession
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * P2P client for communicating with the any-sync filenode service.
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
class P2PFilenodeClient @Inject constructor(
    private val connectionManager: YamuxConnectionManager,
) {

    private var filenodeHost: String? = null
    private var filenodePort: Int? = null

    /**
     * Initialize the filenode client with the specified endpoint.
     * This should be called before using other methods.
     *
     * @param host The hostname or IP of the filenode service
     * @param port The port of the filenode service
     */
    fun initialize(host: String, port: Int) {
        this.filenodeHost = host
        this.filenodePort = port
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

            callRpc(
                method = "BlockPush",
                request = requestProto,
                responseParser = Ok.parser()
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

            val response = callRpc(
                method = "BlockGet",
                request = requestProto,
                responseParser = BlockGetResponse.parser()
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

            val response = callRpc(
                method = "FilesInfo",
                request = requestProto,
                responseParser = FilesInfoResponse.parser()
            )

            val files = response.filesInfoList.map { FileInfo.fromProto(it) }
            Result.success(FilesInfoResult(files))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * List all file IDs in the space by consuming the server-streaming FilesGet RPC.
     *
     * @param spaceId The ID of the space
     * @return List of file IDs present in the space
     */
    suspend fun filesGet(spaceId: String): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            ensureInitialized()

            val requestProto = FilesGetRequest.newBuilder()
                .setSpaceId(spaceId)
                .build()

            val responses = callRpcStreaming(
                method = "FilesGet",
                request = requestProto,
                responseParser = FilesGetResponse.parser(),
            )

            Result.success(responses.map { it.fileId }.filter { it.isNotEmpty() })
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

            val response = callRpc(
                method = "SpaceInfo",
                request = requestProto,
                responseParser = SpaceInfoResponse.parser()
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

            val response = callRpc(
                method = "AccountInfo",
                request = requestProto,
                responseParser = AccountInfoResponse.parser()
            )

            val accountInfo = AccountInfo.fromProto(response)
            Result.success(accountInfo)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun ensureInitialized() {
        if (filenodeHost == null || filenodePort == null) {
            throw IllegalStateException("P2PFilenodeClient not initialized. Call initialize(host, port) first.")
        }
    }

    private suspend fun <T : com.google.protobuf.MessageLite> callRpcStreaming(
        method: String,
        request: com.google.protobuf.MessageLite,
        responseParser: com.google.protobuf.Parser<T>,
    ): List<T> {
        val session: YamuxSession = connectionManager.getSession(filenodeHost!!, filenodePort!!)
        return DrpcClient(session).filenodeStreamingCall(method, request, responseParser)
    }

    /**
     * Make an RPC call using the DRPC protocol over yamux.
     *
     * This method:
     * 1. Gets or creates a yamux session (with TLS + handshake)
     * 2. Creates a DrpcClient with the session
     * 3. Makes the RPC call using filenodeCall extension
     */
    private suspend fun <T : com.google.protobuf.MessageLite> callRpc(
        method: String,
        request: com.google.protobuf.MessageLite,
        responseParser: com.google.protobuf.Parser<T>,
    ): T {
        val host = filenodeHost!!
        val port = filenodePort!!

        // Get or create yamux session (includes TLS + handshake)
        val session: YamuxSession = connectionManager.getSession(host, port)

        // Create DRPC client with the session
        val drpcClient = DrpcClient(session)

        // Make RPC call using filenode extension function
        // This uses service name "filesync.File" as defined in proto
        return drpcClient.filenodeCall(method, request, responseParser)
    }
}

/**
 * Exception thrown when P2P filenode operations fail.
 */
class P2PFilenodeException(message: String, cause: Throwable? = null) : Exception(message, cause)
