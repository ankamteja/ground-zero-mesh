package org.groundzero.mesh.transport

import org.groundzero.mesh.propagation.NodeId

/**
 * One [Transport] over several. What turns the laptop relay from a star's hub into a link in
 * a chain.
 *
 * [TcpRelayServer] only accepts connections and [TcpTransport] only makes one, so a process
 * holding just one of them is an endpoint: every node it can reach is one hop away and there
 * is nowhere further to pass a frame. A relay in the *middle* of a chain has to do both —
 * accept the phones near it, and dial the next relay along — which is exactly a fan-out over
 * two transports feeding one `Gossip`.
 *
 * ### What it does and does not decide
 *
 * Nothing here knows about envelopes, hops or dedup. A broadcast goes to every member; a
 * targeted send goes to the one member that knows the peer. Loop suppression is `Gossip`'s
 * job and stays there — a frame that comes back around a ring is a duplicate by
 * `propagationKey`, which is already how [TcpRelayServer] gets away with echoing to the
 * connection a frame arrived on.
 *
 * ### maxFrameBytes is the *smallest* member's
 *
 * The codec is chosen from this number ([org.groundzero.mesh.propagation.Codecs.forFrameBudget]),
 * so taking anything but the minimum would encode frames one member physically cannot carry —
 * and it would fail at transmit time on whichever link happens to be narrowest, which in this
 * design is the one most likely to be the long-haul radio.
 */
class CompositeTransport(
    override val localId: NodeId,
    private val members: List<Transport>,
) : Transport {

    init {
        require(members.isNotEmpty()) { "a composite needs at least one member transport" }
        val foreign = members.filter { it.localId != localId }
        require(foreign.isEmpty()) {
            "every member must share the composite's NodeId; got ${foreign.map { it.localId }}"
        }
    }

    /** The narrowest member's budget — see the class doc on why this is not the widest. */
    override val maxFrameBytes: Int = members.minOf { it.maxFrameBytes }

    @Volatile private var listener: ((from: NodeId, frame: ByteArray) -> Unit)? = null

    override fun start() {
        // Registered before starting anything: a member that connects immediately would
        // otherwise deliver its first frames into a null listener.
        members.forEach { member ->
            member.onReceive { from, frame -> listener?.invoke(from, frame) }
        }
        members.forEach { it.start() }
    }

    /** Stops every member, and keeps going if one throws — a half-stopped relay leaks threads. */
    override fun stop() {
        members.forEach { runCatching { it.stop() } }
    }

    /**
     * Broadcast to every member, or route a targeted frame to the member that knows [to].
     *
     * An unroutable [to] is dropped rather than broadcast. Quietly widening a targeted send
     * into a flood is how a store-and-forward replay meant for one reconnecting peer becomes
     * a re-flood of the entire mesh.
     */
    override fun send(frame: ByteArray, to: NodeId?) {
        require(frame.size <= maxFrameBytes) { "frame ${frame.size} > $maxFrameBytes" }
        if (to == null) {
            members.forEach { runCatching { it.send(frame, null) } }
            return
        }
        val owner = members.firstOrNull { to in it.knownPeers() }
        if (owner == null) {
            log("send: no member knows $to; dropping rather than broadcasting")
            return
        }
        runCatching { owner.send(frame, to) }
    }

    override fun onReceive(listener: (from: NodeId, frame: ByteArray) -> Unit) {
        this.listener = listener
    }

    /** The union across members, de-duplicated: one peer reachable two ways is still one peer. */
    override fun knownPeers(): List<NodeId> = members.flatMap { it.knownPeers() }.distinct()

    private fun log(msg: String) = println("[CompositeTransport $localId] $msg")
}
