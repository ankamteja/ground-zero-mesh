package org.groundzero.mesh.agent

import org.groundzero.mesh.propagation.Codecs
import org.groundzero.mesh.propagation.Envelope
import org.groundzero.mesh.propagation.EpistemologyTier
import org.groundzero.mesh.propagation.NodeId
import org.groundzero.mesh.propagation.Severity
import org.groundzero.mesh.transport.Transport

/**
 * L1. One device's autonomous agent: observe, score, share, adjust, act.
 *
 * ### Three independent tickers, not one loop
 *
 * Sensing, heartbeating and liveness-checking run on **separate** cadences, mirroring the
 * reference implementation's per-concern tickers rather than a single lockstep loop. The
 * reason is not tidiness: a lockstep loop couples the rate at which the device notices
 * things to the rate at which it talks, so slowing the radio down to save battery would
 * also blind the sensor. They must be independently tunable, because on a phone at 4%
 * battery they *will* be tuned differently.
 *
 * The tick methods are called by whoever owns the schedule — coroutines on Android, a test
 * driving them directly here. `core` deliberately carries no coroutine dependency, and the
 * agent holds no threads of its own, which is also what makes it deterministically
 * testable.
 *
 * ### The AI can never delay the SOS
 *
 * [raiseSos] broadcasts at t=0, synchronously, before any classifier runs. Enrichment
 * arrives ~30 seconds later as a second envelope carrying the *same* incident timestamp,
 * so downstream layers update that incident rather than raising a second alert. If the
 * classifier throws, blocks, or is never called at all, the first broadcast has already
 * gone and the deterministic loop continues untouched.
 *
 * ### The cascade, by stage
 *
 * - **Stage 0, t = 0 ms** — [raiseSos]. Synchronous broadcast at score 1.00,
 *   [EpistemologyTier.PRATYAKSA], flags carrying [SensoryFlags.MANUAL_SOS]. Nothing is
 *   inferred, nothing is waited on.
 * - **Stage 1, t = 10–500 ms** — the sensory window fills. On device that is
 *   [senseVector] called on the sensing ticker; each call carries the 16-float `v_SLM` and
 *   one normalised accelerometer magnitude.
 * - **Stage 2, t < 1 ms** — [MathEngine.project] turns that into one `Signal_t`, the EMA in
 *   [DangerScore] smooths it, and [SensoryFlags.encode] compiles the flag byte.
 * - **Stage 3, t ≈ 500 ms–30 s** — [completeSensoryWindow] re-broadcasts the enriched
 *   envelope, reusing the incident timestamp so the same incident is updated, and attaching
 *   the feature vector for the responder board's inspector.
 */
