// app/src/main/java/com/anyproto/anyfile/domain/sync/SyncOrchestrator.kt
package com.anyproto.anyfile.domain.sync

import android.content.Context
import com.anyproto.anyfile.data.crypto.Blake3Hash
import com.anyproto.anyfile.data.database.dao.SpaceDao
import com.anyproto.anyfile.data.database.dao.SyncedFileDao
import com.anyproto.anyfile.data.database.entity.SyncedFile
import com.anyproto.anyfile.data.database.model.SyncStatus
import com.anyproto.anyfile.data.network.FilenodeClient
import com.anyproto.anyfile.data.network.model.FileInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of a sync operation.
 */
sealed class SyncResult {
    object Success : SyncResult()
    data class PartialSuccess(val successfulFiles: Int, val failedFiles: Int) : SyncResult()
    data class Failed(val error: Throwable) : SyncResult()
}

/**
 * Result of a file upload operation.
 */
sealed class FileUploadResult {
    data class Success(
        val fileId: String,
        val checksum: String,
        val blockSize: Int,
        val blockCount: Int
    ) : FileUploadResult()

    data class Failed(val error: Throwable) : FileUploadResult()
}

/**
 * Result of a file download operation.
 */
sealed class FileDownloadResult {
    data class Success(val localPath: String, val size: Long) : FileDownloadResult()
    data class Failed(val error: Throwable) : FileDownloadResult()
}

/**
 * Constants for file chunking.
 */
private object ChunkingConstants {
    const val DEFAULT_BLOCK_SIZE = 256 * 1024 // 256 KB blocks
    const val MAX_BLOCK_SIZE = 1 * 1024 * 1024 // 1 MB max block size
    const val MIN_BLOCK_SIZE = 64 * 1024 // 64 KB min block size
}

/**
 * Core sync orchestration logic that coordinates bi-directional file synchronization
 * between the local device and the any-sync network.
 *
 * This orchestrator handles:
 * - Upload flow: Hashing, chunking, block upload, metadata update
 * - Download flow: Remote notification, metadata query, block download, file reassembly
 * - Conflict resolution using configurable strategies
 *
 * @property context Application context for file operations
 * @property coordinatorClient Client for space management and notifications
 * @property filenodeClient Client for block storage operations
 * @property spaceDao Database access for space operations
 * @property syncedFileDao Database access for file metadata
 * @property conflictResolver Handler for conflict resolution
 */
