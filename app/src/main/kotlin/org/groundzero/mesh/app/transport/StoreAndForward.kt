package org.groundzero.mesh.app.transport

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * A short-lived outbox, bucketed by zone. When a node reconnects it replays every buffered
 * frame instead of forcing a full re-flood of the mesh — see [drainAll] for why replay
 * cannot be scoped to just the peer's zone.
 *
 * Frames are bucketed by `SHA256("zone:" + zoneId)` so a zone tag never appears in the
 * clear as a map key, and every frame carries a TTL. Expired frames are dropped lazily on
 * access and by [sweep].
 */
class StoreAndForward(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxPerBucket: Int = 128,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val dedupKey: String, val frame: ByteArray, val expiresAt: Long)

    private val buckets = ConcurrentHashMap<String, MutableList<Entry>>()

    fun bucketKey(zoneId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("zone:$zoneId".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** Buffer a frame for [zoneId]. [dedupKey] is the envelope's — a repeat replaces in place. */
    fun offer(zoneId: String, dedupKey: String, frame: ByteArray) {
        val key = bucketKey(zoneId)
        val list = buckets.getOrPut(key) { mutableListOf() }
        synchronized(list) {
            list.removeAll { it.dedupKey == dedupKey || it.expiresAt <= clock() }
            list.add(Entry(dedupKey, frame.copyOf(), clock() + ttlMs))
            while (list.size > maxPerBucket) list.removeAt(0)
        }
    }

    /**
     * Every un-expired frame in every bucket, oldest first.
     *
     * What a node replays to a peer that just reconnected. It cannot be done per zone,
     * because the buckets are keyed by hash and the plain zone tags are deliberately not
     * kept — and a reconnecting peer has not said which zones it missed anyway. The
     * receiver's gossip layer suppresses anything it already holds, so replaying more than
     * strictly necessary costs one frame each, not a storm.
     */
    fun drainAll(): List<ByteArray> {
        val now = clock()
        return buckets.values.flatMap { list ->
            synchronized(list) {
                list.removeAll { it.expiresAt <= now }
                list.map { it.frame.copyOf() }
            }
        }
    }

    fun sweep() {
        val now = clock()
        buckets.values.forEach { list -> synchronized(list) { list.removeAll { it.expiresAt <= now } } }
        buckets.entries.removeAll { (_, list) -> synchronized(list) { list.isEmpty() } }
    }

    companion object {
        const val DEFAULT_TTL_MS: Long = 15L * 60L * 1000L
    }
}
