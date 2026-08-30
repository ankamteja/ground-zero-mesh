package org.groundzero.mesh.app.node

import android.content.Context

/**
 * Persists whether the responder asked for the board to be served.
 *
 * The exact counterpart of [RoleStore], for the same reason and the same failure. The role
 * survives a service restart so "what the screen claims is what actually runs again" — but
 * the responder *server* did not. `GatewayController.start` was only ever reachable from
 * `ResponderScreen`'s button, so when Android reclaimed the process (a locked screen and some
 * memory pressure is enough), `MeshForegroundService` came back on `START_STICKY`, restored
 * the GATEWAY role, kept relaying — and served nothing. The phone still read "responder"; the
 * laptop dashboard simply went dead, with no indication on either that anything had stopped.
 *
 * `ResponderScreen`'s own doc already states the intent this closes: the server is
 * deliberately not stopped when the screen leaves composition, because "a responder whose
 * board dies because they put their phone in a pocket has lost the incident view mid-rescue."
 * A process death is that same pocket, one step further.
 *
 * This records intent, not liveness. Leaving the gateway role stops the server but keeps the
 * intent, so returning to the role resumes serving rather than making the responder ask twice.
 */
object GatewayStore {
    private const val PREFS = "ground_zero_mesh"
    private const val KEY = "gateway_serving"

    /** False until a responder has started the board at least once. */
    fun isServing(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY, false)

    fun setServing(context: Context, serving: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, serving)
            .apply()
    }
}
