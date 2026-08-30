package org.groundzero.mesh.app.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime permissions Nearby Connections needs, resolved per API level.
 *
 * This is the single most likely silent demo-killer: when a permission is missing, Nearby
 * does not error — it just discovers nothing. [runtimePermissions] mirrors the
 * `<uses-permission>` matrix in AndroidManifest.xml. Request the whole list at once and
 * refuse to start advertising/discovery until [allGranted] is true.
 */
object MeshPermissions {

    /** The dangerous permissions that must be granted at runtime on the current device. */
    fun runtimePermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ : the split Bluetooth permissions.
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ : Wi-Fi ranging without location, and the FGS notification.
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S) {
            // Up to and including Android 12 Nearby's BLE scan still needs fine location.
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun missing(context: Context): List<String> =
        runtimePermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    fun allGranted(context: Context): Boolean = missing(context).isEmpty()

    /**
     * Deliberately separate from [runtimePermissions]. That list gates whether the mesh
     * starts at all; this one gates only whether an SOS carries a coordinate. Folding
     * [LOCATION_PERMISSION] into [runtimePermissions] would make GPS a hard requirement to
     * use the mesh, which the feature's own design — "GPS when available, honestly
     * nullable, never required" (see the GPS ledger entry in `docs/architecture.md`) —
     * exists specifically to avoid. See `sensors/GpsBridge.kt` for the consumer.
     */
    const val LOCATION_PERMISSION = Manifest.permission.ACCESS_FINE_LOCATION

    fun locationGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, LOCATION_PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * Optional for the same reason as [LOCATION_PERMISSION], and kept out of
     * [runtimePermissions] for the same reason: the microphone enriches an SOS, it does not
     * enable one. A victim who declines it still reaches the mesh with severity, danger score,
     * IMU evidence and hop distance intact — three of the sixteen feature slots simply stay
     * zero, which `AudioBridge` reports honestly as silence rather than as a measurement of
     * nothing. Folding it into [runtimePermissions] would make a microphone grant a
     * precondition for calling for help.
     *
     * See `sensors/AudioBridge.kt` for the consumer and `agent/AudioFeatures.kt` (core) for
     * what is actually computed from it.
     */
    const val MICROPHONE_PERMISSION = Manifest.permission.RECORD_AUDIO

    fun microphoneGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, MICROPHONE_PERMISSION) == PackageManager.PERMISSION_GRANTED
}
