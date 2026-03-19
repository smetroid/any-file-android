package com.anyproto.anyfile.service

import android.util.Log
import com.anyproto.anyfile.data.config.NetworkConfigRepository
import com.anyproto.anyfile.data.network.Base58Btc
import com.anyproto.anyfile.data.network.CidUtils
import com.anyproto.anyfile.data.network.p2p.P2PFilenodeClient
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles file upload events from [SyncService]'s file watcher.
 *
 * Reads each file, computes its blake3 CID, and pushes it to the filenode
 * via [P2PFilenodeClient]. Requires [NetworkConfigRepository.spaceId] to be set.
 */
@Singleton
class FileUploadCoordinator @Inject constructor(
    private val filenodeClient: P2PFilenodeClient,
    private val config: NetworkConfigRepository,
) {
    companion object {
        private const val TAG = "FileUploadCoordinator"
    }

    suspend fun upload(path: String) {
        val spaceId = config.spaceId ?: run {
            Log.d(TAG, "No space ID configured, skipping upload for $path")
            return
        }
        try {
            val data = File(path).readBytes()
            val cid = CidUtils.computeBlake3Cid(data)
            // fileId = "relPath|base58btc(CID bytes)" — matches Go's buildFileID convention
            val fileId = "${File(path).name}|${Base58Btc.encode(cid)}"
            filenodeClient.blockPush(spaceId, fileId, cid, data)
                .onSuccess {
                    Log.d(TAG, "Uploaded $fileId (${File(path).name}) to space $spaceId")
                }
                .onFailure { Log.e(TAG, "Upload failed for $fileId: ${it.message}") }
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed for $path: ${e.message}")
        }
    }
}
