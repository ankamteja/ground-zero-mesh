package org.groundzero.mesh.transport

import org.groundzero.mesh.propagation.NodeId

/**
 * [Transport] backed by a [SimNetwork]. Same observable behaviour a real transport must
 * provide: start/stop gating, broadcast to known peers, frame-size ceiling, single
 * receive listener.
 */
class SimTransport internal constructor(
    override val localId: NodeId,
    private val net: SimNetwork,
) : Transport {

    override val maxFrameBytes: Int = 1 shl 20 // Nearby-class budget

    private var listener: ((from: NodeId, frame: ByteArray) -> Unit)? = null
    private var running = false

    override fun start() { running = true }

    override fun stop() { running = false }

    override fun send(frame: ByteArray, to: NodeId?) {
        check(running) { "transport not started" }
        require(frame.size <= maxFrameBytes) { "frame ${frame.size} > $maxFrameBytes" }
        net.transmit(localId, to, frame)
    }

    override fun onReceive(listener: (from: NodeId, frame: ByteArray) -> Unit) {
        this.listener = listener
    }

    override fun knownPeers(): List<NodeId> = net.peersOf(localId).toList()

    internal fun deliver(from: NodeId, frame: ByteArray) {
        if (running) listener?.invoke(from, frame)
    }
}
