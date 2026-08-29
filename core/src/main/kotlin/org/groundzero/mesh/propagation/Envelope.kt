package org.groundzero.mesh.propagation

/**
 * The one unit that crosses the mesh. Same object on every hop and every transport; it has
 * two wire projections ([JsonCodec] for phone-to-phone, [CompactCodec] for LoRa), chosen
 * from the transport's frame budget via [Codecs.forFrameBudget].
 *
 * Three things that bite if skipped:
 *
 * 1. [dedupKey] is `nodeId + timestamp`. A refined Stage-3 broadcast intentionally reuses
 *    the original incident timestamp, so downstream layers update the existing cluster
 *    instead of raising a second alert for the same trapped person. Do not make timestamps
 *    unique per broadcast.
 * 2. [effectiveTier] downgrades a relayed first-hand claim to testimony. [tier] keeps the
 *    origin's claim; anyone holding this at `hops > 0` only has hearsay. UIs show the
 *    effective tier.
 * 3. The byte ceilings are enforced here, in the constructor. An envelope that could not
 *    fit a LoRa frame fails at construction, not at transmit time on a rooftop. Hitting a
 *    `require` below is the design working — do not raise the limit.
 */
data class Envelope(
    val nodeId: NodeId,
    /** 32 hex chars. */
    val saltFingerprint: String,
    /** <= 24 chars. */
    val addressZone: String,
    val tier: EpistemologyTier,
    val severity: Severity,
    /** 0..1. */
    val dangerScore: Double,
    /** incident time, seconds — part of [dedupKey]. */
    val timestamp: Long,
    /** <= 50 bytes UTF-8, Stage 3 only. */
    val slmSummary: String? = null,
    /** <= 4 entries. */
    val views: List<String> = emptyList(),
    /** <= 8 entries. */
    val peers: List<NodeId> = emptyList(),
    /** <= 15. */
    val hops: Int = 0,
    /** <= 15. */
    val ttl: Int = 15,
) {
    init {
        require(saltFingerprint.length == 32 && saltFingerprint.all { it.isHexChar() }) {
            "saltFingerprint must be 32 hex chars, got '${saltFingerprint}'"
        }
        require(addressZone.length <= MAX_ADDRESS_ZONE_CHARS) {
            "addressZone must be <= $MAX_ADDRESS_ZONE_CHARS chars, got ${addressZone.length}"
        }
        require(dangerScore in 0.0..1.0) { "dangerScore must be 0..1, got $dangerScore" }
        require(timestamp >= 0L) { "timestamp must be >= 0, got $timestamp" }
        slmSummary?.let {
            require(it.utf8Size() <= MAX_SLM_SUMMARY_BYTES) {
                "slmSummary must be <= $MAX_SLM_SUMMARY_BYTES bytes, got ${it.utf8Size()}"
            }
        }
        require(views.size <= MAX_VIEWS) { "views must be <= $MAX_VIEWS, got ${views.size}" }
        require(views.all { it.utf8Size() <= MAX_VIEW_BYTES }) {
            "each view must be <= $MAX_VIEW_BYTES bytes"
        }
        require(peers.size <= MAX_PEERS) { "peers must be <= $MAX_PEERS, got ${peers.size}" }
        require(hops in 0..MAX_HOPS) { "hops must be 0..$MAX_HOPS, got $hops" }
        require(ttl in 0..MAX_TTL) { "ttl must be 0..$MAX_TTL, got $ttl" }

        val frame = CompactCodec.frameSize(this)
        require(frame <= CompactCodec.LORA_MAX_FRAME) {
            "envelope does not fit a LoRa frame: $frame > ${CompactCodec.LORA_MAX_FRAME} bytes"
        }
    }

    /** Stable across the refined re-broadcast on purpose — see the class doc. */
    val dedupKey: String get() = "${nodeId.canonical()}@$timestamp"

    /** The tier a holder can actually rely on. Relayed => testimony. */
    val effectiveTier: EpistemologyTier
        get() = if (hops > 0) tier.relayed() else tier

    /** One hop further out: hop count up, ttl down. Fails if ttl is exhausted. */
    fun forwarded(addPeers: List<NodeId> = emptyList()): Envelope {
        check(ttl > 0) { "ttl exhausted, cannot forward $dedupKey" }
        val merged = (peers + addPeers).distinct().take(MAX_PEERS)
        return copy(hops = minOf(hops + 1, MAX_HOPS), ttl = ttl - 1, peers = merged)
    }

    companion object {
        const val MAX_ADDRESS_ZONE_CHARS = 24
        const val MAX_SLM_SUMMARY_BYTES = 50
        const val MAX_VIEWS = 4
        const val MAX_VIEW_BYTES = 120
        const val MAX_PEERS = 8
        const val MAX_HOPS = 15
        const val MAX_TTL = 15

        private fun Char.isHexChar() =
            this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

        internal fun String.utf8Size() = toByteArray(Charsets.UTF_8).size
    }
}
