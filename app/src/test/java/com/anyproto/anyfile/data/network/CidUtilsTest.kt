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
        assertEquals(0x1b.toByte(), cid[2]) // blake3 multihash code
        assertEquals(0x20.toByte(), cid[3]) // 32-byte digest length
    }

    @Test
    fun `computeBlake3Cid differs for different inputs`() {
        val cid1 = CidUtils.computeBlake3Cid("hello".toByteArray())
        val cid2 = CidUtils.computeBlake3Cid("world".toByteArray())
        assert(!cid1.contentEquals(cid2)) { "Different inputs must produce different CIDs" }
    }
}
