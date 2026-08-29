package org.groundzero.mesh.app.transport.lora

import java.io.ByteArrayOutputStream

/**
 * Stopgap serial framing for the BLE-to-serial LoRa bridge.
 *
 * TODO(meshtastic-protobuf): a real Meshtastic radio carries `from` / `to` node numbers in
 * its own `MeshPacket` header, *outside* the 233-byte application payload, and the bytes we
 * hand it are the `Data.payload`. Until we link the Meshtastic protobufs, this module wraps
 * each opaque frame in a minimal datagram of its own:
 *
 * ```
 *  [0]   0xA5          magic
 *  [1]   0x5A          magic
 *  [2..5]  u32 BE      source node number (Meshtastic node numbers are 32-bit)
 *  [6..7]  u16 BE      payload length
 *  [8..]   payload     the CompactCodec frame, <= 233 bytes
 * ```
 *
 * Because this datagram's 8-byte header lives *inside* the payload for now, real usable
 * capacity on the stopgap is 233 - 8. That overhead disappears once we move to the native
 * Meshtastic header. See [LoRaBridgeTransport.maxFrameBytes].
 */
object MeshtasticFrame {

    private const val MAGIC0 = 0xA5
    private const val MAGIC1 = 0x5A
    const val HEADER_BYTES = 8

    fun encode(sourceNodeNum: Long, payload: ByteArray): ByteArray {
        require(payload.size <= 0xFFFF) { "payload too large: ${payload.size}" }
        val out = ByteArrayOutputStream(HEADER_BYTES + payload.size)
        out.write(MAGIC0); out.write(MAGIC1)
        for (shift in 24 downTo 0 step 8) out.write(((sourceNodeNum ushr shift) and 0xFF).toInt())
        out.write((payload.size ushr 8) and 0xFF); out.write(payload.size and 0xFF)
        out.write(payload)
        return out.toByteArray()
    }

    data class Datagram(val sourceNodeNum: Long, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Datagram && other.sourceNodeNum == sourceNodeNum && other.payload.contentEquals(payload)
        override fun hashCode(): Int = 31 * sourceNodeNum.hashCode() + payload.contentHashCode()
    }

    /**
     * Accumulates BLE-notification chunks and emits whole datagrams. Resynchronises on the
     * magic bytes if the stream is ever corrupted.
     */
    class Reassembler(private val maxPayload: Int = 233) {
        private val buf = ArrayDeque<Byte>()

        fun offer(chunk: ByteArray): List<Datagram> {
            for (b in chunk) buf.addLast(b)
            val out = ArrayList<Datagram>()
            while (true) {
                val dg = tryReadOne() ?: break
                out.add(dg)
            }
            return out
        }

        private fun tryReadOne(): Datagram? {
            resyncToMagic()
            if (buf.size < HEADER_BYTES) return null
            val h = buf.toList()
            val nodeNum = ((h[2].u() shl 24) or (h[3].u() shl 16) or (h[4].u() shl 8) or h[5].u()).toLong() and 0xFFFFFFFFL
            val len = (h[6].u() shl 8) or h[7].u()
            if (len > maxPayload) { buf.removeFirst(); return tryReadOne() } // bad length, resync
            if (buf.size < HEADER_BYTES + len) return null
            repeat(HEADER_BYTES) { buf.removeFirst() }
            val payload = ByteArray(len) { buf.removeFirst() }
            return Datagram(nodeNum, payload)
        }

        private fun resyncToMagic() {
            while (buf.size >= 2) {
                val it = buf.iterator()
                val first = it.next().u()
                val second = it.next().u()
                if (first == MAGIC0 && second == MAGIC1) return
                buf.removeFirst()
            }
        }

        private fun Byte.u(): Int = toInt() and 0xFF
    }
}
