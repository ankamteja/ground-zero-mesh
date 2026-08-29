package org.groundzero.mesh.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import org.groundzero.mesh.agent.NodeAgent
import org.groundzero.mesh.app.NodeIdStore
import org.groundzero.mesh.app.mesh.MeshStack
import org.groundzero.mesh.app.transport.GossipOriginTransport
import org.groundzero.mesh.app.transport.NearbyTransport
import org.groundzero.mesh.app.node.MeshRole
import org.groundzero.mesh.app.node.RoleStore
import org.groundzero.mesh.app.sensors.GpsBridge
import org.groundzero.mesh.app.sensors.SensorBridge
import org.groundzero.mesh.app.transport.StoreAndForward
import org.groundzero.mesh.propagation.Gossip

/**
 * Keeps the mesh alive with the screen off.
 *
 * A phone that stops advertising/scanning when it sleeps is invisible to the mesh. This
 * service runs foreground (type `connectedDevice`) and holds a partial wake lock so the
 * radios keep working. On OEM-throttled devices, also keep the app in the foreground
 * during a demo — the wake lock is necessary, not always sufficient.
 *
 * It also owns the mesh stack: the transport, [Gossip], and the [NodeAgent] wired together
 * and published through [MeshStack] for the UI and the gateway server to borrow.
 *
 * **Known gap:** [NodeAgent.livenessTick] is not driven. It expects a
 * `MutableMap<NodeId, Peer>`, and the app keeps peers in [org.groundzero.mesh.app.transport.PeerTable]
 * instead; that table's own `decayTick` runs in [maintenance], so peers still decay and go
 * SILENT on time. The agent's copy of that concern is simply unused here.
 */
class MeshForegroundService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var transport: NearbyTransport? = null
    private var sensors: SensorBridge? = null
    private var gps: GpsBridge? = null
    val storeAndForward = StoreAndForward()

    private val maintenance = object : Runnable {
        override fun run() {
            transport?.peers?.decayTick()
            storeAndForward.sweep()
            handler.postDelayed(this, MAINTENANCE_INTERVAL_MS)
        }
    }

    /**
     * The agent's own cadence. Separate from [maintenance] on purpose — sensing, talking and
     * peer upkeep are independently tunable in [NodeAgent], and merging them here would
     * rebuild the lockstep loop that class exists to avoid.
     *
     * A calm node's heartbeat emits nothing, so this ticker is nearly free when there is
     * nothing to say.
     */
    private val heartbeat = object : Runnable {
        override fun run() {
            MeshStack.heartbeatTick()
            handler.postDelayed(this, NodeAgent.HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false); acquire() }

        val localId = NodeIdStore.get(this)
        val radio = NearbyTransport(this, localId)
        transport = radio

        val clock = { System.currentTimeMillis() }
        val gossip = Gossip(radio, clockMs = clock)
        val agent = NodeAgent(
            nodeId = localId,
            saltFingerprint = NodeIdStore.saltFingerprint(localId),
            // TODO: a responder-entered zone. Localisation is not solved, and a fabricated
            // coordinate here would read as solved on the dashboard.
            addressZone = UNSET_ZONE,
            transport = GossipOriginTransport(radio, gossip) { envelope, frame ->
                storeAndForward.offer(envelope.addressZone, envelope.dedupKey, frame)
            },
            clockMs = clock,
        )
        MeshStack.install(gossip, agent, clock, storeAndForward, radio.peers)

        radio.onReceive { from, frame -> MeshStack.ingest(frame, from) }
        radio.onPeerConnected { peer -> replayTo(radio, peer) }
        radio.start()

        sensors = SensorBridge(this, handler)
        gps = GpsBridge(this) { lat, lon -> MeshStack.updateGpsFix(lat, lon) }
        // Restore the role the responder had picked before this instance existed — MeshStack
        // otherwise comes up as NODE regardless of what the (possibly still-running) UI shows.
        MeshStack.setRole(RoleStore.get(this))
        MeshStack.onRoleChange { applyRole(it) }
        applyRole(MeshStack.currentRole())

        handler.postDelayed(maintenance, MAINTENANCE_INTERVAL_MS)
        handler.postDelayed(heartbeat, NodeAgent.HEARTBEAT_INTERVAL_MS)
        Log.i(TAG, "mesh service up as $localId")
    }

    /**
     * Hand a reconnected peer what it missed.
     *
     * The peer cannot ask for this: it does not know what it did not hear. The receiver's
     * gossip layer drops anything it already holds, so an over-generous replay costs one
     * frame per report rather than a flood.
     */
    private fun replayTo(radio: NearbyTransport, peer: org.groundzero.mesh.propagation.NodeId) {
        val frames = MeshStack.bufferedFrames()
        if (frames.isEmpty()) return
        frames.forEach { runCatching { radio.send(it, peer) } }
        Log.i(TAG, "replayed ${frames.size} buffered frame(s) to $peer")
    }

    /**
     * Start and stop what the role actually needs.
     *
     * Only a NODE senses: a RELAY left in a stairwell should spend its battery carrying other
     * people's reports, and a GATEWAY is a responder's phone at the perimeter rather than a
     * casualty's. Gossip keeps running in every role — a phone that stops relaying has left
     * the mesh.
     */
    private fun applyRole(role: MeshRole) {
        if (role == MeshRole.NODE) {
            sensors?.start()
            gps?.start()
        } else {
            sensors?.stop()
            gps?.stop()
        }
        RoleStore.set(this, role)
        Log.i(TAG, "role is now $role")
    }

    /**
     * Also the retry point for a GPS permission granted after the service was already
     * running: [GpsBridge.start] silently did nothing the first time if the permission was
     * missing, and `MeshForegroundService.start(context)` is exactly what the victim
     * screen's own permission-grant callback calls (mirroring the same nudge
     * `MainActivity` already does for the Nearby permissions) — so `onStartCommand` runs
     * again and gets another shot at it. Cheap: [GpsBridge.start] no-ops if already running.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (MeshStack.currentRole() == MeshRole.NODE) gps?.start()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(maintenance)
        handler.removeCallbacks(heartbeat)
        sensors?.stop()
        sensors = null
        gps?.stop()
        gps = null
        MeshStack.onRoleChange(null)
        MeshStack.clear()
        transport?.stop()
        transport = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Mesh", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Keeps offline peer discovery running"
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Ground-Zero Mesh")
            .setContentText("Discovering nearby nodes")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "MeshService"
        private const val CHANNEL_ID = "mesh"
        private const val NOTIF_ID = 1
        private const val WAKE_LOCK_TAG = "groundzero:mesh"
        private const val MAINTENANCE_INTERVAL_MS = 30_000L

        /** No zone until a responder can enter one. See the `addressZone` TODO in onCreate. */
        const val UNSET_ZONE = "unset"

        fun start(context: Context) {
            val intent = Intent(context, MeshForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MeshForegroundService::class.java))
        }
    }
}