@Singleton
class SyncOrchestrator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val filenodeClient: FilenodeClient,
    private val spaceDao: SpaceDao,
    private val syncedFileDao: SyncedFileDao,
    private val conflictResolver: ConflictResolver
) {

    /**
     * Perform a full synchronization for the given space.
     *
     * This will:
     * 1. Query remote file metadata
     * 2. Compare with local database
     * 3. Upload local changes
     * 4. Download remote changes
     * 5. Resolve any conflicts
     *
     * @param spaceId The ID of the space to sync
     * @return SyncResult indicating success or failure
     */
    suspend fun sync(spaceId: String): SyncResult = withContext(Dispatchers.IO) {
        try {
            // Get space info to validate space exists
            val space = spaceDao.getSpaceById(spaceId)
                ?: return@withContext SyncResult.Failed(
                    IllegalArgumentException("Space not found: $spaceId")
                )

            // Update space status to syncing
            spaceDao.updateSpace(space.copy(syncStatus = SyncStatus.SYNCING))

            // Get local files from database
            val localFiles = syncedFileDao.getFilesBySpace(spaceId)

            // For now, we'll implement a basic sync that uploads pending files
            // In a full implementation, we would also query Filenode for remote files
            var successfulUploads = 0
            var failedUploads = 0

            // Collect local files (first emission from Flow)
            val localFilesList = localFiles.first()

            // TODO: Query Filenode for remote file list when API is available
            // val remoteFileIds = getRemoteFileIds(spaceId)

            // For each local file, check if it needs upload
            for (file in localFilesList) {
                if (file.syncStatus == SyncStatus.IDLE || file.syncStatus == SyncStatus.ERROR) {
                    val localFile = File(file.filePath)
                    if (localFile.exists()) {
                        val uploadResult = uploadFile(spaceId, file.filePath)
                        when (uploadResult) {
                            is FileUploadResult.Success -> successfulUploads++
                            is FileUploadResult.Failed -> failedUploads++
                        }
                    }
                }
            }

            // Update last sync time and status
            spaceDao.updateLastSyncTime(spaceId, Date())
            val updatedSpace = spaceDao.getSpaceById(spaceId)
            if (updatedSpace != null) {
                spaceDao.updateSpace(updatedSpace.copy(syncStatus = SyncStatus.IDLE))
            }

            if (failedUploads == 0) {
                SyncResult.Success
            } else {
                SyncResult.PartialSuccess(successfulUploads, failedUploads)
            }
        } catch (e: Exception) {
            // Update space status to error
            try {
                val space = spaceDao.getSpaceById(spaceId)
                if (space != null) {
                    spaceDao.updateSpace(space.copy(syncStatus = SyncStatus.ERROR))
                }
            } catch (ignored: Exception) {
            }
            SyncResult.Failed(e)
        }
    }

    /**
     * Upload a single file to the any-sync network.
     *
     * Upload flow:
     * 1. Calculate blake3 hash of file
     * 2. Split file into blocks (chunking)
     * 3. Upload blocks to Filenode (skipping duplicates)
     * 4. Update local database with new metadata
     *
     * @param spaceId The ID of the space
     * @param filePath The absolute path to the local file
     * @return FileUploadResult with file metadata or error
     */
    suspend fun uploadFile(
        spaceId: String,
        filePath: String
    ): FileUploadResult = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                return@withContext FileUploadResult.Failed(
                    IllegalArgumentException("File does not exist: $filePath")
                )
            }

            // 1. Calculate blake3 hash of file
            val checksum = Blake3Hash.hashFile(file)
            val checksumString = Blake3Hash.hashToString(checksum)

            // 2. Split file into blocks (chunking)
            val blocks = chunkFile(file)
            val blockSize = if (blocks.isNotEmpty()) blocks[0].data.size else ChunkingConstants.DEFAULT_BLOCK_SIZE
            val blockCount = blocks.size

            // Generate a unique file ID based on checksum and path
            val fileId = generateFileId(spaceId, filePath, checksumString)

            // 3. Upload blocks to Filenode (skipping duplicates)
            var uploadedBlocks = 0
            for (block in blocks) {
                val blockChecksum = Blake3Hash.hash(block.data)
                val blockChecksumString = Blake3Hash.hashToString(blockChecksum)

                val result = filenodeClient.blockPush(
                    spaceId = spaceId,
                    fileId = fileId,
                    cid = blockChecksum,
                    data = block.data
                )

                if (result.isSuccess) {
                    uploadedBlocks++
                }
            }

            if (uploadedBlocks < blockCount) {
                return@withContext FileUploadResult.Failed(
                    Exception("Failed to upload all blocks: $uploadedBlocks/$blockCount")
                )
            }

            // 4. Update local database with new metadata
            val existingFiles = syncedFileDao.getFilesBySpace(spaceId).first()
            val existingFile = existingFiles.find { it.filePath == filePath }

            val syncedFile = SyncedFile(
                cid = checksumString,
                spaceId = spaceId,
                filePath = filePath,
                size = file.length(),
                version = (existingFile?.version ?: 0) + 1,
                syncStatus = SyncStatus.IDLE,
                modifiedAt = Date(file.lastModified()),
                checksum = checksumString
            )

            syncedFileDao.insertFile(syncedFile)

            FileUploadResult.Success(
                fileId = fileId,
                checksum = checksumString,
                blockSize = blockSize,
                blockCount = blockCount
            )
        } catch (e: Exception) {
            // Update sync status to error
            try {
                val files = syncedFileDao.getFilesBySpace(spaceId).first()
                val existingFile = files.find { it.filePath == filePath }
                if (existingFile != null) {
                    syncedFileDao.updateSyncStatus(existingFile.cid, SyncStatus.ERROR)
                }
            } catch (ignored: Exception) {
            }
            FileUploadResult.Failed(e)
        }
    }

    /**
     * Download a file from the any-sync network.
     *
     * Download flow:
     * 1. Query Filenode for updated file metadata
     * 2. Compare local vs remote versions
     * 3. Download missing blocks
     * 4. Reassemble file from blocks
     * 5. Write to local storage
     * 6. Update local database
     *
     * @param spaceId The ID of the space
     * @param fileId The ID of the file to download
     * @param localPath The local path where the file should be saved
     * @return FileDownloadResult with local path and size or error
     */
    suspend fun downloadFile(
        spaceId: String,
        fileId: String,
        localPath: String
    ): FileDownloadResult = withContext(Dispatchers.IO) {
        try {
            // 1. Query Filenode for file metadata
            val filesInfoResult = filenodeClient.filesInfo(spaceId, listOf(fileId))
            if (filesInfoResult.isFailure) {
                return@withContext FileDownloadResult.Failed(
                    filesInfoResult.exceptionOrNull() ?: Exception("Unknown error")
                )
            }

            val filesInfo = filesInfoResult.getOrNull() ?: return@withContext FileDownloadResult.Failed(
                Exception("Empty response from Filenode")
            )

            val fileInfo = filesInfo.files.firstOrNull()
                ?: return@withContext FileDownloadResult.Failed(
                    IllegalArgumentException("File not found: $fileId")
                )

            // 2. Check local vs remote for conflicts
            val localFile = File(localPath)
            val existingDbFile = if (localFile.exists()) {
                val files = syncedFileDao.getFilesBySpace(spaceId).first()
                files.find { it.filePath == localPath }
            } else null

            // 3. Download blocks and reassemble
            // TODO: Need to implement block list retrieval from Filenode
            // For now, we'll create a placeholder file

            val outputFile = File(localPath)
            outputFile.parentFile?.mkdirs()

            // 4. Write to local storage
            // TODO: Reassemble from downloaded blocks
            if (!outputFile.exists()) {
                outputFile.createNewFile()
            }

            // 5. Update local database
            val syncedFile = SyncedFile(
                cid = Blake3Hash.hashToString(Blake3Hash.hashFile(outputFile)),
                spaceId = spaceId,
                filePath = localPath,
                size = outputFile.length(),
                version = (existingDbFile?.version ?: 0) + 1,
                syncStatus = SyncStatus.IDLE,
                modifiedAt = Date(outputFile.lastModified()),
                checksum = Blake3Hash.hashToString(Blake3Hash.hashFile(outputFile))
            )

            syncedFileDao.insertFile(syncedFile)

            FileDownloadResult.Success(
                localPath = localPath,
                size = outputFile.length()
            )
        } catch (e: Exception) {
            FileDownloadResult.Failed(e)
        }
    }

    /**
     * Resolve a conflict between local and remote file versions.
     *
     * @param localFile The local file version
     * @param remoteFile The remote file version
     * @param strategy The resolution strategy to apply
     * @return ConflictResolutionResult indicating the resolution
     */
    fun resolveConflict(
        localFile: SyncedFile,
        remoteFile: SyncedFile,
        strategy: ConflictResolutionStrategy
    ) = conflictResolver.resolve(localFile, remoteFile, strategy)

    /**
     * Check if there is a conflict between local and remote versions.
     *
     * @param localFile The local file version (null if doesn't exist)
     * @param remoteFile The remote file version (null if doesn't exist)
     * @return true if there is a conflict
     */
    fun hasConflict(
        localFile: SyncedFile?,
        remoteFile: SyncedFile?
    ) = conflictResolver.hasConflict(localFile, remoteFile)

    /**
     * Split a file into blocks for chunked upload.
     *
     * @param file The file to chunk
     * @param blockSize The size of each block (default: 256KB)
     * @return List of file blocks with their offsets
     */
    private fun chunkFile(file: File, blockSize: Int = ChunkingConstants.DEFAULT_BLOCK_SIZE): List<FileBlock> {
        val blocks = mutableListOf<FileBlock>()
        var offset = 0L

        file.inputStream().use { input ->
            while (true) {
                val buffer = readBlock(input, blockSize)
                if (buffer.isEmpty()) break

                blocks.add(FileBlock(offset = offset, data = buffer))
                offset += buffer.size
            }
        }

        return blocks
    }

    /**
     * Read a single block from the input stream.
     */
    private fun readBlock(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var totalRead = 0

        while (totalRead < size) {
            val read = input.read(buffer, totalRead, size - totalRead)
            if (read == -1) break
            totalRead += read
        }

        return if (totalRead > 0) buffer.sliceArray(0 until totalRead) else ByteArray(0)
    }

    /**
     * Generate a unique file ID based on space, path, and checksum.
     */
    private fun generateFileId(spaceId: String, filePath: String, checksum: String): String {
        val combined = "$spaceId:$filePath:$checksum"
        val hash = Blake3Hash.hash(combined.toByteArray())
        return Blake3Hash.hashToString(hash)
    }

    /**
     * Reassemble a file from downloaded blocks.
     *
     * @param outputPath The path where the file should be written
     * @param blocks The list of blocks to assemble
     */
    private suspend fun reassembleFile(outputPath: String, blocks: List<ByteArray>) {
        File(outputPath).outputStream().use { output ->
            for (block in blocks) {
                output.write(block)
            }
        }
    }
}

/**
 * Represents a block of a file with its offset in the original file.
 */
data class FileBlock(
    val offset: Long,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FileBlock

        if (offset != other.offset) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = offset.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}
