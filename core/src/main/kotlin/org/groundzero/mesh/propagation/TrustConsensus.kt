package org.groundzero.mesh.propagation

/**
 * Trust-weighted agreement across peers.
 *
 * Two ported rules, both load-bearing:
 *
 * 1. `cooperative = localWeight x local + (1 - localWeight) x trust-weighted peer average`.
 *    A node keeps majority say over its own conclusion. It listens; it does not defer.
 * 2. Trust **decays faster than it builds** — [TRUST_GAIN] 0.02 against [TRUST_LOSS] 0.05.
 *    That asymmetry is the entire defence against a spoofed or faulty node: it must behave
 *    well for a long time to earn influence, and can lose it in a handful of bad reports.
 *
 * The asymmetry is exercised adversarially in `TrustConsensusTest` rather than assumed
 * correct because the constants were copied accurately. Copying a formula right and
 * wiring it up wrong is the failure mode that looks most like success.
 */
class TrustConsensus(
    private val localWeight: Double = DEFAULT_LOCAL_WEIGHT,
    private val trustGain: Double = TRUST_GAIN,
    private val trustLoss: Double = TRUST_LOSS,
) {
    init {
        require(localWeight in 0.0..1.0) { "localWeight must be 0..1" }
        require(trustLoss > trustGain) {
            "trust must decay faster than it builds, or a bad node can outlast its own reputation"
        }
    }

    private val trust = LinkedHashMap<NodeId, Double>()

    /** Unknown peers start neutral: neither believed nor disbelieved. */
    fun trustOf(node: NodeId): Double = trust[node] ?: NEUTRAL_TRUST

    fun knownPeers(): Set<NodeId> = trust.keys.toSet()

    /**
     * A peer's report agreed with what we independently concluded.
     *
     * Gain is proportional to the room left (`1 - t`), so trust approaches 1.0 without ever
     * reaching it. Nobody is ever fully believed.
     */
    fun reinforce(node: NodeId) {
        val t = trustOf(node)
        trust[node] = (t + trustGain * (1.0 - t)).coerceIn(0.0, 1.0)
    }

    /**
     * A peer's report contradicted first-hand observation, duplicated a claim it should not
     * have, or was otherwise not borne out.
     *
     * Loss is proportional to the trust held, so a well-trusted node has more to lose —
     * which is what makes a long con expensive rather than free.
     */
    fun penalise(node: NodeId) {
        val t = trustOf(node)
        trust[node] = (t - trustLoss * t).coerceIn(0.0, 1.0)
    }

    /**
     * Blend this node's own conclusion with what its peers report.
     *
     * Peers are weighted by trust, so an untrusted node's contribution tends to zero
     * without needing to be explicitly excluded — it is ignored by arithmetic rather than
     * by a rule that could be got wrong.
     */
    fun cooperativeScore(localScore: Double, peerScores: Map<NodeId, Double>): Double {
        require(localScore in 0.0..1.0) { "localScore must be 0..1" }
        if (peerScores.isEmpty()) return localScore

        var weighted = 0.0
        var weights = 0.0
        for ((peer, score) in peerScores) {
            val w = trustOf(peer)
            weighted += w * score.coerceIn(0.0, 1.0)
            weights += w
        }
        if (weights == 0.0) return localScore

        val peerView = weighted / weights
        return (localWeight * localScore + (1.0 - localWeight) * peerView).coerceIn(0.0, 1.0)
    }

    /** Snapshot for the debug view and the port ledger's adversarial test. */
    fun snapshot(): Map<NodeId, Double> = trust.toMap()

    companion object {
        const val NEUTRAL_TRUST = 0.5
        const val DEFAULT_LOCAL_WEIGHT = 0.6

        /** Ported verbatim. */
        const val TRUST_GAIN = 0.02

        /** Ported verbatim, and deliberately larger than the gain. */
        const val TRUST_LOSS = 0.05
    }
}
