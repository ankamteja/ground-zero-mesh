package org.groundzero.mesh.transport

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/**
 * Shared wire framing for [TcpTransport] and [TcpRelayServer]: a raw TCP socket is a byte
 * stream, not a sequence of discrete messages the way Nearby's `Payload` or a LoRa packet
 * already is, so something has to mark where one [org.groundzero.mesh.propagation.Envelope]
 * frame ends and the next begins. A 4-byte big-endian length prefix is the whole scheme —
 * simple enough that both ends can implement it independently without a shared library.
 */
internal object TcpFraming {

    /** Generously above any real envelope — a sanity ceiling against a corrupt length, not a
     *  real budget (that is [org.groundzero.mesh.transport.TcpTransport.maxFrameBytes]). */
    const val MAX_FRAME_BYTES = 1 shl 20

    fun writeFrame(out: DataOutputStream, bytes: ByteArray) {
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    /**
     * Null on a clean close at a frame boundary — the ordinary way a peer disconnects.
     * Throws on anything else: a torn read mid-frame, or a length outside sane bounds,
     * both of which mean the connection is no longer trustworthy and should be dropped,
     * not silently papered over.
     */
    fun readFrame(input: DataInputStream): ByteArray? {
        val length = try {
            input.readInt()
        } catch (e: EOFException) {
            return null
        }
        require(length in 0..MAX_FRAME_BYTES) { "frame length $length out of bounds" }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return bytes
    }
}
