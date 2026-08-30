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
import org.groundzero.mesh.propagation.EpistemologyTier
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

    private val activity = ArrayDeque<MeshActivityEntry>()
    private var totalReceived: Int = 0

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
        activity.clear()
        totalReceived = 0
    }

    /**
     * Clear the responder's board, and stop this device feeding it again.
     *
     * Three things have to go together or the board repopulates within seconds, which is
     * what made the old dashboard "clear" look broken:
     *
     * 1. the clusters themselves, and a note not to re-accept those same incidents
     *    ([Gossip.clearBoard]);
     * 2. this device's replay buffer, or the next peer to reconnect is handed the cleared
     *    frames straight back ([StoreAndForward.clear]);
     * 3. the relay screen's log and counters, which describe the same traffic.
     *
     * What it deliberately does *not* do is reach other phones. A victim whose incident is
     * still open keeps heartbeating, and no mesh-wide "resolved" broadcast exists — see
     * [markPeerFound] for the same limitation stated at the peer level. So this clears what
     * this gateway holds and refuses those incidents thereafter; it does not end anyone's
     * emergency, and a genuinely new SOS still arrives normally.
     */
    fun clearBoard() = synchronized(lock) {
        gossip?.clearBoard()
        store?.clear()
        activity.clear()
        totalReceived = 0
        Unit
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
        val g = gossip ?: return null
        val duplicatesBefore = g.suppressedDuplicates
        val envelope = g.ingest(frame, from)
        if (envelope != null) {
            store?.offer(envelope.addressZone, envelope.dedupKey, frame)
            totalReceived++
            record(
                MeshActivityEntry(
                    atMs = clockMs(),
                    from = from,
                    outcome = MeshActivityOutcome.RECEIVED_NEW,
                    zone = envelope.addressZone,
                    severity = envelope.severity,
                    effectiveTier = envelope.effectiveTier,
                )
            )
            return envelope
        }
        // A frame that decoded but was already held, versus one that never decoded at all.
        // Only the counter can tell them apart from here — a null return means both.
        val outcome = if (g.suppressedDuplicates > duplicatesBefore) {
            MeshActivityOutcome.DUPLICATE
        } else {
            MeshActivityOutcome.DROPPED
        }
        totalReceived++
        record(MeshActivityEntry(atMs = clockMs(), from = from, outcome = outcome))
        null
    }

    /** Newest last. Capped — a relay left in a stairwell must not grow a log forever. */
    private fun record(entry: MeshActivityEntry) {
        activity.addLast(entry)
        while (activity.size > ACTIVITY_LOG_CAPACITY) activity.removeFirst()
    }

    /** The recent inbound frames this device saw, oldest first. Empty until installed. */
    fun recentActivity(): List<MeshActivityEntry> = synchronized(lock) { activity.toList() }

    /** Running totals for the relay screen. Zeroed while nothing is installed. */
    fun activityCounts(): MeshActivityCounts = synchronized(lock) {
        val g = gossip ?: return MeshActivityCounts(0, 0, 0, 0, 0)
        MeshActivityCounts(
            received = totalReceived,
            relayed = g.relayed,
            duplicates = g.suppressedDuplicates,
            dropped = g.droppedUndecodable,
            stored = store?.size() ?: 0,
        )
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

    /**
     * A GPS fix from the platform layer, whenever one arrives. See
     * [org.groundzero.mesh.app.sensors.GpsBridge]. A no-op while nothing is installed — same
     * shape as [senseVector], which the agent may simply not have yet.
     */
    fun updateGpsFix(lat: Float, lon: Float) = synchronized(lock) {
        agent?.updateGpsFix(lat, lon)
        Unit
    }

    /** Stage 3: hand the window's evidence to the agent for the enriched re-broadcast. */
    fun completeSensoryWindow(window: SensoryWindow): Envelope? = synchronized(lock) {
        agent?.completeSensoryWindow(window)
    }

    /** What the responder dashboard renders. Empty until the service installs the stack. */
    fun rankedBoard(): List<RankedIncident> = synchronized(lock) {
        val g = gossip ?: return emptyList()
        ResponderRanking.rank(g.clusters(), clockMs(), trustOf = g.dedup()::trustOf)
    }

    /** This device's own permanent id — stable across role changes, null until installed.
     *  A GATEWAY never appears as an incident (it doesn't sense or originate), but the
     *  dashboard still needs to say *which* device is serving the board it's looking at. */
    fun localNodeId(): NodeId? = synchronized(lock) { agent?.nodeId }

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

    /** How many entries [recentActivity] keeps. */
    const val ACTIVITY_LOG_CAPACITY = 50
}

/** What became of one inbound frame. */
enum class MeshActivityOutcome { RECEIVED_NEW, DUPLICATE, DROPPED }

/**
 * One inbound frame, as the relay screen shows it.
 *
 * [zone], [severity] and [effectiveTier] are only populated for
 * [MeshActivityOutcome.RECEIVED_NEW], where the decoded envelope was already in hand. A
 * duplicate or an undecodable frame is deliberately *not* decoded a second time to fill them
 * in: this layer does not know which codec the sender used — that is chosen from the
 * transport's frame budget — so it would have to guess, and a guessed zone on a screen is
 * worse than an honestly blank one.
 */
data class MeshActivityEntry(
    val atMs: Long,
    val from: NodeId?,
    val outcome: MeshActivityOutcome,
    val zone: String? = null,
    val severity: Severity? = null,
    val effectiveTier: EpistemologyTier? = null,
)

/**
 * Running totals for the relay screen.
 *
 * [received] counts every frame that reached this node, including duplicates and
 * undecodables; [relayed] is how many were passed on, which is lower by design — see
 * [Gossip] on why a mesh that forwards everything does not survive the night.
 */
data class MeshActivityCounts(
    val received: Int,
    val relayed: Int,
    val duplicates: Int,
    val dropped: Int,
    val stored: Int,
)
