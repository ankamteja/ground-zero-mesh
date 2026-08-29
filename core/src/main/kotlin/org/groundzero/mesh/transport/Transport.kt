package org.groundzero.mesh.transport

import org.groundzero.mesh.propagation.NodeId

/**
 * The L0 seam. Byte-oriented on purpose: one [org.groundzero.mesh.propagation.Envelope]
 * has two wire forms, so codec choice lives *above* the transport and is driven by
 * [maxFrameBytes] via [org.groundzero.mesh.propagation.Codecs.forFrameBudget] — never
 * hardcoded at a call site.
 *
 * Implementations: [SimTransport] (here), `NearbyTransport` (app module),
 * `LoRaBridgeTransport` (app module). If an implementation needs this interface to change,
 * that is a PR-level design conversation — every layer above depends on this shape.
 */
interface Transport {
    val localId: NodeId

    /** Nearby / Wi-Fi Direct: large. LoRa: ~233. Drives codec selection. */
    val maxFrameBytes: Int

    fun start()
    fun stop()

    /** [to] null means broadcast to every currently known peer. */
    fun send(frame: ByteArray, to: NodeId? = null)

    fun onReceive(listener: (from: NodeId, frame: ByteArray) -> Unit)

    fun knownPeers(): List<NodeId>
}
