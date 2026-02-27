// app/src/test/java/com/anyproto/anyfile/domain/sync/ConflictResolverTest.kt
package com.anyproto.anyfile.domain.sync

import com.anyproto.anyfile.data.database.entity.SyncedFile
import com.anyproto.anyfile.data.database.model.SyncStatus
import org.junit.Before
import org.junit.Test
import java.util.Date
import com.google.common.truth.Truth.assertThat
import java.util.regex.Pattern

/**
 * Unit tests for ConflictResolver.
 *
 * Tests cover:
 * - Conflict resolution strategies
 * - LATEST_WINS strategy
 * - MANUAL strategy
 * - BOTH_KEEP strategy
 * - Conflict detection
 */
class ConflictResolverTest {

    private lateinit var conflictResolver: ConflictResolver

    private val testSpaceId = "test-space-123"
    private val testDate1 = Date(1000)
    private val testDate2 = Date(2000)

    @Before
    fun setup() {
        conflictResolver = ConflictResolver()
    }

    @Test
    fun `resolve with LATEST_WINS returns remote when remote is newer`() {
        // Arrange
        val localFile = createTestFile(modifiedAt = testDate1, version = 1)
        val remoteFile = createTestFile(modifiedAt = testDate2, version = 2)

        // Act
        val result = conflictResolver.resolve(
            localFile,
            remoteFile,
            ConflictResolutionStrategy.LATEST_WINS
        )

        // Assert
        assertThat(result).isInstanceOf(ConflictResolutionResult.UseRemote::class.java)
        val useRemote = result as ConflictResolutionResult.UseRemote
        assertThat(useRemote.file).isEqualTo(remoteFile)
    }

    @Test
    fun `resolve with LATEST_WINS returns local when local is newer`() {
        // Arrange
        val localFile = createTestFile(modifiedAt = testDate2, version = 2)
        val remoteFile = createTestFile(modifiedAt = testDate1, version = 1)

        // Act
        val result = conflictResolver.resolve(
            localFile,
            remoteFile,
            ConflictResolutionStrategy.LATEST_WINS
        )

        // Assert
        assertThat(result).isInstanceOf(ConflictResolutionResult.UseLocal::class.java)
        val useLocal = result as ConflictResolutionResult.UseLocal
        assertThat(useLocal.file).isEqualTo(localFile)
    }

    @Test
    fun `resolve with MANUAL returns RequiresManualResolution`() {
        // Arrange
        val localFile = createTestFile(modifiedAt = testDate1, version = 1)
        val remoteFile = createTestFile(modifiedAt = testDate2, version = 2)

        // Act
        val result = conflictResolver.resolve(
            localFile,
            remoteFile,
            ConflictResolutionStrategy.MANUAL
        )

        // Assert
        assertThat(result).isInstanceOf(ConflictResolutionResult.RequiresManualResolution::class.java)
        val manualResolution = result as ConflictResolutionResult.RequiresManualResolution
        assertThat(manualResolution.localFile).isEqualTo(localFile)
        assertThat(manualResolution.remoteFile).isEqualTo(remoteFile)
    }

    @Test
    fun `resolve with BOTH_KEEP returns KeepBoth with new path`() {
        // Arrange
        val localFile = createTestFile(filePath = "/test/file.txt", modifiedAt = testDate1, version = 1)
        val remoteFile = createTestFile(filePath = "/test/file.txt", modifiedAt = testDate2, version = 2)

        // Act
        val result = conflictResolver.resolve(
            localFile,
            remoteFile,
            ConflictResolutionStrategy.BOTH_KEEP
        )

        // Assert
        assertThat(result).isInstanceOf(ConflictResolutionResult.KeepBoth::class.java)
        val keepBoth = result as ConflictResolutionResult.KeepBoth
        assertThat(keepBoth.localFile).isEqualTo(localFile)
        assertThat(keepBoth.remoteFile).isEqualTo(remoteFile)
        assertThat(keepBoth.newLocalPath).contains("_conflict_")
        assertThat(keepBoth.newLocalPath).endsWith(".txt")
    }

