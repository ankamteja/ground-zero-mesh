package org.groundzero.mesh.app.transport.lora

import org.groundzero.mesh.propagation.CompactCodec
import org.groundzero.mesh.propagation.NodeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MeshtasticFrameTest {

    private val victim = NodeId(0xAABB_CCDD_EEFFL)

    @Test
    fun headerFitsTheBudgetCoreReservesForIt() {
        // Envelope sizes itself against LORA_USABLE_FRAME on the assumption that link framing
        // costs no more than this. If the header grows past the reserve, envelopes that pass
        // construction stop fitting the radio — silently, and only on the LoRa hop.
        assertTrue(
            "header ${MeshtasticFrame.HEADER_BYTES} > reserve ${CompactCodec.LORA_LINK_HEADER_RESERVE}",
            MeshtasticFrame.HEADER_BYTES <= CompactCodec.LORA_LINK_HEADER_RESERVE,
        )
    }

    @Test
    fun theLargestPossibleEnvelopeStillFitsOnAir() {
        val onAir = MeshtasticFrame.HEADER_BYTES + CompactCodec.LORA_USABLE_FRAME
        assertTrue("$onAir > ${CompactCodec.LORA_MAX_FRAME}", onAir <= CompactCodec.LORA_MAX_FRAME)
    }

    @Test
    fun encodeThenReassembleRoundTrips() {
        val payload = Random(1).nextBytes(200)
        val frame = MeshtasticFrame.encode(victim, payload)
        val out = MeshtasticFrame.Reassembler().offer(frame)
        assertEquals(1, out.size)
        assertEquals(victim, out[0].source)
        assertTrue(payload.contentEquals(out[0].payload))
    }

    @Test
    fun theFullFortyEightBitNodeIdSurvivesTheHop() {
        // The previous framing carried a 32-bit Meshtastic node number, so a NodeId whose top
        // sixteen bits are set came back as a different node — and every peer table, trust
        // score and corroboration count downstream attached to an id nobody owns.
        val truncated = NodeId(victim.value and 0xFFFF_FFFFL)
        assertNotEquals(victim, truncated)

        val out = MeshtasticFrame.Reassembler().offer(MeshtasticFrame.encode(victim, byteArrayOf(1)))
        assertEquals(victim, out[0].source)
        assertEquals(victim.canonical(), out[0].source.canonical())
    }

    @Test
    fun reassemblesAcrossArbitraryChunkBoundaries() {
        val a = MeshtasticFrame.encode(NodeId(1), byteArrayOf(1, 2, 3))
        val b = MeshtasticFrame.encode(NodeId(2), ByteArray(100) { it.toByte() })
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
        assertEquals(NodeId(1), got[0].source)
        assertEquals(NodeId(2), got[1].source)
        assertEquals(100, got[1].payload.size)
    }

    @Test
    fun resyncsAfterLeadingGarbage() {
        val good = MeshtasticFrame.encode(NodeId(9), byteArrayOf(42))
        val noisy = byteArrayOf(0x00, 0x11, 0x22, 0xA5.toByte(), 0x33) + good
        val out = MeshtasticFrame.Reassembler().offer(noisy)
        assertEquals(1, out.size)
        assertEquals(NodeId(9), out[0].source)
        assertEquals(42, out[0].payload[0].toInt())
    }

    @Test
    fun aCorruptedPayloadIsDroppedRatherThanDecoded() {
        // The whole reason for the CRC: without it these bytes reach CompactCodec, which has
        // no integrity check of its own and will happily decode them into a structurally
        // valid envelope carrying a severity nobody reported.
        val frame = MeshtasticFrame.encode(victim, ByteArray(40) { it.toByte() })
        frame[MeshtasticFrame.HEADER_BYTES + 5] = (frame[MeshtasticFrame.HEADER_BYTES + 5] + 1).toByte()

        val r = MeshtasticFrame.Reassembler()
        assertEquals(emptyList<MeshtasticFrame.Datagram>(), r.offer(frame))
        assertTrue(r.corruptDropped > 0)
    }

    @Test
    fun aCorruptedHeaderIsDroppedToo() {
        val frame = MeshtasticFrame.encode(victim, byteArrayOf(1, 2, 3))
        frame[4] = (frame[4] + 7).toByte()   // a bit flip in the node id
        assertEquals(emptyList<MeshtasticFrame.Datagram>(), MeshtasticFrame.Reassembler().offer(frame))
    }

    @Test
    fun aGoodFrameAfterACorruptedOneStillArrives() {
        val bad = MeshtasticFrame.encode(victim, ByteArray(20) { 7 })
        bad[MeshtasticFrame.HEADER_BYTES] = 0x55
        val good = MeshtasticFrame.encode(NodeId(3), byteArrayOf(9))

        val out = MeshtasticFrame.Reassembler().offer(bad + good)
        assertEquals(1, out.size)
        assertEquals(NodeId(3), out[0].source)
    }

    @Test
    fun rejectsImplausibleLengthAndRecovers() {
        val bogus = byteArrayOf(0xA5.toByte(), 0x5A, 0, 0, 0, 0, 0, 1, 0xFF.toByte(), 0xFF.toByte())
        val good = MeshtasticFrame.encode(NodeId(7), byteArrayOf(1, 2))
        val out = MeshtasticFrame.Reassembler(maxPayload = 16).offer(bogus + good)
        assertEquals(1, out.size)
        assertEquals(NodeId(7), out[0].source)
    }

    @Test
    fun aLongRunOfNoiseDoesNotStallOrBlowTheStack() {
        // Two failures in one: the old reassembler recursed once per discarded byte, so this
        // ended in StackOverflowError; and with a one-byte sync word every 0xA5 false-matched
        // and carried a false length, parking the reader behind a payload that never comes
        // while the real frame waited behind it. A stuck serial line produces exactly this.
        val noise = ByteArray(200_000) { 0xA5.toByte() }
        val good = MeshtasticFrame.encode(NodeId(5), byteArrayOf(1))
        val r = MeshtasticFrame.Reassembler()
        r.offer(noise)
        val out = r.offer(good)
        assertEquals(1, out.size)
        assertEquals(NodeId(5), out[0].source)
    }

    @Test
    fun randomNoiseNeverYieldsAFrameAndNeverStalls() {
        val r = MeshtasticFrame.Reassembler()
        val rng = Random(7)
        repeat(200) { assertEquals(emptyList<MeshtasticFrame.Datagram>(), r.offer(rng.nextBytes(512))) }
        assertTrue(r.bufferedBytes <= r.bufferCap)

        val good = MeshtasticFrame.encode(NodeId(12), byteArrayOf(3))
        // Random noise can end mid-way through a false length claim, so allow the reader one
        // more delivery to walk past it. What must never happen is losing the frame entirely.
        val got = r.offer(good) + r.offer(good)
        assertTrue("frame never arrived after noise", got.any { it.source == NodeId(12) })
    }

    @Test
    fun theBufferStaysBoundedWhenNoFrameEverCompletes() {
        // A sender that dies mid-datagram must not pin memory forever.
        val r = MeshtasticFrame.Reassembler()
        val halfFrame = MeshtasticFrame.encode(victim, ByteArray(200)).copyOfRange(0, 100)
        repeat(500) { r.offer(halfFrame) }
        assertTrue("buffer grew to ${r.bufferedBytes}", r.bufferedBytes <= r.bufferCap)
    }

    @Test
    fun anEmptyPayloadRoundTrips() {
        val out = MeshtasticFrame.Reassembler().offer(MeshtasticFrame.encode(NodeId(4), ByteArray(0)))
        assertEquals(1, out.size)
        assertEquals(0, out[0].payload.size)
    }

    @Test
    fun resetDropsAHalfReceivedDatagram() {
        val frame = MeshtasticFrame.encode(victim, ByteArray(50))
        val r = MeshtasticFrame.Reassembler()
        r.offer(frame.copyOfRange(0, 30))
        r.reset()
        assertEquals(emptyList<MeshtasticFrame.Datagram>(), r.offer(frame.copyOfRange(30, frame.size)))
        assertEquals(1, r.offer(frame).size)
    }
}
