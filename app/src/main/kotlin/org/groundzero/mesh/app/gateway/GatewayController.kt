package org.groundzero.mesh.app.gateway

import android.content.Context
import android.util.Log
import org.groundzero.mesh.app.mesh.MeshStack
import org.groundzero.mesh.gateway.RankedIncident
import org.groundzero.mesh.propagation.NodeId
import java.io.IOException

/**
 * Lifecycle wrapper for the [GatewayServer]. The Gateway role starts this; other roles
 * never touch it.
 *
 * [clusterSource] is `MeshStack::rankedBoard` in the app — the ranked view of whatever the
 * service's [org.groundzero.mesh.propagation.Gossip] currently holds. It is read on a
 * NanoHTTPD worker thread, which is why `MeshStack` serialises access rather than assuming
 * one.
 */
object GatewayController {

    @Volatile
    private var server: GatewayServer? = null

    val isRunning: Boolean get() = server != null

    fun start(
        context: Context,
        port: Int = GatewayServer.DEFAULT_PORT,
        clusterSource: () -> List<RankedIncident>,
        onMarkFound: (NodeId) -> Unit = MeshStack::markPeerFound,
        localNodeId: () -> NodeId? = MeshStack::localNodeId,
    ) {
        if (server != null) return
        val assets = context.applicationContext.assets
        val srv = GatewayServer(
            port = port,
            readAsset = { name ->
                runCatching { assets.open("dashboard/$name").use { it.readBytes() } }.getOrNull()
            },
            clustersNow = clusterSource,
            onMarkFound = onMarkFound,
            localNodeId = localNodeId,
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
