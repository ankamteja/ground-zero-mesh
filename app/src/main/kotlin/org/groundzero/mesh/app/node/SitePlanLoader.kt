package org.groundzero.mesh.app.node

import android.content.Context
import android.util.Log

/**
 * Reads the bundled site plan once and hands the same one to everybody afterwards.
 *
 * Cached because both the victim screen and [org.groundzero.mesh.app.service.MeshForegroundService]
 * want it, and re-parsing an asset on a phone that is trying to stay alive on its last battery
 * is work for nothing.
 *
 * A missing or malformed plan is not fatal and must not be. The map is an *extra* way to say
 * where you are; the SOS button works without it, and a build that shipped without a plan
 * should still send for help. [load] returns null, the picker is simply not shown, and the
 * reason lands in logcat for whoever packaged the build.
 */
object SitePlanLoader {

    private const val TAG = "SitePlan"

    @Volatile private var cached: SitePlan? = null
    @Volatile private var tried = false

    fun load(context: Context): SitePlan? {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            if (tried) return null
            tried = true
            return try {
                val text = context.applicationContext.assets
                    .open(SitePlan.ASSET_PATH)
                    .bufferedReader()
                    .use { it.readText() }
                SitePlan.parse(text).also {
                    cached = it
                    Log.i(TAG, "loaded '${it.name}' with ${it.zones.size} zone(s)")
                }
            } catch (e: Exception) {
                // Includes both "no plan bundled" and "the plan does not parse". Neither is
                // recoverable here and neither should stop an SOS.
                Log.w(TAG, "no usable site plan — the map picker will be hidden: ${e.message}")
                null
            }
        }
    }
}
