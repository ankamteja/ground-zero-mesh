package org.groundzero.mesh.app.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * The GPS half of "GPS when available, honestly nullable" — see the SOS-location ledger
 * entry in `docs/architecture.md` and `Envelope.gpsLat`/`gpsLon`.
 *
 * Mirrors [SensorBridge]'s shape on purpose: the same start/stop lifecycle tied to
 * [org.groundzero.mesh.app.node.MeshRole.NODE] (a relay or gateway phone has no reason to
 * burn battery on continuous location), and the same "push whatever arrives, never make
 * anyone wait on it" contract. [onFix] fires on every location update; the caller
 * ([org.groundzero.mesh.app.service.MeshForegroundService]) wires it straight to
 * [org.groundzero.mesh.app.mesh.MeshStack.updateGpsFix].
 *
 * ### Real fixes only — no network-location fallback
 *
 * Deliberately [LocationManager.GPS_PROVIDER] only, never `NETWORK_PROVIDER`. Cell/Wi-Fi
 * positioning can be off by hundreds of meters to kilometers — exactly the fabricated
 * precision `Envelope.gpsLat`'s own doc comment rules out ("never a fallback or an
 * estimate"). A victim without a GPS lock gets no coordinate, not a wrong one.
 *
 * ### Why this never throws for a missing permission
 *
 * [start] checks the permission itself and does nothing when it is missing — same shape as
 * [SensorBridge] not faking a light reading when there is no light sensor. Calling [start]
 * again later (e.g. right after the victim screen's own permission grant) picks the fix up
 * without a service restart — see `MeshForegroundService.onStartCommand`.
 */
class GpsBridge(
    context: Context,
    private val onFix: (lat: Float, lon: Float) -> Unit,
) {
    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Volatile private var running = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onFix(location.latitude.toFloat(), location.longitude.toFloat())
        }

        // No-ops kept explicit rather than relying on the interface's default methods —
        // those only became default implementations on newer platform releases, and this
        // app's minSdk predates that; an explicit override is correct at every API level.
        @Deprecated("Deprecated in the platform interface; kept for pre-Q compatibility.")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    @SuppressLint("MissingPermission") // ACCESS_FINE_LOCATION is gated by hasPermission() above
    fun start() {
        if (running) return
        if (!hasPermission()) {
            Log.i(TAG, "no location permission yet — SOS will carry no GPS fix until granted")
            return
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Log.i(TAG, "GPS provider is off — SOS will carry no GPS fix until it is")
            return
        }
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            UPDATE_INTERVAL_MS,
            UPDATE_MIN_DISTANCE_M,
            listener,
            Looper.getMainLooper(),
        )
        running = true
    }

    fun stop() {
        if (!running) return
        running = false
        locationManager.removeUpdates(listener)
    }

    private fun hasPermission() = ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "GpsBridge"

        /** Frequent enough that a fix is rarely more than this stale by broadcast time. */
        const val UPDATE_INTERVAL_MS = 20_000L

        /** Meters. Zero would mean "every reading" — unnecessary battery drain for no gain. */
        const val UPDATE_MIN_DISTANCE_M = 5f
    }
}
