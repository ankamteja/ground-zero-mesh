package org.groundzero.mesh.propagation

/**
 * One physical incident, assembled from however many reports described it.
 *
 * A responder must never be shown report spam. Ten relays of one trapped person is one
 * person, and a dashboard that shows it as ten is worse than useless — it inflates exactly
 * the zone that is best-connected rather than the one that is worst off.
 */
data class IncidentCluster(
    /** [Envelope.dedupKey] of the originating report. */
    val key: String,
    val origin: NodeId,
    val zone: String,
    val severity: Severity,
    /** Highest danger score seen for this incident. */
    val dangerScore: Double,
    /** The strongest tier any holder can claim for it. */
    val tier: EpistemologyTier,
    /** Distinct peers that relayed this to us. Corroboration, not duplication. */
    val corroborators: Set<NodeId>,
    /** Fewest links this reached us by — the closest this incident has ever been. */
    val minHops: Int,
    val firstSeenMs: Long,
    val lastUpdatedMs: Long,
    val slmSummary: String? = null,
    /**
     * Every sensory flag ever asserted for this incident, OR-ed together.
     *
     * Union rather than last-write for the same reason severity never walks back: a device
     * that reported water and is now too damaged to report it was still in water. Evidence
     * accumulates; it does not expire because the next frame was quieter.
     */
    val flags: Byte = 0,
    /** The most recent `v_SLM` seen for this incident, if any report carried one. */
    val featureVector: org.groundzero.mesh.agent.SlmFeatureVector? = null,
    /** True once a report for this incident arrived first-hand rather than as testimony. */
    val firstHandHeld: Boolean = false,
    /**
     * Every [DedupCluster.ingest] call folded into this incident, including repeats from a
     * relay that has already reported it. Unlike [corroborators].size (distinct relayers)
     * or [corroborationCount] (that minus the first), this is the true raw fold count — how
     * many times the mesh told this node about the same person, not how many different
     * nodes told it.
     */
    val reportCount: Int = 1,
    /**
     * The best position held for this incident, with [gpsSource] saying what kind it is.
     * All three null or all three set.
     *
     * A later report's fix (Stage 3 often has a better one than Stage 0) replaces this, and a
     * later report with none never blanks one already held — the same rule as [slmSummary].
     * The one addition is that kind outranks recency: see [betterFix].
     */
    val gpsLat: Float? = null,
    val gpsLon: Float? = null,
    val gpsSource: FixSource? = null,
) {
    /** Independent relayers beyond the first. Zero means single-sourced. */
    val corroborationCount: Int get() = maxOf(0, corroborators.size - 1)

    fun ageMs(nowMs: Long): Long = nowMs - lastUpdatedMs
}

/**
 * Merges incoming envelopes into incidents.
 *
 * Dedup is by [Envelope.dedupKey] — origin node plus incident timestamp. That key is what
 * makes the two-stage pipeline work: the Stage 3 refined broadcast deliberately reuses the
 * Stage 1 timestamp, so it lands here as an *update* to an existing incident rather than
 * as a second person needing rescue.
 *
 * ### The localisation caveat
 *
 * Clustering *within* a zone is not attempted, and the omission is deliberate. Merging two
 * separate people into one incident would hide a casualty, and there is no signal available
 * that could justify it: GPS is unreliable indoors and underground, and the zone tag is a
 * coarse human-entered proxy. Under-merging shows a responder two entries for one person,
 * which costs them a moment. Over-merging loses someone. See the open assumptions in
 * `docs/architecture.md`.
 */
class DedupCluster(private val trust: TrustConsensus = TrustConsensus()) {

    private val clusters = LinkedHashMap<String, IncidentCluster>()

