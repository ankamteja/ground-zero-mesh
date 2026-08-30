package org.groundzero.mesh.app.transport.lora

import org.groundzero.mesh.propagation.CompactCodec
import org.groundzero.mesh.propagation.NodeId

/**
 * Serial framing for the BLE-to-serial LoRa bridge.
 *
 * TODO(meshtastic-protobuf): a real Meshtastic radio carries `from` / `to` in its own
 * `MeshPacket` header, *outside* the 233-byte application payload, and the bytes we hand it
 * are the `Data.payload`. Until the Meshtastic protobufs are linked, this module wraps each
 * opaque frame in a datagram of its own, and that header has to be paid for out of the same
 * 233 bytes — see [CompactCodec.LORA_LINK_HEADER_RESERVE].
 *
 * ```
 *  [0..1]   0xA5 0x5A   sync word
 *  [2..7]   u48 BE      source NodeId, all 48 bits
 *  [8]      u8          payload length (0..233, so one byte is enough)
 *  [9]      u8          CRC-8 over bytes [2..8] and the payload
 *  [10..]   payload     the CompactCodec frame
 * ```
 *
 * ### Why the sync word is two bytes
 *
 * It was one, and a one-byte sync word false-matches every 256th byte of noise. That is not
 * merely wasteful: a false match carries a false *length*, and the reader then waits for a
 * payload that will never arrive while a real frame sits behind it in the buffer. A run of
 * 0xA5 on the serial line — exactly what a stuck or floating line produces — stalls the
 * channel indefinitely. Two bytes make that 65,536 times rarer and cost one byte of payload.
 *
 * ### Why the node id is 48 bits and not 32
 *
 * Meshtastic node numbers are 32-bit and the previous framing matched that, truncating a
 * 48-bit [NodeId] on the way out and reconstructing a *different* id on the way in. Every
 * peer that reached this device over LoRa therefore appeared under an identity its owner
 * never had: peer tables, trust scores and the corroboration count all attached to a node
 * that does not exist. Since this header is our own and lives inside the payload, carrying
 * the real id costs two bytes and removes the whole class of bug. It comes back when the
 * native Meshtastic header is linked and `from` moves outside the payload.
 *
 * ### Why a CRC when both LoRa and BLE already have one
 *
 * Neither covers this boundary. The radio's CRC protects the air hop and BLE's protects a
 * GATT notification; what is unprotected is the reassembly *between* them — a dropped or
 * duplicated chunk in the BLE-to-serial stream produces a byte sequence both layers consider
 * perfectly valid. [CompactCodec] has no integrity check of its own, so a frame reassembled
 * one byte out of step decodes into a structurally valid envelope carrying the wrong
 * severity, the wrong origin, or a danger score that was never observed. `Gossip.ingest`
 * cannot catch that: it only rejects frames that fail to decode. One byte turns a silent
 * corruption into a dropped frame, which is the failure a mesh is built to tolerate.
 */
object MeshtasticFrame {

    private const val MAGIC0 = 0xA5
    private const val MAGIC1 = 0x5A

    /** Must stay within [CompactCodec.LORA_LINK_HEADER_RESERVE]; a test asserts it. */
    const val HEADER_BYTES = 10

    /** Largest payload this framing will emit or accept. */
    const val MAX_PAYLOAD = CompactCodec.LORA_MAX_FRAME

