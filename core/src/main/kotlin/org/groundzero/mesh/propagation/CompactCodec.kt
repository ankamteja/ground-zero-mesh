package org.groundzero.mesh.propagation

import org.groundzero.mesh.agent.SlmFeatureVector
import org.groundzero.mesh.propagation.Envelope.Companion.utf8Size
import java.io.ByteArrayOutputStream

/**
 * The LoRa projection. Fixed-layout binary, big-endian, no self-describing overhead.
 *
 * Layout:
 * ```
 *  off  size  field
 *  0    1     format version (0x01)
 *  1    6     nodeId, 48-bit
 *  7    16    saltFingerprint (32 hex -> 16 bytes)
 *  23   1     tier ordinal
 *  24   1     severity ordinal
 *  25   2     dangerScore, u16 = round(score * 10000)
 *  27   8     timestamp, i64 seconds
 *  35   1     hops
 *  36   1     ttl
 *  37   1     sensory flags (see SensoryFlags)
 *  38   1     addressZone length n
 *  39   n     addressZone, UTF-8
 *  ..   1     slm header: 0xFF = null, else length m (0..50)
 *  ..   m     slmSummary, UTF-8 (absent when header is 0xFF)
 *  ..   1     v_SLM header: 0x00 = absent, 0x01 = present
 *  ..   16    v_SLM, one u8 per slot = round(value * 255) (absent when header is 0x00)
 *  ..   1     view count v
 *       per view: 1 length byte + bytes
 *  ..   1     peer count p
 *       per peer: 6 bytes
 * ```
 */
object CompactCodec : EnvelopeCodec {

    override val name: String = "compact"

    /** 0x02 added the flag byte and the optional v_SLM block. Nothing speaks 0x01. */
    private const val VERSION: Int = 0x02
    private const val SLM_NULL: Int = 0xFF
    private const val VECTOR_ABSENT: Int = 0x00
    private const val VECTOR_PRESENT: Int = 0x01
    private const val SCORE_SCALE: Double = 10_000.0
    private const val SLOT_SCALE: Double = 255.0

    /** Fixed part of the layout, before the variable-length tail. */
    private const val FIXED_PREFIX = 39

    /**
     * Maximum application payload on a Meshtastic LoRa frame.
     *
     * Meshtastic firmware constant `DATA_PAYLOAD_LEN` is **233**, not the 237 the docs'
     * packet table shows (that figure includes ~4 bytes of protobuf framing). Verified
     * 2026-08-29 against meshtastic/protobufs `mesh.proto` and the firmware headers — see
     * `docs/research/meshtastic-payload.md`. The original brief assumed 237; it is 4 bytes
     * too high.
     */
    const val LORA_MAX_FRAME: Int = 233

    /** Exact encoded size of [envelope] without allocating the frame. Used by the
     *  [Envelope] constructor to fail an over-budget envelope at construction time. */
    fun frameSize(envelope: Envelope): Int {
        var n = FIXED_PREFIX
        n += envelope.addressZone.utf8Size()
        n += 1 // slm header
        if (envelope.slmSummary != null) n += envelope.slmSummary.utf8Size()
        n += 1 // v_SLM header
        if (envelope.featureVector != null) n += SlmFeatureVector.LENGTH
        n += 1 // view count
        for (v in envelope.views) n += 1 + v.utf8Size()
        n += 1 // peer count
        n += 6 * envelope.peers.size
        return n
    }

    fun fits(envelope: Envelope): Boolean = frameSize(envelope) <= LORA_MAX_FRAME

