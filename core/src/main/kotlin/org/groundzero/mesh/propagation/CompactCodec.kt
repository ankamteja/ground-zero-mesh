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
 *  38   1     gps header: 0x00 = absent, 0x01 = satellite fix, 0x02 = self-reported
 *  ..   8     gpsLat + gpsLon, two f32 big-endian (absent when header is 0x00)
 *  ..   1     addressZone length n
 *  ..   n     addressZone, UTF-8
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

    /**
     * 0x04 gave the GPS header a third value so a coordinate says where it came from; it
     * spends no extra bytes, because the header byte was already there with only two values
     * in use. 0x03 added the optional GPS block. 0x02 added the flag byte and v_SLM. Nothing
     * still on the mesh speaks 0x01.
     */
    private const val VERSION: Int = 0x04

    /**
     * Versions this decoder accepts. Reading 0x03 keeps an updated gateway able to hear
     * phones that have not been updated — which is the direction that matters, since the
     * gateway is the one machine an operation can realistically reflash mid-deployment. Every
     * coordinate in a 0x03 frame is a satellite fix by construction: self-reported positions
     * did not exist when that format was written.
     *
     * The other direction needs nothing: an old node meeting a 0x04 frame fails the version
     * check and raises [EnvelopeDecodeException] rather than misreading the header byte and
     * walking off into the variable-length tail.
     */
    private val SUPPORTED_VERSIONS: Set<Int> = setOf(0x03, 0x04)

    private const val SLM_NULL: Int = 0xFF
    private const val VECTOR_ABSENT: Int = 0x00
    private const val VECTOR_PRESENT: Int = 0x01
    private const val GPS_ABSENT: Int = 0x00
    private const val GPS_SATELLITE: Int = 0x01
    private const val GPS_SELF_REPORTED: Int = 0x02
    private const val SCORE_SCALE: Double = 10_000.0
    private const val SLOT_SCALE: Double = 255.0

    /** Fixed part of the layout, before GPS and the variable-length tail. */
    private const val FIXED_PREFIX = 38

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

    /**
     * Bytes reserved out of [LORA_MAX_FRAME] for the link layer's own framing.
     *
     * A LoRa bridge cannot hand the radio a bare envelope: it has to say who sent it and how
     * long it is, and on a BLE-to-serial link it has to be able to resynchronise after a
     * corrupted chunk. That header lives *inside* `DATA_PAYLOAD_LEN`, so it comes out of the
     * same 233 bytes.
     *
     * This was measured, not guessed. The largest envelope the schema can express is exactly
     * 233 bytes (zone=1, four 17-byte views, seven peers, a 50-byte summary and a full
     * v_SLM), so before this constant existed the ceiling left **zero** room for framing —
     * and `LoRaBridgeTransport`, which subtracts its header from the budget, rejected every
     * envelope above 225 at `send()`. An envelope that cannot cross the link it was sized
     * for should not be constructible in the first place, which is what [LORA_USABLE_FRAME]
     * now enforces.
     *
     * `MeshtasticFrame.HEADER_BYTES` must stay within this; a test in `:app` asserts it.
     */
    const val LORA_LINK_HEADER_RESERVE: Int = 10

    /** What an envelope may actually occupy once the link's framing is paid for. */
    const val LORA_USABLE_FRAME: Int = LORA_MAX_FRAME - LORA_LINK_HEADER_RESERVE

    /** Exact encoded size of [envelope] without allocating the frame. Used by the
     *  [Envelope] constructor to fail an over-budget envelope at construction time. */
    fun frameSize(envelope: Envelope): Int {
        var n = FIXED_PREFIX
        n += 1 // gps header
        if (envelope.gpsLat != null) n += 8
        n += 1 // zone length
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

        if (envelope.gpsLat == null) {
            out.write(GPS_ABSENT)
        } else {
            out.write(
                when (envelope.gpsSource!!) {  // non-null whenever gpsLat is; Envelope requires it
                    FixSource.SATELLITE -> GPS_SATELLITE
                    FixSource.SELF_REPORTED -> GPS_SELF_REPORTED
                },
            )
            out.writeF32(envelope.gpsLat)
            out.writeF32(envelope.gpsLon!!)
        }

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
            require(version in SUPPORTED_VERSIONS) { "unknown compact version $version" }
            val nodeId = NodeId(r.u48())
            val salt = bytesToHex(r.bytes(16))
            val tier = EpistemologyTier.entries[r.u8()]
            val severity = Severity.entries[r.u8()]
            val danger = r.u16() / SCORE_SCALE
            val timestamp = r.i64()
            val hops = r.u8()
            val ttl = r.u8()
            val flags = r.u8().toByte()
            // An unrecognised non-zero header would leave the reader mid-coordinate with no
            // way to know it, so anything but the three known values is a corrupt frame.
            val gpsSource = when (val header = r.u8()) {
                GPS_ABSENT -> null
                GPS_SATELLITE -> FixSource.SATELLITE
                GPS_SELF_REPORTED -> FixSource.SELF_REPORTED
                else -> throw IllegalArgumentException("unknown gps header 0x%02x".format(header))
            }
            val gpsLat = if (gpsSource != null) r.f32() else null
            val gpsLon = if (gpsSource != null) r.f32() else null
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
                gpsLat = gpsLat,
                gpsLon = gpsLon,
                gpsSource = gpsSource,
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

    private fun ByteArrayOutputStream.writeF32(v: Float) {
        val bits = java.lang.Float.floatToIntBits(v)
        for (shift in 24 downTo 0 step 8) write(((bits ushr shift) and 0xFF))
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
        fun f32(): Float {
            var bits = 0
            repeat(4) { bits = (bits shl 8) or u8() }
            return java.lang.Float.intBitsToFloat(bits)
        }
        fun bytes(len: Int): ByteArray {
            check(i + len <= a.size) { "truncated: need $len at $i/${a.size}" }
            return a.copyOfRange(i, i + len).also { i += len }
        }
    }
}
