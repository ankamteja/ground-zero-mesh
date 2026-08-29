package org.groundzero.mesh.app

import android.content.Context
import org.groundzero.mesh.propagation.NodeId
import java.security.MessageDigest

/** Persists this device's 48-bit [NodeId] so it is stable across restarts. */
object NodeIdStore {
    private const val PREFS = "ground_zero_mesh"
    private const val KEY = "node_id"

    /**
     * The 32 hex chars [org.groundzero.mesh.propagation.Envelope] requires, derived from the
     * node id rather than stored.
     *
     * Derived, not random, because it must be stable across restarts and reproducible from
     * the id alone. It is therefore a *fingerprint of the identity*, not a secret: it
     * distinguishes two nodes claiming the same id, and nothing more. A real per-device salt
     * — one that would make the id itself unlinkable — needs its own store and a decision
     * about who may re-link it, which is not made yet.
     */
    fun saltFingerprint(id: NodeId): String =
        MessageDigest.getInstance("SHA-256")
            .digest(id.canonical().toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)

    fun get(context: Context): NodeId {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getLong(KEY, -1L)
        if (existing in 0L..NodeId.MAX_VALUE) return NodeId(existing)
        val fresh = NodeId.random()
        prefs.edit().putLong(KEY, fresh.value).apply()
        return fresh
    }
}
