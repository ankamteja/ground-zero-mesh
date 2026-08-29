package org.groundzero.mesh.app.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreAndForwardTest {

    private var now = 0L
    private val saf = StoreAndForward(ttlMs = 1000, maxPerBucket = 4) { now }

    @Test
    fun bucketKeyIsSha256OfZonePrefixNotZoneItself() {
        val key = saf.bucketKey("sector-7")
        assertEquals(64, key.length)
        assertTrue(key.all { it in "0123456789abcdef" })
        assertNotEquals("sector-7", key)
        assertEquals(key, saf.bucketKey("sector-7")) // stable
    }

    @Test
    fun drainReturnsBufferedFramesForZone() {
        saf.offer("z", "n@1", byteArrayOf(1))
        saf.offer("z", "n@2", byteArrayOf(2))
        assertEquals(2, saf.drain("z").size)
        assertEquals(0, saf.drain("other").size)
    }

    @Test
    fun sameDedupKeyReplacesInPlace() {
        saf.offer("z", "n@1", byteArrayOf(1))
        saf.offer("z", "n@1", byteArrayOf(9))
        val frames = saf.drain("z")
        assertEquals(1, frames.size)
        assertEquals(9, frames[0][0].toInt())
    }

    @Test
    fun framesExpireAfterTtl() {
        saf.offer("z", "n@1", byteArrayOf(1))
        now += 1001
        assertEquals(0, saf.drain("z").size)
    }

    @Test
    fun bucketIsBounded() {
        repeat(10) { saf.offer("z", "n@$it", byteArrayOf(it.toByte())) }
        assertEquals(4, saf.drain("z").size)
    }
}
