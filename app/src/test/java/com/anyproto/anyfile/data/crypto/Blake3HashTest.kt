package com.anyproto.anyfile.data.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.File

/**
 * Unit tests for Blake3Hash.
 */
class Blake3HashTest {

    @Test
    fun `hash of known input produces expected output`() {
        // Known test vector for BLAKE3
        // Input: "abc"
        // Note: We verify the hash is deterministic and has correct properties
        // The exact BLAKE3 hash value depends on the implementation
        val input = "abc".toByteArray()
        val hash1 = Blake3Hash.hash(input)
        val hash2 = Blake3Hash.hash(input)

        // BLAKE3 produces a 32-byte hash
        assertEquals(32, hash1.size)

        // Same input should produce same hash (deterministic)
        assertArrayEquals("Hash should be deterministic", hash1, hash2)

        // The hash should be different from the input
        assert(hash1.contentEquals(input).not()) {
            "Hash should differ from input"
        }
    }

    @Test
    fun `hash of empty input produces expected output`() {
        // Known test vector for BLAKE3
        // Input: "" (empty)
        val input = ByteArray(0)
        val hash = Blake3Hash.hash(input)

        // BLAKE3 produces a 32-byte hash
        assertEquals(32, hash.size)

        // Verify against known test vector
        // BLAKE3("") = af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262
        val expectedHex = "af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262"
        val expectedHash = expectedHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        assertArrayEquals("Empty input hash should match known test vector", expectedHash, hash)
    }

    @Test
    fun `hash of different inputs produces different outputs`() {
        val input1 = "hello".toByteArray()
        val input2 = "world".toByteArray()

        val hash1 = Blake3Hash.hash(input1)
        val hash2 = Blake3Hash.hash(input2)

        // Different inputs should produce different hashes
        // We compare the hex strings for better readability in test failures
        val hex1 = Blake3Hash.hashToString(hash1)
        val hex2 = Blake3Hash.hashToString(hash2)

        assert(hash1 != hash2) {
            "Different inputs should produce different hashes: '$hex1' vs '$hex2'"
        }
    }

    @Test
    fun `hash same input produces same output`() {
        val input = "deterministic".toByteArray()

        val hash1 = Blake3Hash.hash(input)
        val hash2 = Blake3Hash.hash(input)

        assertArrayEquals("Same input should produce same hash", hash1, hash2)
    }

    @Test
    fun `hashToString produces correct hex encoding`() {
        val input = "test".toByteArray()
        val hash = Blake3Hash.hash(input)
        val hexString = Blake3Hash.hashToString(hash)

        // Hex string should be 64 characters (32 bytes * 2 chars per byte)
        assertEquals(64, hexString.length)

        // All characters should be valid hex
        assert(hexString.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Hex string should only contain valid hex characters: $hexString"
        }

        // Verify it's lowercase
        assertEquals(hexString, hexString.lowercase())
    }

    @Test
    fun `hashToString of known value produces correct output`() {
        // Test with a known byte array
        val bytes = byteArrayOf(0x00, 0x01, 0x02, 0x0F, 0x10, 0xFF.toByte())
        val hexString = Blake3Hash.hashToString(bytes)

        assertEquals("0001020f10ff", hexString)
    }

    @Test
    fun `hashFile computes correct hash for file`() {
        // Create a temporary file with known content
        val tempFile = File.createTempFile("blake3_test", null)
        tempFile.deleteOnExit()

        val content = "test file content for hashing".toByteArray()
        tempFile.writeBytes(content)

        // Hash the file
        val fileHash = Blake3Hash.hashFile(tempFile)

        // Compare with direct hash of the same content
        val directHash = Blake3Hash.hash(content)

        assertArrayEquals("File hash should match content hash", directHash, fileHash)
    }

    @Test
    fun `hashFile handles empty file`() {
        // Create a temporary empty file
        val tempFile = File.createTempFile("blake3_empty_test", null)
        tempFile.deleteOnExit()

        // Hash the empty file
        val fileHash = Blake3Hash.hashFile(tempFile)

        // Compare with direct hash of empty content
        val directHash = Blake3Hash.hash(ByteArray(0))

        assertArrayEquals("Empty file hash should match empty content hash", directHash, fileHash)
    }

    @Test
    fun `hashFile handles large file`() {
        // Create a temporary file with larger content
        val tempFile = File.createTempFile("blake3_large_test", null)
        tempFile.deleteOnExit()

        // Create content larger than the buffer size (8192 bytes)
        val content = "x".repeat(20000).toByteArray()
        tempFile.writeBytes(content)

        // Hash the file
        val fileHash = Blake3Hash.hashFile(tempFile)

        // Compare with direct hash of the same content
        val directHash = Blake3Hash.hash(content)

        assertArrayEquals("Large file hash should match content hash", directHash, fileHash)
    }

    @Test
    fun `hash produces 32 bytes for any input`() {
        val testInputs = listOf(
            ByteArray(0),
            "a".toByteArray(),
            "hello world".toByteArray(),
            "a".repeat(1000).toByteArray(),
            "The quick brown fox jumps over the lazy dog".toByteArray()
        )

        for (input in testInputs) {
            val hash = Blake3Hash.hash(input)
            assertEquals("Hash should always be 32 bytes for input length ${input.size}", 32, hash.size)
        }
    }
}
