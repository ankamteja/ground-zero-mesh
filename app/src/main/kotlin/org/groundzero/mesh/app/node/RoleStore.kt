package org.groundzero.mesh.app.node

import android.content.Context

/**
 * Persists the device's chosen [MeshRole] across a service restart.
 *
 * [MeshStack][org.groundzero.mesh.app.mesh.MeshStack] resets to [MeshRole.NODE] whenever
 * `MeshForegroundService` is torn down and rebuilt — an OEM killing the process while the
 * Activity survives, say. Without this, the freshly-rebuilt stack silently originates and
 * senses as NODE while the UI, which was never torn down, still shows whatever role the
 * responder had picked. `MeshForegroundService.onCreate` reads this back before the stack
 * starts doing anything, so what the screen claims is what actually runs again.
 */
object RoleStore {
    private const val PREFS = "ground_zero_mesh"
    private const val KEY = "mesh_role"

    fun get(context: Context): MeshRole {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?: return MeshRole.NODE
        return runCatching { MeshRole.valueOf(raw) }.getOrDefault(MeshRole.NODE)
    }

    fun set(context: Context, role: MeshRole) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, role.name)
            .apply()
    }
}