    override fun encode(envelope: Envelope): ByteArray {
        val out = ByteArrayOutputStream(frameSize(envelope))
        out.write(VERSION)
        out.writeU48(envelope.nodeId.value)
        out.write(hexToBytes(envelope.saltFingerprint))
        out.write(envelope.tier.ordinal)
        out.write(envelope.severity.ordinal)
        out.writeU16((envelope.dangerScore * SCORE_SCALE).toInt().coerceIn(0, 0xFFFF))
        out.writeI64(envelope.timestamp)
        out.write(envelope.hops)
        out.write(envelope.ttl)
        out.write(envelope.flags.toInt() and 0xFF)

        val zone = envelope.addressZone.toByteArray(Charsets.UTF_8)
        out.write(zone.size)
        out.write(zone)

        if (envelope.slmSummary == null) {
            out.write(SLM_NULL)
        } else {
            val slm = envelope.slmSummary.toByteArray(Charsets.UTF_8)
            out.write(slm.size)
            out.write(slm)
        }

        val vector = envelope.featureVector
        if (vector == null) {
            out.write(VECTOR_ABSENT)
        } else {
            out.write(VECTOR_PRESENT)
            for (slot in vector.values) out.write(Math.round(slot * SLOT_SCALE).toInt())
        }

        out.write(envelope.views.size)
        for (v in envelope.views) {
            val vb = v.toByteArray(Charsets.UTF_8)
            out.write(vb.size)
            out.write(vb)
        }

        out.write(envelope.peers.size)
        for (p in envelope.peers) out.writeU48(p.value)

        return out.toByteArray()
    }

    override fun decode(bytes: ByteArray): Envelope {
        try {
            val r = Reader(bytes)
            val version = r.u8()
            require(version == VERSION) { "unknown compact version $version" }
            val nodeId = NodeId(r.u48())
            val salt = bytesToHex(r.bytes(16))
            val tier = EpistemologyTier.entries[r.u8()]
            val severity = Severity.entries[r.u8()]
            val danger = r.u16() / SCORE_SCALE
            val timestamp = r.i64()
            val hops = r.u8()
            val ttl = r.u8()
            val flags = r.u8().toByte()
            val zone = String(r.bytes(r.u8()), Charsets.UTF_8)
            val slmHeader = r.u8()
            val slm = if (slmHeader == SLM_NULL) null else String(r.bytes(slmHeader), Charsets.UTF_8)
            val vector = if (r.u8() == VECTOR_PRESENT) {
                SlmFeatureVector(FloatArray(SlmFeatureVector.LENGTH) { (r.u8() / SLOT_SCALE).toFloat() })
            } else {
                null
            }
            val viewCount = r.u8()
            val views = ArrayList<String>(viewCount)
            repeat(viewCount) { views.add(String(r.bytes(r.u8()), Charsets.UTF_8)) }
            val peerCount = r.u8()
            val peers = ArrayList<NodeId>(peerCount)
            repeat(peerCount) { peers.add(NodeId(r.u48())) }
            return Envelope(
                nodeId = nodeId,
                saltFingerprint = salt,
                addressZone = zone,
                tier = tier,
                severity = severity,
                dangerScore = danger.coerceIn(0.0, 1.0),
                timestamp = timestamp,
                slmSummary = slm,
                flags = flags,
                featureVector = vector,
                views = views,
                peers = peers,
                hops = hops,
                ttl = ttl,
            )
        } catch (e: Exception) {
            throw EnvelopeDecodeException("compact decode failed: ${e.message}", e)
        }
    }

    // --- helpers ---

    private fun ByteArrayOutputStream.writeU16(v: Int) {
        write((v ushr 8) and 0xFF); write(v and 0xFF)
    }

    private fun ByteArrayOutputStream.writeU48(v: Long) {
        for (shift in 40 downTo 0 step 8) write(((v ushr shift) and 0xFF).toInt())
    }

    private fun ByteArrayOutputStream.writeI64(v: Long) {
        for (shift in 56 downTo 0 step 8) write(((v ushr shift) and 0xFF).toInt())
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex length" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun bytesToHex(b: ByteArray): String =
        buildString(b.size * 2) { for (x in b) append("%02x".format(x.toInt() and 0xFF)) }

    private class Reader(private val a: ByteArray) {
        private var i = 0
        fun u8(): Int {
            check(i < a.size) { "truncated at $i/${a.size}" }
            return a[i++].toInt() and 0xFF
        }
        fun u16(): Int = (u8() shl 8) or u8()
        fun u48(): Long {
            var v = 0L
            repeat(6) { v = (v shl 8) or u8().toLong() }
            return v
        }
        fun i64(): Long {
            var v = 0L
            repeat(8) { v = (v shl 8) or u8().toLong() }
            return v
        }
        fun bytes(len: Int): ByteArray {
            check(i + len <= a.size) { "truncated: need $len at $i/${a.size}" }
            return a.copyOfRange(i, i + len).also { i += len }
        }
    }
}
