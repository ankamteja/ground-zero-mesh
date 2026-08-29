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
import org.groundzero.mesh.app.NodeIdStore
import org.groundzero.mesh.app.transport.NearbyTransport
import org.groundzero.mesh.app.transport.StoreAndForward

/**
 * Keeps the mesh alive with the screen off.
 *
 * A phone that stops advertising/scanning when it sleeps is invisible to the mesh. This
 * service runs foreground (type `connectedDevice`) and holds a partial wake lock so the
 * radios keep working. On OEM-throttled devices, also keep the app in the foreground
 * during a demo — the wake lock is necessary, not always sufficient.
 */
class MeshForegroundService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var transport: NearbyTransport? = null
    val storeAndForward = StoreAndForward()

    private val maintenance = object : Runnable {
        override fun run() {
            transport?.peers?.decayTick()
            storeAndForward.sweep()
            handler.postDelayed(this, MAINTENANCE_INTERVAL_MS)
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
        transport = NearbyTransport(this, localId).also {
            it.onReceive { from, frame -> Log.d(TAG, "rx ${frame.size}B from $from") }
            it.start()
        }
        handler.postDelayed(maintenance, MAINTENANCE_INTERVAL_MS)
        Log.i(TAG, "mesh service up as $localId")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(maintenance)
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
