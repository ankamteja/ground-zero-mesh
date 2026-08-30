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
        // Deliberately registered whether or not the provider is enabled *right now*.
        // Bailing out here left `running` false with nothing to ever set it true again: the
        // only retry path is MeshForegroundService.onStartCommand, which the victim screen
        // fires on a permission grant — not on someone toggling Location on in Settings.
        // A registration against a disabled provider is legal and simply starts delivering
        // when the provider comes up, which is the behaviour actually wanted here.
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Log.i(TAG, "GPS provider is off — registered anyway; fixes begin if it is switched on")
        }
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            UPDATE_INTERVAL_MS,
            UPDATE_MIN_DISTANCE_M,
            listener,
            Looper.getMainLooper(),
        )
        running = true
        seedFromLastKnownFix()
    }

    /**
     * Take the platform's most recent GPS fix, if it is recent enough to still be true.
     *
     * Without this, the first coordinate arrives no sooner than the GPS chip's time-to-first
     * -fix — tens of seconds from cold, longer indoors, which is both the exact situation this
     * app is for and well past when the SOS was pressed. The fix the platform already holds is
     * the difference between an SOS with a coordinate and one without.
     *
     * This does not weaken `Envelope.gpsLat`'s "never a fallback or an estimate" rule: it is
     * [LocationManager.GPS_PROVIDER] only, so it is a real satellite fix, never a cell/Wi-Fi
     * derived one. [MAX_SEED_AGE_MS] is what keeps it honest — an old fix is a claim about
     * where the phone *was*, and past that bound it is no longer a claim about where it is.
     */
    @SuppressLint("MissingPermission") // only ever called from start(), past its hasPermission() gate
    private fun seedFromLastKnownFix() {
        val last = runCatching {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        }.getOrNull() ?: return
        val age = System.currentTimeMillis() - last.time
        if (age !in 0..MAX_SEED_AGE_MS) {
            Log.i(TAG, "last known fix is ${age}ms old — too stale to seed, waiting for a live one")
            return
        }
        Log.i(TAG, "seeded from a ${age}ms-old GPS fix while waiting for the first live one")
        onFix(last.latitude.toFloat(), last.longitude.toFloat())
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

        /**
         * How old the platform's last known fix may be and still be worth seeding with.
         *
         * Two minutes: long enough to cover the app being restarted or the service being
         * recreated mid-incident, short enough that a trapped person has not been carried
         * anywhere meaningful in it. Anything older is discarded rather than sent.
         */
        const val MAX_SEED_AGE_MS = 120_000L
    }
}
