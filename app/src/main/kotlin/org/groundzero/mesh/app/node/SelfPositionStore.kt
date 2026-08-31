package org.groundzero.mesh.app.node

import android.content.Context

/**
 * Where the person said they are, kept across restarts.
 *
 * Persisted for the same reason [RoleStore] is: the foreground service can be killed and
 * rebuilt under memory pressure, and the phone this runs on belongs to someone who is trapped.
 * Asking them to mark themselves again after a restart they never saw is asking for something
 * they may no longer be able to give.
 *
 * Stored in plan space, not as latitude and longitude. The plan is what they actually pointed
 * at, so it survives a corrected georeference — a deployment that discovers its two reference
 * corners were slightly off can fix the plan and every stored mark moves with it, whereas
 * stored coordinates would be silently wrong forever.
 *
 * Unset is the default and is a real state, not a missing value: it means nobody has answered
 * the question, and the SOS goes out with no self-reported position rather than a guess.
 */
object SelfPositionStore {
    private const val PREFS = "ground_zero_mesh"
    private const val KEY_X = "self_pos_x"
    private const val KEY_Y = "self_pos_y"
    private const val KEY_ZONE = "self_pos_zone"

    /** A mark on the plan: where it was, and the zone it landed in (blank for open ground). */
    data class Mark(val planX: Float, val planY: Float, val zone: String)

    private const val UNSET = Float.MIN_VALUE

    fun get(context: Context): Mark? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val x = prefs.getFloat(KEY_X, UNSET)
        val y = prefs.getFloat(KEY_Y, UNSET)
        if (x == UNSET || y == UNSET) return null
        return Mark(x, y, prefs.getString(KEY_ZONE, "").orEmpty())
    }

    fun set(context: Context, mark: Mark) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_X, mark.planX)
            .putFloat(KEY_Y, mark.planY)
            .putString(KEY_ZONE, mark.zone)
            .apply()
    }

    fun clear(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_X)
            .remove(KEY_Y)
            .remove(KEY_ZONE)
            .apply()
    }
}