    @Test
    fun `resolve with BOTH_KEEP generates unique conflict filename`() {
        // Arrange
        val localFile = createTestFile(filePath = "/test/document.pdf", modifiedAt = testDate1, version = 1)
        val remoteFile = createTestFile(filePath = "/test/document.pdf", modifiedAt = testDate2, version = 2)

        // Act
        val result = conflictResolver.resolve(
            localFile,
            remoteFile,
            ConflictResolutionStrategy.BOTH_KEEP
        )

        // Assert
        val keepBoth = result as ConflictResolutionResult.KeepBoth
        assertThat(keepBoth.newLocalPath).matches(Pattern.compile("/test/document_conflict_\\d+\\.pdf"))
    }

    @Test
    fun `resolve with BOTH_KEEP handles files without extension`() {
        // Arrange
        val localFile = createTestFile(filePath = "/test/README", modifiedAt = testDate1, version = 1)
        val remoteFile = createTestFile(filePath = "/test/README", modifiedAt = testDate2, version = 2)

        // Act
        val result = conflictResolver.resolve(
            localFile,
            remoteFile,
            ConflictResolutionStrategy.BOTH_KEEP
        )

        // Assert
        val keepBoth = result as ConflictResolutionResult.KeepBoth
        assertThat(keepBoth.newLocalPath).matches(Pattern.compile("/test/README_conflict_\\d+"))
    }

    @Test
    fun `hasConflict returns false when local file is null`() {
        // Arrange
        val remoteFile = createTestFile(modifiedAt = testDate1, version = 1)

        // Act
        val result = conflictResolver.hasConflict(null, remoteFile)

        // Assert
        assertThat(result).isFalse()
    }

    @Test
    fun `hasConflict returns false when remote file is null`() {
        // Arrange
        val localFile = createTestFile(modifiedAt = testDate1, version = 1)

        // Act
        val result = conflictResolver.hasConflict(localFile, null)

        // Assert
        assertThat(result).isFalse()
    }

    @Test
    fun `hasConflict returns false when both files are null`() {
        // Act
        val result = conflictResolver.hasConflict(null, null)

        // Assert
        assertThat(result).isFalse()
    }

    @Test
    fun `hasConflict returns false when versions are the same`() {
        // Arrange
        val localFile = createTestFile(version = 1, checksum = "abc123")
        val remoteFile = createTestFile(version = 1, checksum = "abc123")

        // Act
        val result = conflictResolver.hasConflict(localFile, remoteFile)

        // Assert
        assertThat(result).isFalse()
    }

    @Test
    fun `hasConflict returns true when versions differ and checksums differ`() {
        // Arrange
        val localFile = createTestFile(version = 1, checksum = "abc123")
        val remoteFile = createTestFile(version = 2, checksum = "def456")

        // Act
        val result = conflictResolver.hasConflict(localFile, remoteFile)

        // Assert
        assertThat(result).isTrue()
    }

    @Test
    fun `hasConflict returns false when versions differ but checksums are the same`() {
        // Arrange - This could happen if a file was re-uploaded without changes
        val localFile = createTestFile(version = 2, checksum = "abc123")
        val remoteFile = createTestFile(version = 1, checksum = "abc123")

        // Act
        val result = conflictResolver.hasConflict(localFile, remoteFile)

        // Assert
        assertThat(result).isFalse()
    }

    private fun createTestFile(
        filePath: String = "/test/file.txt",
        modifiedAt: Date = Date(),
        version: Int = 1,
        checksum: String = "abc123"
    ): SyncedFile {
        return SyncedFile(
            cid = "cid-$checksum",
            spaceId = testSpaceId,
            filePath = filePath,
            size = 1024,
            version = version,
            syncStatus = SyncStatus.IDLE,
            modifiedAt = modifiedAt,
            checksum = checksum
        )
    }
}
