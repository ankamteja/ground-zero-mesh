package org.groundzero.mesh.app.mesh

import org.groundzero.mesh.agent.NodeAgent
import org.groundzero.mesh.gateway.RankedIncident
import org.groundzero.mesh.gateway.ResponderRanking
import org.groundzero.mesh.propagation.Envelope
import org.groundzero.mesh.propagation.Gossip
import org.groundzero.mesh.propagation.NodeId
import org.groundzero.mesh.propagation.Severity

/**
 * The process-wide handle on the running mesh, mirroring [org.groundzero.mesh.app.gateway.GatewayController].
 *
 * The service owns the stack's lifetime; the UI and the gateway server only borrow it. They
 * cannot hold it directly — the Activity outlives no service and the server is started from
 * a different screen — so a process singleton is the honest shape here, and every method is
 * a no-op or an empty list while nothing is installed.
 *
 * ### Why everything is serialised
 *
 * [NodeAgent] and [Gossip] are not thread-safe, and three different threads want in: the UI
 * thread presses SOS, a Nearby callback thread delivers frames, and a NanoHTTPD worker
 * thread reads the board for the dashboard. Rather than demand every caller hop to one
 * looper — a rule that would be silently broken the first time someone adds a call site —
 * the stack takes a lock. Contention is negligible at mesh message rates.
 */
object MeshStack {

    private val lock = Any()

    private var gossip: Gossip? = null
    private var agent: NodeAgent? = null
    private var clockMs: () -> Long = { System.currentTimeMillis() }

    val isInstalled: Boolean get() = synchronized(lock) { gossip != null }

    fun install(gossip: Gossip, agent: NodeAgent, clockMs: () -> Long) = synchronized(lock) {
        this.gossip = gossip
        this.agent = agent
        this.clockMs = clockMs
    }

    fun clear() = synchronized(lock) {
        gossip = null
        agent = null
        clockMs = { System.currentTimeMillis() }
    }

    /** A frame off the radio. Null when nothing is installed or the frame was a duplicate. */
    fun ingest(frame: ByteArray, from: NodeId?): Envelope? = synchronized(lock) {
        gossip?.ingest(frame, from)
    }

    /**
     * Raise an SOS on the local agent. Returns null if the service is not up yet, which the
     * UI treats as "the mesh is not running" rather than as a failure to report.
     */
    fun raiseSos(severity: Severity): Envelope? = synchronized(lock) {
        agent?.raiseSos(severity)
    }

    fun heartbeatTick(): Envelope? = synchronized(lock) { agent?.heartbeatTick() }

    /** What the responder dashboard renders. Empty until the service installs the stack. */
    fun rankedBoard(): List<RankedIncident> = synchronized(lock) {
        val clusters = gossip?.clusters() ?: return emptyList()
        ResponderRanking.rank(clusters, clockMs())
    }
}
