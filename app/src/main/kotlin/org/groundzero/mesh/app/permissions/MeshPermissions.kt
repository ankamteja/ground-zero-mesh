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
}
