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
import org.groundzero.mesh.app.transport.LanRelayTransport
import org.groundzero.mesh.app.transport.NearbyTransport
import org.groundzero.mesh.app.transport.RadioTransport
import org.groundzero.mesh.app.node.MeshRole
import org.groundzero.mesh.app.node.RelayHostStore
import org.groundzero.mesh.app.node.RoleStore
import org.groundzero.mesh.app.node.SelfPositionStore
import org.groundzero.mesh.app.node.SitePlanLoader
import org.groundzero.mesh.app.sensors.AudioBridge
import org.groundzero.mesh.app.sensors.GpsBridge
import org.groundzero.mesh.app.sensors.SensorBridge
import org.groundzero.mesh.app.transport.StoreAndForward
import org.groundzero.mesh.propagation.Gossip
import org.groundzero.mesh.propagation.NodeId
import org.groundzero.mesh.transport.TcpTransport

/**
 * Keeps the mesh alive with the screen off.
 *
 * A phone that stops advertising/scanning when it sleeps is invisible to the mesh. This
 * service runs foreground (type `connectedDevice|microphone` — the microphone half is what
 * lets [AudioBridge] keep recording with the app in the background) and holds a partial wake
 * lock so the radios keep working. On OEM-throttled devices, also keep the app in the foreground
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
    private var transport: RadioTransport? = null
    private var sensors: SensorBridge? = null
    private var gps: GpsBridge? = null
    private var audio: AudioBridge? = null
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
        val radio = buildRadio(localId)
        transport = radio

        val clock = { System.currentTimeMillis() }
        val gossip = Gossip(radio, clockMs = clock)
        // The zone the person last marked on the site plan, if they ever did. Still
        // UNSET_ZONE otherwise — the one thing that must not happen here is a zone nobody
        // chose, which would read on the dashboard as a located casualty.
        val markedZone = SelfPositionStore.get(this)?.zone?.takeIf { it.isNotBlank() }

        val agent = NodeAgent(
            nodeId = localId,
            saltFingerprint = NodeIdStore.saltFingerprint(localId),
            addressZone = markedZone ?: UNSET_ZONE,
            transport = GossipOriginTransport(radio, gossip) { envelope, frame ->
                storeAndForward.offer(envelope.addressZone, envelope.dedupKey, frame)
            },
            clockMs = clock,
        )
        MeshStack.install(gossip, agent, clock, storeAndForward, radio.peers)

        radio.onReceive { from, frame -> MeshStack.ingest(frame, from) }
        radio.onPeerConnected { peer -> replayTo(radio, peer) }
        radio.start()

        // Built before the sensor bridge because the bridge pulls from it on every tick.
        val microphone = AudioBridge(this)
        audio = microphone
        sensors = SensorBridge(this, handler) { microphone.latest }
        gps = GpsBridge(this) { lat, lon -> MeshStack.updateGpsFix(lat, lon) }

        // Put a previously marked position back on the agent. The mark is stored in plan
        // space, so it is converted here through whatever georeference the *current* plan
        // carries — a deployment that corrects its reference corners moves every stored mark
        // with it, instead of leaving old coordinates quietly wrong.
        SelfPositionStore.get(this)?.let { mark ->
            val plan = SitePlanLoader.load(this)
            if (plan == null) {
                // The plan that produced this mark is gone, so plan space no longer means
                // anything and the mark cannot honestly be converted.
                Log.w(TAG, "a marked position is stored but no site plan is bundled — ignoring it")
            } else {
                val (lat, lon) = plan.georeference.toLatLon(mark.planX, mark.planY)
                MeshStack.updateSelfReportedPosition(lat.toFloat(), lon.toFloat(), mark.zone)
            }
        }
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
     * [NearbyTransport], or — when [RelayHostStore] holds a host — [LanRelayTransport]
     * pointed at a laptop running `:core:runRelay` instead. The two are interchangeable
     * from here down: everything else in this class only ever holds a [RadioTransport].
     */
    private fun buildRadio(localId: NodeId): RadioTransport {
        val relayHost = RelayHostStore.get(this)
        if (relayHost.isBlank()) return NearbyTransport(this, localId)
        val (host, port) = parseRelayHost(relayHost)
        Log.i(TAG, "LAN relay mode: connecting to $host:$port instead of Nearby")
        return LanRelayTransport(TcpTransport(host, port, localId))
    }

    /** `host` or `host:port`; an unparseable or missing port falls back to
     *  [RelayHostStore.DEFAULT_PORT] rather than refusing to start. */
    private fun parseRelayHost(raw: String): Pair<String, Int> {
        val colon = raw.lastIndexOf(':')
        val port = if (colon >= 0) raw.substring(colon + 1).toIntOrNull() else null
        return if (port == null) raw to RelayHostStore.DEFAULT_PORT
        else raw.substring(0, colon) to port
    }

    /**
     * Hand a reconnected peer what it missed.
     *
     * The peer cannot ask for this: it does not know what it did not hear. The receiver's
     * gossip layer drops anything it already holds, so an over-generous replay costs one
     * frame per report rather than a flood.
     */
    private fun replayTo(radio: RadioTransport, peer: NodeId) {
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
            audio?.start()
            sensors?.start()
            gps?.start()
        } else {
            sensors?.stop()
            gps?.stop()
            // A relay in a stairwell has no business holding the microphone open: it cannot
            // originate a report, so nothing downstream could ever read what it heard.
            audio?.stop()
        }
        RoleStore.set(this, role)
        Log.i(TAG, "role is now $role")
    }

    /**
     * Also the retry point for a GPS permission granted after the service was already
     * running — and, since the microphone landed, for a `RECORD_AUDIO` grant too:
     * [GpsBridge.start] and [AudioBridge.start] both silently do nothing the first time if
     * their permission is missing, and `MeshForegroundService.start(context)` is what the victim
     * screen's own permission-grant callback calls (mirroring the same nudge
     * `MainActivity` already does for the Nearby permissions) — so `onStartCommand` runs
     * again and gets another shot at it. Cheap: [GpsBridge.start] no-ops if already running.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (MeshStack.currentRole() == MeshRole.NODE) {
            gps?.start()
            // Same retry path, same reason: the victim screen can grant the microphone after
            // the service is already up, and both bridges no-op when already running.
            audio?.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(maintenance)
        handler.removeCallbacks(heartbeat)
        sensors?.stop()
        sensors = null
        gps?.stop()
        gps = null
        audio?.stop()
        audio = null
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