    /**
     * Fold one envelope in.
     *
     * [from] is the peer the frame arrived from, which is not the same as
     * [Envelope.nodeId] — the origin is who is in trouble, the sender is who passed it
     * along. Conflating them is how corroboration counts get inflated by a single chatty
     * relay.
     */
    fun ingest(envelope: Envelope, from: NodeId?, nowMs: Long): IncidentCluster {
        val key = envelope.dedupKey
        val existing = clusters[key]
        val isFirstHand = envelope.effectiveTier == EpistemologyTier.PRATYAKSA

        val merged = if (existing == null) {
            IncidentCluster(
                key = key,
                origin = envelope.nodeId,
                zone = envelope.addressZone,
                severity = envelope.severity,
                dangerScore = envelope.dangerScore,
                tier = envelope.effectiveTier,
                corroborators = setOfNotNull(from),
                minHops = envelope.hops,
                firstSeenMs = nowMs,
                lastUpdatedMs = nowMs,
                slmSummary = envelope.slmSummary,
                flags = envelope.flags,
                featureVector = envelope.featureVector,
                firstHandHeld = isFirstHand,
                gpsLat = envelope.gpsLat,
                gpsLon = envelope.gpsLon,
                gpsSource = envelope.gpsSource,
            )
        } else {
            val fix = betterFix(existing, envelope)
            existing.copy(
                // Severity is the person's own statement of how fast they die. Take the
                // most urgent ever reported and never walk it back on a later, calmer relay.
                severity = if (envelope.severity.rank < existing.severity.rank) {
                    envelope.severity
                } else {
                    existing.severity
                },
                // A zone entered after the first broadcast used to be dropped on the floor:
                // `zone` was simply absent from this copy, so whatever the very first
                // envelope carried — almost always Envelope.UNSET_ZONE, since nobody types a
                // zone before pressing SOS — was frozen for the life of the incident. The
                // Stage 3 enrichment reuses the same dedup key by design, so it lands here as
                // an update, and its zone was the one field the update could not change.
                //
                // Same rule the summary and the GPS fix already use: an informative value
                // wins, and an uninformative one never blanks what is already held.
                zone = if (Envelope.isZoneKnown(envelope.addressZone)) envelope.addressZone else existing.zone,
                dangerScore = maxOf(existing.dangerScore, envelope.dangerScore),
                tier = strongest(existing.tier, envelope.effectiveTier),
                corroborators = existing.corroborators + setOfNotNull(from),
                minHops = minOf(existing.minHops, envelope.hops),
                lastUpdatedMs = nowMs,
                // A later enrichment fills the summary in; it never blanks an existing one.
                slmSummary = envelope.slmSummary ?: existing.slmSummary,
                flags = (existing.flags.toInt() or envelope.flags.toInt()).toByte(),
                featureVector = envelope.featureVector ?: existing.featureVector,
                firstHandHeld = existing.firstHandHeld || isFirstHand,
                reportCount = existing.reportCount + 1,
                gpsLat = fix.lat,
                gpsLon = fix.lon,
                gpsSource = fix.source,
            )
        }

        if (existing != null && from != null && from != envelope.nodeId) {
            // Grounds to judge: this peer relayed a claim about an incident we already hold,
            // so its report can be checked against one.
            //
            // The origin is never judged for its own report. Severity is the victim's own
            // statement of how fast they die, and penalising the person in trouble for
            // restating it would be exactly backwards.
            when (severityAgreement(envelope.severity, existing.severity)) {
                Agreement.SAME -> trust.reinforce(from)
                // Carrying an upgrade is the designed path, not a contradiction. Neither
                // rewarded nor punished: a relay should not be able to farm standing by
                // escalating, and it must not lose standing for relaying the truth.
                Agreement.ESCALATION -> Unit
                Agreement.CONTRADICTION -> trust.penalise(from)
            }
        }

        clusters[key] = merged
        return merged
    }

    /**
     * How a relayed severity compares with the one already held.
     *
     * This exists because "the severities differ, so the peer is lying" is wrong twice over,
     * and both mistakes punish the relays a rescue depends on. Trust decays about seven times
     * faster than it builds, so a wrong penalty is expensive and slow to undo — and it feeds
     * `ResponderRanking`'s corroboration weighting, which means an unjustly demoted relay
     * drags down the ranking of the very incident it carried.
     */
    private enum class Agreement { SAME, ESCALATION, CONTRADICTION }

