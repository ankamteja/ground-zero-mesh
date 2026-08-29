package org.groundzero.mesh.app.gateway

import android.content.Context
import android.util.Log
import org.groundzero.mesh.gateway.RankedIncident
import java.io.IOException

/**
 * Lifecycle wrapper for the [GatewayServer]. The Gateway role starts this; other roles
 * never touch it.
 *
 * [clusterSource] is expected to be `ResponderRanking.rank(gossip.clusters(), now)` once
 * the mesh stack runs in the service (Step 2). Until then a caller may pass `{ emptyList() }`
 * and the dashboard simply shows nothing.
 */
object GatewayController {

    @Volatile
    private var server: GatewayServer? = null

    val isRunning: Boolean get() = server != null

    fun start(
        context: Context,
        port: Int = GatewayServer.DEFAULT_PORT,
        clusterSource: () -> List<RankedIncident>,
    ) {
        if (server != null) return
        val assets = context.applicationContext.assets
        val srv = GatewayServer(
            port = port,
            readAsset = { name ->
                runCatching { assets.open("dashboard/$name").use { it.readBytes() } }.getOrNull()
            },
            clustersNow = clusterSource,
        )
        try {
            srv.start()
            server = srv
            Log.i(TAG, "gateway up on :$port — join this phone's hotspot and open http://<phone-ip>:$port/")
        } catch (e: IOException) {
            Log.e(TAG, "gateway failed to bind :$port", e)
        }
    }

    fun stop() {
        server?.let { runCatching { it.stop() } }
        server = null
    }

    private const val TAG = "GatewayController"
}
