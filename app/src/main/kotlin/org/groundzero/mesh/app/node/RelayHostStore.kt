package org.groundzero.mesh.app.node

import android.content.Context
import org.groundzero.mesh.app.transport.LanRelayTransport
import org.groundzero.mesh.app.transport.NearbyTransport

/**
 * Persists the laptop-relay host this phone should connect to instead of Nearby, if any.
 *
 * Blank (the default) means "use the real radio" — `MeshForegroundService` reads this
 * once, in `onCreate`, the same moment it reads [RoleStore], and picks [NearbyTransport] or
 * [LanRelayTransport] accordingly. Changing it takes effect the next time the service is
 * (re)created, not live — a full transport swap is a bigger change than a role flip and is
 * not worth the complexity of tearing down and rebuilding the whole mesh stack while it is
 * running.
 */
object RelayHostStore {
    private const val PREFS = "ground_zero_mesh"
    private const val KEY = "relay_host"

    /** The port [org.groundzero.mesh.relay.TcpRelayMain] listens on by default. */
    const val DEFAULT_PORT = 7777

    fun get(context: Context): String =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "")
            ?.trim()
            ?: ""

    fun set(context: Context, host: String) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, host.trim())
            .apply()
    }
}
