package com.anyproto.anyfile.data.network

import com.appmattus.crypto.Algorithm

object CidUtils {
    /**
     * Computes a CIDv1 for the given data using blake3-256.
     *
     * Binary layout (36 bytes):
     *   0x01       - CIDv1 version
     *   0x55       - raw codec
     *   0x1e       - blake3 multihash code (0x1e = BLAKE3 per multicodec table)
     *   0x20       - digest length (32)
     *   [32 bytes] - blake3 hash of data
     *
     * Matches Go: cid.V1Builder{Codec: cid.Raw(0x55), MhType: 0x1e}.Sum(data)
     * Note: 0x1b = KECCAK_256 (not blake3). 0x1e is the correct blake3 code.
     */
    fun computeBlake3Cid(data: ByteArray): ByteArray {
        val digest = Algorithm.Blake3().createDigest()
        val hash = digest.digest(data)
        return byteArrayOf(0x01, 0x55.toByte(), 0x1e.toByte(), 0x20.toByte()) + hash
    }
}
