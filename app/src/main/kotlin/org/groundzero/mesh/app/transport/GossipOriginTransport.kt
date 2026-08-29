package org.groundzero.mesh.app.transport

import org.groundzero.mesh.propagation.Codecs
import org.groundzero.mesh.propagation.Envelope
import org.groundzero.mesh.propagation.Gossip
import org.groundzero.mesh.propagation.NodeId
import org.groundzero.mesh.transport.Transport

/**
 * Routes a [org.groundzero.mesh.agent.NodeAgent]'s own broadcasts through [Gossip] instead
 * of straight at the radio.
 *
 * The agent holds a [Transport] and sends its envelopes down it directly, which is right for
 * `core` but wrong once L2 sits on the same device: the frame would hit the radio without
 * `Gossip` ever seeing it, so the propagation key would be unmarked (the first echo back
 * would be re-forwarded as news) and the local cluster store would not hold this node's own
 * report — a gateway phone would not show the SOS its own user just raised.
 *
 * So the agent is given this decorator and `Gossip` is given the real transport. One frame
 * reaches the radio, via [Gossip.originate], which marks the key seen and folds the envelope
 * into the cluster store on the way past. No `core` change.
 */
class GossipOriginTransport(
    private val delegate: Transport,
    private val gossip: Gossip,
    /**
     * Called with everything this node originates, so it can be buffered for replay. A
     * victim's own SOS is the report a peer arriving five minutes later most needs, and it
     * never passes through the receive path where inbound frames are buffered.
     */
    private val onOriginate: (Envelope, ByteArray) -> Unit = { _, _ -> },
) : Transport by delegate {

    override fun send(frame: ByteArray, to: NodeId?) {
        val envelope = Codecs.forFrameBudget(delegate.maxFrameBytes).decode(frame)
        gossip.originate(envelope)
        onOriginate(envelope, frame)
    }
}
