package org.groundzero.mesh.propagation

/**
 * Liveness of a peer as seen from one node.
 *
 * Only [SILENT] is reachable by a timer. [GONE] means someone *told us* the peer is gone
 * (explicit shutdown notice, or a responder marking a cluster cleared) — it is never
 * inferred from silence, because a phone under a collapsed floor, OEM-throttled, on its
 * last 4% of battery is silent for minutes and its owner is alive.
 */
enum class PeerLiveness { ALIVE, SILENT, GONE }

/**
 * One row of the peer table: `{node_id, address, last_seen, ok, fail}` plus an explicit
 * gone flag.
 *
 * Health rule: `fail < 5 || ok > 0`. Peers decay toward neutral on silence; they are
 * never dropped from the table just for going quiet.
 */
data class Peer(
    val nodeId: NodeId,
    val address: String,
    val lastSeenMs: Long,
    val ok: Int = 0,
    val fail: Int = 0,
    val explicitlyGone: Boolean = false,
) {
    val healthy: Boolean get() = fail < 5 || ok > 0

    fun liveness(nowMs: Long): PeerLiveness = when {
        explicitlyGone -> PeerLiveness.GONE
        nowMs - lastSeenMs >= SILENT_AFTER_MS -> PeerLiveness.SILENT
        else -> PeerLiveness.ALIVE
    }

    /** A frame arrived from this peer. */
    fun sawInbound(nowMs: Long): Peer =
        copy(lastSeenMs = nowMs, ok = ok + 1, fail = maxOf(0, fail - 1), explicitlyGone = false)

    /** A send to this peer failed. */
    fun sendFailed(): Peer = copy(fail = fail + 1)

    /** Periodic pull toward neutral. Never removes the peer. */
    fun decayedToward(neutral: Int = 0): Peer = copy(
        ok = ok.pullToward(neutral),
        fail = fail.pullToward(neutral),
    )

    /** Someone reported this peer as gone. Distinct from a silence timeout. */
    fun markGone(): Peer = copy(explicitlyGone = true)

    private fun Int.pullToward(target: Int): Int = when {
        this > target -> this - 1
        this < target -> this + 1
        else -> this
    }

    companion object {
        /**
         * Silence threshold before a peer is considered [PeerLiveness.SILENT].
         *
         * The reference implementation (ANW) declared a peer dead after 6 seconds. That is
         * catastrophically wrong here: reporting a live, trapped survivor as gone is the
         * worst error this system can make. Do not lower this.
         */
        const val SILENT_AFTER_MS: Long = 4L * 60L * 1000L
    }
}
