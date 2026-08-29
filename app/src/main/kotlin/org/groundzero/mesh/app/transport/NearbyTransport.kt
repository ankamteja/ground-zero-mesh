package org.groundzero.mesh.app.transport

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.groundzero.mesh.propagation.NodeId
import org.groundzero.mesh.transport.Transport
import java.util.concurrent.ConcurrentHashMap

/**
 * [Transport] over Google Nearby Connections, `P2P_CLUSTER`.
 *
 * `P2P_CLUSTER` does BLE discovery and auto-upgrades the data channel to Wi-Fi Direct /
 * local WLAN, which sidesteps BLE GATT payload limits without hand-rolled GATT work.
 *
 * Peer identity: each node advertises with its [NodeId] canonical string as the endpoint
 * name. Nearby's own endpoint ids are opaque and per-session, so this class keeps a
 * two-way map between them and [NodeId].
 *
 * Connection policy is symmetric auto-accept — any discovered endpoint on the same service
 * id is connected to. That matches the trust model here (team-owned phones form the mesh;
 * consensus and trust live at L2, not L0). It does mean L0 does no authentication.
 *
 * NOT covered by unit tests — needs two physical devices. See `PeerTable` / `StoreAndForward`
 * for the parts that are testable in isolation.
 */
class NearbyTransport(
    context: Context,
    override val localId: NodeId,
    private val serviceId: String = DEFAULT_SERVICE_ID,
) : Transport {

    override val maxFrameBytes: Int = MAX_BYTES_PAYLOAD

    val peers = PeerTable()

    private val appContext = context.applicationContext
    private val connections: ConnectionsClient = Nearby.getConnectionsClient(appContext)

    private val endpointToNode = ConcurrentHashMap<String, NodeId>()
    private val nodeToEndpoint = ConcurrentHashMap<NodeId, String>()

    @Volatile
    private var listener: ((from: NodeId, frame: ByteArray) -> Unit)? = null

    @Volatile
    private var peerConnected: ((NodeId) -> Unit)? = null

    @Volatile
    private var running = false

    override fun start() {
        if (running) return
        running = true
        startAdvertising()
        startDiscovery()
    }

    override fun stop() {
        running = false
        runCatching { connections.stopAdvertising() }
        runCatching { connections.stopDiscovery() }
        runCatching { connections.stopAllEndpoints() }
        endpointToNode.clear()
        nodeToEndpoint.clear()
    }

    override fun send(frame: ByteArray, to: NodeId?) {
        check(running) { "transport not started" }
        require(frame.size <= maxFrameBytes) { "frame ${frame.size} > $maxFrameBytes" }
        val payload = Payload.fromBytes(frame)
        if (to == null) {
            val targets = endpointToNode.keys.toList()
            if (targets.isNotEmpty()) {
                connections.sendPayload(targets, payload)
                    .addOnFailureListener { targets.forEach { ep -> endpointToNode[ep]?.let(peers::sendFailed) } }
            }
        } else {
            val endpoint = nodeToEndpoint[to] ?: run {
                peers.sendFailed(to)
                Log.w(TAG, "send: no live endpoint for $to")
                return
            }
            connections.sendPayload(endpoint, payload)
                .addOnFailureListener { peers.sendFailed(to) }
        }
    }

    override fun onReceive(listener: (from: NodeId, frame: ByteArray) -> Unit) {
        this.listener = listener
    }

    override fun knownPeers(): List<NodeId> = endpointToNode.values.toList()

    /**
     * A peer just finished connecting.
     *
     * This is the store-and-forward moment: a node that was out of range for ten minutes
     * comes back and needs what it missed, and nothing else in the stack knows when that
     * happened.
     */
    fun onPeerConnected(listener: (NodeId) -> Unit) {
        this.peerConnected = listener
    }

    // --- Nearby plumbing ---

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connections.startAdvertising(localId.canonical(), serviceId, lifecycleCallback, options)
            .addOnFailureListener { Log.e(TAG, "startAdvertising failed", it) }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connections.startDiscovery(serviceId, discoveryCallback, options)
            .addOnFailureListener { Log.e(TAG, "startDiscovery failed", it) }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != serviceId) return
            // Deterministic initiator: the lexicographically smaller NodeId requests, so
            // two nodes that discover each other at the same time don't both dial.
            val remote = runCatching { NodeId.parse(info.endpointName) }.getOrNull() ?: return
            if (localId.canonical() < remote.canonical()) {
                connections.requestConnection(localId.canonical(), endpointId, lifecycleCallback)
                    .addOnFailureListener { Log.w(TAG, "requestConnection to $endpointId failed", it) }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            // Endpoint out of radio range. Drop the session mapping; the PeerTable row
            // stays and decays — a lost endpoint is SILENT, not GONE.
            endpointToNode.remove(endpointId)?.let { nodeToEndpoint.remove(it) }
        }
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Symmetric auto-accept. No auth-token comparison — see class doc.
            connections.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { Log.w(TAG, "acceptConnection $endpointId failed", it) }
            runCatching { NodeId.parse(info.endpointName) }.getOrNull()?.let { node ->
                endpointToNode[endpointId] = node
                nodeToEndpoint[node] = endpointId
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                val node = endpointToNode[endpointId]
                if (node != null) {
                    peers.sawInbound(node, endpointId)
                    peerConnected?.invoke(node)
                }
            } else {
                endpointToNode.remove(endpointId)?.let { nodeToEndpoint.remove(it) }
            }
        }

        override fun onDisconnected(endpointId: String) {
            endpointToNode.remove(endpointId)?.let { nodeToEndpoint.remove(it) }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            val from = endpointToNode[endpointId] ?: return
            peers.sawInbound(from, endpointId)
            listener?.invoke(from, bytes)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.FAILURE) {
                endpointToNode[endpointId]?.let(peers::sendFailed)
            }
        }
    }

    companion object {
        private const val TAG = "NearbyTransport"
        const val DEFAULT_SERVICE_ID = "org.groundzero.mesh"
        private val STRATEGY = Strategy.P2P_CLUSTER

        /** Nearby BYTES payloads are capped near 32 KiB; larger needs STREAM/FILE. Well
         *  above [org.groundzero.mesh.propagation.Codecs.JSON_MIN_BUDGET], so this
         *  transport uses the JSON projection. */
        const val MAX_BYTES_PAYLOAD = 32 * 1024
    }
}