class NodeAgent(
    val nodeId: NodeId,
    val saltFingerprint: String,
    var addressZone: String,
    private val transport: Transport,
    private val clockMs: () -> Long,
    private val classifier: SensoryClassifier = DeterministicSensoryClassifier(),
    private val mathEngine: MathEngine = MathEngine(),
    private val dangerScore: DangerScore = DangerScore(),
    private val gate: HysteresisGate = HysteresisGate(),
    private val detector: EventDetector = EventDetector(),
    private val ttl: Int = DEFAULT_TTL,
) {

    /** An SOS that has been raised and is awaiting or has completed enrichment. */
    data class Incident(
        val severity: Severity,
        /** Seconds. The dedup anchor — reused verbatim by the Stage 3 broadcast. */
        val atSeconds: Long,
        val openedAtMs: Long,
        val enriched: Boolean = false,
    )

    var activeIncident: Incident? = null
        private set

    var lastEvent: SensoryEvent? = null
        private set

    /** The most recent `v_SLM`, or null while only scalar observations have arrived. */
    var lastVector: SlmFeatureVector? = null
        private set

    /** Every envelope this agent put on the wire, newest last. Useful for the debug view. */
    val emitted = ArrayList<Envelope>()

    private var lastBroadcastState: AgentState? = null
    private var lastHeartbeatMs: Long = Long.MIN_VALUE

    val state: AgentState get() = gate.state

    private val codec get() = Codecs.forFrameBudget(transport.maxFrameBytes)

    // ---------------------------------------------------------------- ticker 1: sensing

    /**
     * Fold one normalised observation in.
     *
     * Returns the posture afterwards. Does **not** transmit: sensing and talking are
     * separate concerns on separate cadences, and collapsing them here would rebuild the
     * lockstep loop this class exists to avoid.
     */
    fun senseTick(observation: Double): AgentState {
        dangerScore.observe(observation)
        detector.observe(observation)?.let { lastEvent = it }
        return gate.update(dangerScore.score)
    }

    /**
     * Stages 1 and 2 in one call: project the feature vector through the [MathEngine], fold
     * the resulting `Signal_t` into the EMA, and remember the vector so Stage 3 can attach it
     * and the flag byte can be compiled from it.
     *
     * Does not transmit, for the same reason [senseTick] does not.
     */
    fun senseVector(vector: SlmFeatureVector, accelMagnitude: Double = 0.0): AgentState {
        lastVector = vector
        return senseTick(mathEngine.project(vector, accelMagnitude))
    }

    /**
     * The 8-bit sensory summary as it stands right now.
     *
     * [SensoryFlags.MANUAL_SOS] is set from the agent's own incident state rather than from
     * any sensor: it records that a human pressed the button, which no feature vector can
     * assert on its own and no classifier may clear.
     */
    fun currentFlags(): Byte = SensoryFlags.encode(
        vector = lastVector ?: SlmFeatureVector.ZERO,
        manualSos = activeIncident != null,
        enriched = activeIncident?.enriched == true,
    )

    // ------------------------------------------------------------- ticker 2: heartbeat

    /**
     * Broadcast the current conclusion, if there is anything worth saying.
     *
     * Selective on purpose. A heartbeat goes out when the posture has changed, or when
     * [HEARTBEAT_INTERVAL_MS] has elapsed while not calm. A calm node that has been calm
     * for a while says **nothing at all** — silence is the correct output of a quiet
     * ticker, and it is most of the battery saving in the whole design.
     *
     * The envelope carries the conclusion and never the evidence, so its size does not grow
     * with how much history this node holds.
     */
    fun heartbeatTick(): Envelope? {
        val now = clockMs()
        val postureChanged = gate.state != lastBroadcastState
        val periodicDue = gate.state != AgentState.CALM &&
            now - lastHeartbeatMs >= HEARTBEAT_INTERVAL_MS

        if (!postureChanged && !periodicDue) return null
        if (gate.state == AgentState.CALM && lastBroadcastState == null) return null

        val envelope = buildEnvelope(
            tier = EpistemologyTier.PRATYAKSA,
            severity = activeIncident?.severity ?: Severity.OTHER,
            score = dangerScore.score,
            timestampSeconds = activeIncident?.atSeconds ?: (now / 1000),
            views = buildList {
                add("STATE:" + gate.state.name)
                lastEvent?.let { add("EVENT:" + it.name) }
            },
        )
        lastBroadcastState = gate.state
        lastHeartbeatMs = now
        return emit(envelope)
    }

    // ------------------------------------------------------------- ticker 3: liveness

    /**
     * Peer-table upkeep. Separate from the heartbeat because a node must keep judging who
     * is still out there even while it is itself too quiet to transmit.
     *
     * Runs at half [org.groundzero.mesh.propagation.Peer.SILENT_AFTER_MS] — the reference
     * implementation's Nyquist-style cadence, which is the one thing about its liveness
     * model worth keeping.
     */
    fun livenessTick(peerTable: MutableMap<NodeId, org.groundzero.mesh.propagation.Peer>) {
        val now = clockMs()
        for ((id, peer) in peerTable.entries.toList()) {
            peerTable[id] = peer.decayedToward()
        }
        // Liveness is derived on read via Peer.liveness(now); nothing is dropped here.
        // Silence is not death, so this tick never removes a row.
        require(now >= 0) { "clock must not go negative" }
    }

    // ------------------------------------------------------------------ the SOS path

    /**
     * Stage 1. Broadcast immediately, at full danger, before anything is inferred.
     *
     * The danger score is set to 1.00 rather than fed through the EMA. A first-hand SOS
     * that had to climb an exponential moving average would be smoothed into invisibility
     * for several ticks, and those ticks are the ones that matter. This is a deliberate,
     * documented divergence from the reference implementation.
     */
    fun raiseSos(severity: Severity, atSeconds: Long = clockMs() / 1000): Envelope {
        val incident = Incident(
            severity = severity,
            atSeconds = atSeconds,
            openedAtMs = clockMs(),
        )
        activeIncident = incident
        gate.forceAlarm()
        lastBroadcastState = AgentState.ALARM
        lastHeartbeatMs = clockMs()

        return emit(
            buildEnvelope(
                tier = EpistemologyTier.PRATYAKSA,
                severity = severity,
                score = OVERRIDE_SCORE,
                timestampSeconds = atSeconds,
                views = listOf("OVERRIDE_ACTIVE"),
            ),
        )
    }

    /** Whether the sensory window for the active incident is still open. */
    fun sensoryWindowOpen(): Boolean {
        val incident = activeIncident ?: return false
        return !incident.enriched && clockMs() - incident.openedAtMs <= SENSORY_WINDOW_MS
    }

    /**
     * Stages 2 and 3. Classify what the window saw, then broadcast the refined envelope.
     *
     * Returns null if there is no incident, if the window has closed, if the classifier
     * found nothing, or if it **threw** — all four are the same thing from the mesh's point
     * of view, and none of them can affect the Stage 1 broadcast that already went out.
     *
     * The refined envelope reuses the incident timestamp, so its [Envelope.dedupKey]
     * matches Stage 1 exactly and downstream layers update that incident in place.
     */
    fun completeSensoryWindow(window: SensoryWindow): Envelope? {
        val incident = activeIncident ?: return null
        if (incident.enriched) return null
        if (clockMs() - incident.openedAtMs > SENSORY_WINDOW_MS) return null

        // The window is the evidence, so it defines v_SLM for the enriched broadcast whether
        // or not a sensing ticker ever ran.
        lastVector = SlmFeatureVector.from(window.copy(event = window.event ?: lastEvent))

        val summary = try {
            classifier.classify(window.copy(event = window.event ?: lastEvent))
        } catch (e: Exception) {
            // Stage 2 is advisory. A classifier that fails costs us enrichment and nothing
            // else: the override broadcast is already gone and the loop is unaffected.
            null
        }

        activeIncident = incident.copy(enriched = true)
        val wire = summary?.toWireString() ?: return null

        // Fuse by max, never sum — see SensorySummary. The refined score may only ever
        // *raise* confidence relative to what the fusion actually saw; it never lowers the
        // override, because a person who pressed SOS is still a person who pressed SOS.
        val refined = maxOf(summary.fusedConfidence, REFINED_FLOOR)

        return emit(
            buildEnvelope(
                tier = EpistemologyTier.PRATYAKSA,
                severity = incident.severity,
                score = refined,
                timestampSeconds = incident.atSeconds,
                slmSummary = wire,
                // Stage 3 is the one broadcast that carries the vector: it is the enriched
                // report a responder will open, and 17 bytes buys the inspector its evidence.
                featureVector = lastVector,
                views = listOf("OVERRIDE_ACTIVE", "ENRICHED"),
            ),
        )
    }

    /** Clear the incident once a responder has it, or the user cancels. */
    fun clearIncident() {
        activeIncident = null
    }

    // ------------------------------------------------------------------------ helpers

    /** The "why" panel: the score, the baseline, and the shape the readings took. */
    fun explain(): ScoreExplanation {
        val base = dangerScore.explain()
        val eventNote = lastEvent?.let { "; pattern: " + it.meaning } ?: ""
        val overrideNote = if (activeIncident != null) "; SOS override active" else ""
        return base.copy(
            state = gate.state,
            reason = base.reason + eventNote + overrideNote,
        )
    }

    private fun buildEnvelope(
        tier: EpistemologyTier,
        severity: Severity,
        score: Double,
        timestampSeconds: Long,
        slmSummary: String? = null,
        featureVector: SlmFeatureVector? = null,
        views: List<String> = emptyList(),
    ) = Envelope(
        nodeId = nodeId,
        saltFingerprint = saltFingerprint,
        addressZone = addressZone,
        tier = tier,
        severity = severity,
        dangerScore = score.coerceIn(0.0, 1.0),
        timestamp = timestampSeconds,
        slmSummary = slmSummary,
        flags = currentFlags(),
        featureVector = featureVector,
        views = views.take(Envelope.MAX_VIEWS),
        peers = transport.knownPeers().take(Envelope.MAX_PEERS),
        hops = 0,
        ttl = ttl,
    )

    private fun emit(envelope: Envelope): Envelope {
        transport.send(codec.encode(envelope))
        emitted += envelope
        return envelope
    }

    companion object {
        /** Stage 1 is maximal by definition: the person told us themselves. */
        const val OVERRIDE_SCORE = 1.00

        /** An enriched report never drops below this — an SOS stays an SOS. */
        const val REFINED_FLOOR = 0.80

        /** Addendum: sensors run at most this long after an SOS, then sleep. */
        const val SENSORY_WINDOW_MS = 30_000L

        /** How often a non-calm node repeats itself. */
        const val HEARTBEAT_INTERVAL_MS = 10_000L

        const val DEFAULT_TTL = 4
    }
}
