package org.groundzero.mesh.app.mesh

import org.groundzero.mesh.agent.NodeAgent
import org.groundzero.mesh.agent.SensoryWindow
import org.groundzero.mesh.agent.SlmFeatureVector
import org.groundzero.mesh.app.node.MeshRole
import org.groundzero.mesh.app.transport.PeerTable
import org.groundzero.mesh.app.transport.StoreAndForward
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
    private var store: StoreAndForward? = null
    private var peers: PeerTable? = null
    private var role: MeshRole = MeshRole.NODE
    private var roleListener: ((MeshRole) -> Unit)? = null

    val isInstalled: Boolean get() = synchronized(lock) { gossip != null }

    fun install(
        gossip: Gossip,
        agent: NodeAgent,
        clockMs: () -> Long,
        store: StoreAndForward? = null,
        peers: PeerTable? = null,
    ) = synchronized(lock) {
        this.gossip = gossip
        this.agent = agent
        this.clockMs = clockMs
        this.store = store
        this.peers = peers
    }

    fun clear() = synchronized(lock) {
        gossip = null
        agent = null
        clockMs = { System.currentTimeMillis() }
        store = null
        peers = null
        role = MeshRole.NODE
        roleListener = null
    }

    fun currentRole(): MeshRole = synchronized(lock) { role }

    /**
     * Switch what this device does. Every role keeps relaying — a phone that stops carrying
     * other people's reports has left the mesh — but only [MeshRole.NODE] originates.
     *
     * A RELAY is carry-only: no SOS, no sensing, no heartbeat, which is what makes it cheap
     * enough to leave in a stairwell. A GATEWAY is a responder's phone at the perimeter, not
     * a casualty's, so it does not sense or originate either; it serves the board.
     *
     * The listener is notified outside the lock. It starts and stops real subsystems (the
     * sensor bridge), and holding the stack's lock across that is how a deadlock gets built.
     */
    fun setRole(next: MeshRole) {
        val listener = synchronized(lock) {
            if (role == next) return
            role = next
            roleListener
        }
        listener?.invoke(next)
    }

    /** The service registers here to start and stop what a role needs. */
    fun onRoleChange(listener: ((MeshRole) -> Unit)?) = synchronized(lock) {
        roleListener = listener
    }

    /**
     * A frame off the radio. Null when nothing is installed or the frame was a duplicate.
     *
     * A frame that was *news* is also buffered for replay: the node that is currently out of
     * range is exactly the one that will need it, and it cannot ask for what it never heard.
     * Duplicates are not buffered — they are already in the outbox from the first time.
     */
    fun ingest(frame: ByteArray, from: NodeId?): Envelope? = synchronized(lock) {
        val envelope = gossip?.ingest(frame, from) ?: return null
        store?.offer(envelope.addressZone, envelope.dedupKey, frame)
        envelope
    }

    /** What to replay to a peer that just reconnected. Empty when nothing was buffered. */
    fun bufferedFrames(): List<ByteArray> = synchronized(lock) {
        store?.drainAll() ?: emptyList()
    }

    /**
     * Raise an SOS on the local agent. Returns null if the service is not up yet, which the
     * UI treats as "the mesh is not running" rather than as a failure to report.
     */
    fun raiseSos(severity: Severity): Envelope? = synchronized(lock) {
        if (role != MeshRole.NODE) return null
        agent?.raiseSos(severity)
    }

    fun heartbeatTick(): Envelope? = synchronized(lock) {
        if (role != MeshRole.NODE) return null
        agent?.heartbeatTick()
    }

    /** One sensing tick: the feature vector and the normalised accelerometer magnitude. */
    fun senseVector(vector: SlmFeatureVector, accelMagnitude: Double) = synchronized(lock) {
        if (role == MeshRole.NODE) agent?.senseVector(vector, accelMagnitude)
        Unit
    }

    /** True while the post-SOS window is still open and unenriched. */
    fun sensoryWindowOpen(): Boolean = synchronized(lock) { agent?.sensoryWindowOpen() == true }

    /** Stage 3: hand the window's evidence to the agent for the enriched re-broadcast. */
    fun completeSensoryWindow(window: SensoryWindow): Envelope? = synchronized(lock) {
        agent?.completeSensoryWindow(window)
    }

    /** What the responder dashboard renders. Empty until the service installs the stack. */
    fun rankedBoard(): List<RankedIncident> = synchronized(lock) {
        val g = gossip ?: return emptyList()
        ResponderRanking.rank(g.clusters(), clockMs(), trustOf = g.dedup()::trustOf)
    }

    /**
     * A responder at the gateway has confirmed [nodeId] found/safe.
     *
     * This only reaches [PeerTable.markGone] on *this* device's own peer table — the set of
     * nodes this gateway phone has itself connected to over the radio. A victim several hops
     * away, known to this board only through relay, is not in it, and no mesh-wide "resolved"
     * broadcast exists yet to reach one. Silently doing nothing in that case is the honest
     * behaviour until that propagation is built; a no-op is preferable to a responder
     * believing they closed out an incident that is still open on every other node's board.
     */
    fun markPeerFound(nodeId: NodeId) = synchronized(lock) {
        peers?.markGone(nodeId)
        Unit
    }
}
