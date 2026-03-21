package com.anyproto.anyfile.data.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CidUtilsTest {

    @Test
    fun `computeBlake3Cid returns 36 bytes`() {
        val cid = CidUtils.computeBlake3Cid("hello world".toByteArray())
        assertEquals(36, cid.size)
    }

    @Test
    fun `computeBlake3Cid is deterministic`() {
        val data = "test data".toByteArray()
        assertArrayEquals(CidUtils.computeBlake3Cid(data), CidUtils.computeBlake3Cid(data))
    }

    @Test
    fun `computeBlake3Cid has correct CIDv1 prefix bytes`() {
        val cid = CidUtils.computeBlake3Cid(ByteArray(0))
        assertEquals(0x01.toByte(), cid[0]) // CIDv1
        assertEquals(0x55.toByte(), cid[1]) // raw codec
        assertEquals(0x1e.toByte(), cid[2]) // blake3 multihash code (0x1e per multicodec table)
        assertEquals(0x20.toByte(), cid[3]) // 32-byte digest length
    }

    @Test
    fun `computeBlake3Cid differs for different inputs`() {
        val cid1 = CidUtils.computeBlake3Cid("hello".toByteArray())
        val cid2 = CidUtils.computeBlake3Cid("world".toByteArray())
        assert(!cid1.contentEquals(cid2)) { "Different inputs must produce different CIDs" }
    }

    /**
     * Cross-validates against Go's zeebo/blake3 output.
     * Official blake3("hello world") = d74981efa70a0c880b8d8c1985d075dbcbf679b99a5f9914e5aaf96b831a9e24
     * Expected CIDv1 base58btc = b34W2RYabJMMsfMq3uCh8szbnZFc5vfCaX6uxWgtMEh6SA5A
     */
    @Test
    fun `computeBlake3Cid matches Go CID for hello world`() {
        val cid = CidUtils.computeBlake3Cid("hello world".toByteArray())
        // Expected hash bytes (official blake3 test vector)
        val expectedHash = byteArrayOf(
            0xd7.toByte(), 0x49.toByte(), 0x81.toByte(), 0xef.toByte(),
            0xa7.toByte(), 0x0a.toByte(), 0x0c.toByte(), 0x88.toByte(),
            0x0b.toByte(), 0x8d.toByte(), 0x8c.toByte(), 0x19.toByte(),
            0x85.toByte(), 0xd0.toByte(), 0x75.toByte(), 0xdb.toByte(),
            0xcb.toByte(), 0xf6.toByte(), 0x79.toByte(), 0xb9.toByte(),
            0x9a.toByte(), 0x5f.toByte(), 0x99.toByte(), 0x14.toByte(),
            0xe5.toByte(), 0xaa.toByte(), 0xf9.toByte(), 0x6b.toByte(),
            0x83.toByte(), 0x1a.toByte(), 0x9e.toByte(), 0x24.toByte(),
        )
        assertArrayEquals("Blake3 hash must match official test vector", expectedHash, cid.sliceArray(4..35))
    }
}