    fun encode(source: NodeId, payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "payload ${payload.size} > $MAX_PAYLOAD" }
        val out = ByteArray(HEADER_BYTES + payload.size)
        out[0] = MAGIC0.toByte()
        out[1] = MAGIC1.toByte()
        for (i in 0 until 6) out[2 + i] = ((source.value ushr (40 - 8 * i)) and 0xFF).toByte()
        out[8] = payload.size.toByte()
        out[9] = crc8(out, 2, 8, payload).toByte()
        payload.copyInto(out, HEADER_BYTES)
        return out
    }

    data class Datagram(val source: NodeId, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Datagram && other.source == source && other.payload.contentEquals(payload)

        override fun hashCode(): Int = 31 * source.hashCode() + payload.contentHashCode()
    }

    /**
     * Accumulates BLE-notification chunks and emits whole datagrams, resynchronising on the
     * magic byte after corruption.
     *
     * Bounded and non-recursive by construction. The previous version recursed once per
     * discarded byte, so a long run of noise on the serial line — the ordinary condition this
     * class exists for — ended in `StackOverflowError`; and it copied the entire pending
     * buffer to a list on every attempt just to read eight header bytes, which made
     * reassembly quadratic in the size of the backlog.
     */
    class Reassembler(private val maxPayload: Int = MAX_PAYLOAD) {

        private val buf = ArrayDeque<Byte>()

        /** Frames discarded for a bad length or a failed CRC. Diagnostics, not control flow. */
        var corruptDropped: Int = 0
            private set

        /** Bytes thrown away while hunting for the magic byte. */
        var resyncDiscarded: Int = 0
            private set

        fun offer(chunk: ByteArray): List<Datagram> {
            for (b in chunk) buf.addLast(b)
            // A sender that dies mid-datagram would otherwise leave its partial frame in the
            // buffer forever. Nothing valid is ever longer than one full datagram, so
            // anything beyond that is noise the resync will have to walk past anyway.
            while (buf.size > bufferCap) {
                buf.removeFirst()
                resyncDiscarded++
            }

            val out = ArrayList<Datagram>()
            while (true) out.add(readOne() ?: break)
            return out
        }

        private fun readOne(): Datagram? {
            while (true) {
                if (!resyncToMagic()) return null
                if (buf.size < HEADER_BYTES) return null

                val length = buf[8].u()
                if (length > maxPayload) {
                    dropOne(); corruptDropped++
                    continue
                }
                if (buf.size < HEADER_BYTES + length) return null

                val header = ByteArray(HEADER_BYTES) { buf[it] }
                val payload = ByteArray(length) { buf[HEADER_BYTES + it] }
                if (crc8(header, 2, 8, payload) != header[9].u()) {
                    // Not a real frame at this offset. Step one byte and keep hunting rather
                    // than trusting a length that may itself be noise.
                    dropOne(); corruptDropped++
                    continue
                }

                repeat(HEADER_BYTES + length) { buf.removeFirst() }
                var id = 0L
                for (i in 0 until 6) id = (id shl 8) or header[2 + i].u().toLong()
                return Datagram(NodeId(id), payload)
            }
        }

        /** True once the buffer starts on the sync word, false if it ran out looking. */
        private fun resyncToMagic(): Boolean {
            while (buf.size >= 2 && !(buf[0].u() == MAGIC0 && buf[1].u() == MAGIC1)) dropOne()
            // One trailing byte cannot begin a sync word unless it is the first half of one;
            // anything else is noise and holding it would only delay the next resync.
            if (buf.size == 1 && buf[0].u() != MAGIC0) dropOne()
            return buf.size >= 2
        }

        private fun dropOne() {
            buf.removeFirst()
            resyncDiscarded++
        }

        fun reset() {
            buf.clear()
        }

        /** Pending bytes not yet consumed. Exposed so a test can prove the bound holds. */
        internal val bufferedBytes: Int get() = buf.size

        internal val bufferCap: Int get() = (HEADER_BYTES + maxPayload) * 2
    }

    /**
     * CRC-8, polynomial 0x07, zero init — one byte, which is all the reserve has room for
     * once the full node id is carried. It catches every single-bit error, every odd number
     * of bit errors, and any burst up to 8 bits; on a link whose realistic failure is a
     * dropped or duplicated chunk rather than random bit rot, that is the failure mode that
     * matters.
     */
    internal fun crc8(header: ByteArray, from: Int, until: Int, payload: ByteArray): Int {
        var crc = 0
        for (i in from until until) crc = step(crc, header[i].u())
        for (b in payload) crc = step(crc, b.u())
        return crc
    }

    private fun step(crcIn: Int, byte: Int): Int {
        var crc = crcIn xor byte
        repeat(8) {
            crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF
        }
        return crc
    }

    private fun Byte.u(): Int = toInt() and 0xFF
}
