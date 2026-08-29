package org.groundzero.mesh.app.transport.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MeshtasticFrameTest {

    @Test
    fun encodeThenReassembleRoundTrips() {
        val payload = Random(1).nextBytes(200)
        val frame = MeshtasticFrame.encode(sourceNodeNum = 0xDEADBEEFL, payload = payload)
        val out = MeshtasticFrame.Reassembler().offer(frame)
        assertEquals(1, out.size)
        assertEquals(0xDEADBEEFL, out[0].sourceNodeNum)
        assertTrue(payload.contentEquals(out[0].payload))
    }

    @Test
    fun reassemblesAcrossArbitraryChunkBoundaries() {
        val a = MeshtasticFrame.encode(1, byteArrayOf(1, 2, 3))
        val b = MeshtasticFrame.encode(2, ByteArray(100) { it.toByte() })
        val stream = a + b
        val r = MeshtasticFrame.Reassembler()
        val got = mutableListOf<MeshtasticFrame.Datagram>()
        var i = 0
        while (i < stream.size) {
            val n = minOf(7, stream.size - i)   // deliberately tiny, header-splitting chunks
            got += r.offer(stream.copyOfRange(i, i + n))
            i += n
        }
        assertEquals(2, got.size)
        assertEquals(1L, got[0].sourceNodeNum)
        assertEquals(2L, got[1].sourceNodeNum)
        assertEquals(100, got[1].payload.size)
    }

    @Test
    fun resyncsAfterLeadingGarbage() {
        val good = MeshtasticFrame.encode(9, byteArrayOf(42))
        val noisy = byteArrayOf(0x00, 0x11, 0x22, 0xA5.toByte(), 0x33) + good
        val out = MeshtasticFrame.Reassembler().offer(noisy)
        assertEquals(1, out.size)
        assertEquals(9L, out[0].sourceNodeNum)
        assertEquals(42, out[0].payload[0].toInt())
    }

    @Test
    fun rejectsImplausibleLengthAndRecovers() {
        // magic + a length far over the cap, then a valid frame right after
        val bogus = byteArrayOf(0xA5.toByte(), 0x5A, 0, 0, 0, 1, 0xFF.toByte(), 0xFF.toByte())
        val good = MeshtasticFrame.encode(7, byteArrayOf(1, 2))
        val out = MeshtasticFrame.Reassembler(maxPayload = 233).offer(bogus + good)
        assertEquals(1, out.size)
        assertEquals(7L, out[0].sourceNodeNum)
    }
}