    /**
     * An **escalation** is the system working as designed, not a contradiction.
     *
     * Severity is monotonic here: the merge above takes the most urgent ever reported and
     * never walks it back, and Stage 3's enriched re-broadcast reuses the incident's dedup
     * key, so a victim upgrading from OTHER to DROWNING_IMMINENT arrives as an update to the
     * same incident. A relay carrying that upgrade did its job perfectly — and the previous
     * rule, which read any difference as a contradiction, docked it from 0.5 to 0.325 for
     * doing so. That is expensive: trust builds about seven times slower than it decays, and
     * it feeds `ResponderRanking`'s corroboration weighting, so the penalty demoted both the
     * relay and the ranking of the incident it had just carried.
     *
     * A **contradiction** — a report *less* urgent than what is held — is still penalised.
     * That is the spoofed-node case: a peer downplaying an emergency another peer reported,
     * which is exactly what the asymmetric decay exists to punish.
     *
     * Known limitation, deliberately left alone: `StoreAndForward` replays buffered frames on
     * reconnection, so an honest relay that survived a partition can deliver a genuinely old,
     * lower-severity frame and be penalised for it. Distinguishing that from a spoof needs
     * something this layer does not have — the envelope's timestamp is the *incident's*, not
     * the report's, because the dedup key reuses it by design. Weakening the spoof defence
     * on a maybe is the worse trade, so it is recorded rather than guessed at.
     */
    private fun severityAgreement(incoming: Severity, held: Severity): Agreement = when {
        incoming == held -> Agreement.SAME
        incoming.rank < held.rank -> Agreement.ESCALATION
        else -> Agreement.CONTRADICTION
    }

    /**
     * Note that a peer's report agreed with, or contradicted, what we hold.
     *
     * [ingest] calls this itself, but only for a relay of an incident already held — a node
     * cannot reward a peer merely for talking, and a first sighting corroborates nothing.
     * Public because a layer with better grounds than severity agreement (a gateway
     * comparing against a responder's confirmation, say) should be able to say so.
     */
    fun judge(peer: NodeId, corroborated: Boolean) {
        if (corroborated) trust.reinforce(peer) else trust.penalise(peer)
    }

    fun trustOf(peer: NodeId): Double = trust.trustOf(peer)

    fun clusters(): List<IncidentCluster> = clusters.values.toList()

    fun cluster(key: String): IncidentCluster? = clusters[key]

    val size: Int get() = clusters.size

    private fun strongest(a: EpistemologyTier, b: EpistemologyTier): EpistemologyTier =
        if (a.ordinal <= b.ordinal) a else b

    /** A position and what kind it is, kept together so the three can never drift apart. */
    private data class Fix(val lat: Float?, val lon: Float?, val source: FixSource?)

    /**
     * Which of the two positions the cluster should keep.
     *
     * Newer normally wins, because a later report is usually a better-settled fix. The one
     * exception is kind: a [FixSource.SELF_REPORTED] tap never displaces a
     * [FixSource.SATELLITE] lock, however recent it is. A phone that got a fix outdoors and
     * was then carried inside still knows where it was to within metres, which beats the
     * owner's estimate of where "inside" is — and the failure this prevents is the expensive
     * one: a measured location silently replaced by a guess, on a board whose whole claim is
     * that you can tell those apart.
     *
     * Satellite replacing satellite, or self-report replacing self-report, both take the
     * newer. Neither kind is ever blanked by a report carrying no position at all.
     */
    private fun betterFix(existing: IncidentCluster, envelope: Envelope): Fix {
        val held = Fix(existing.gpsLat, existing.gpsLon, existing.gpsSource)
        val arriving = Fix(envelope.gpsLat, envelope.gpsLon, envelope.gpsSource)
        if (arriving.source == null) return held
        if (held.source == FixSource.SATELLITE && arriving.source == FixSource.SELF_REPORTED) return held
        return arriving
    }
}
