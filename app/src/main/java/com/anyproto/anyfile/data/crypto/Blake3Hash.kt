package com.anyproto.anyfile.data.crypto

import com.appmattus.crypto.Algorithm
import java.io.File

/**
 * Blake3 hash implementation using the cryptohash library.
 * Provides BLAKE3 hashing functionality for byte arrays and files.
 */
object Blake3Hash {

    /**
     * Computes the BLAKE3 hash of the given byte array.
     *
     * @param bytes The input data to hash
     * @return The 32-byte BLAKE3 hash
     */
    fun hash(bytes: ByteArray): ByteArray {
        val digest = Algorithm.Blake3().createDigest()
        return digest.digest(bytes)
    }

    /**
     * Computes the BLAKE3 hash of a file's contents.
     *
     * @param file The file to hash
     * @return The 32-byte BLAKE3 hash
     */
    fun hashFile(file: File): ByteArray {
        val digest = Algorithm.Blake3().createDigest()

        file.inputStream().use { inputStream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }

        return digest.digest()
    }

    /**
     * Converts a byte array to a hexadecimal string.
     *
     * @param bytes The byte array to convert
     * @return The hexadecimal string representation
     */
    fun hashToString(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
