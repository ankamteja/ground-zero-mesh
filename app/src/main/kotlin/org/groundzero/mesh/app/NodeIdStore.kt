package org.groundzero.mesh.app

import android.content.Context
import org.groundzero.mesh.propagation.NodeId

/** Persists this device's 48-bit [NodeId] so it is stable across restarts. */
object NodeIdStore {
    private const val PREFS = "ground_zero_mesh"
    private const val KEY = "node_id"

    fun get(context: Context): NodeId {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getLong(KEY, -1L)
        if (existing in 0L..NodeId.MAX_VALUE) return NodeId(existing)
        val fresh = NodeId.random()
        prefs.edit().putLong(KEY, fresh.value).apply()
        return fresh
    }
}
