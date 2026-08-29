package org.groundzero.mesh.app.gateway

import org.groundzero.mesh.propagation.EpistemologyTier
import org.groundzero.mesh.propagation.Severity

/**
 * One report as it reaches the gateway — a folded envelope from L2. The gateway groups
 * these into [SurvivorCluster]s; a responder never sees the raw stream.
 */
data class SurvivorReport(
    val originNodeId: String,
    val zone: String,
    val severity: Severity,
    /** The tier the holder can rely on — already downgraded for relay hops. */
    val effectiveTier: EpistemologyTier,
    val dangerScore: Double,
    val incidentSeconds: Long,
    val receivedSeconds: Long,
)

/**
 * A grouped, ranked survivor cluster. `zone` is a coarse location proxy, not a coordinate
 * — localisation is an open assumption, and the dashboard says so.
 */
data class SurvivorCluster(
    val clusterId: String,
    val zone: String,
    val severity: Severity,
    val effectiveTier: EpistemologyTier,
    /** Distinct origin nodes that reported into this cluster. */
    val corroboration: Int,
    val dangerScore: Double,
    val lastSeenSecondsAgo: Long,
    /** Raw reports folded in — shown as a number, never as a list of alerts. */
    val reportCount: Int,
    /** 1..budget for clusters inside the action budget; null for the rest. */
    val recommendedActionRank: Int?,
)
