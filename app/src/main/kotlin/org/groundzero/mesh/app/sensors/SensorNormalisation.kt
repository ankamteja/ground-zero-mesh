package org.groundzero.mesh.app.sensors

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turning raw sensor units into the `0..1` observations `core` expects.
 *
 * Pure and Android-free on purpose: this is the layer where a units mistake silently makes
 * the whole pipeline wrong — an accelerometer reading in m/s² fed to something expecting
 * `0..1` saturates every threshold forever — so it is the layer that most needs plain unit
 * tests, and none of it needs a device to check.
 */
object SensorNormalisation {

    /** Standard gravity, m/s². What a phone at rest reads. */
    const val GRAVITY = 9.81

    /** Deviation from gravity that counts as a full-scale shock. A hard drop clears this. */
    const val SHOCK_SPAN = 20.0

    /** Deviation above which a device is no longer "still". Hand tremor sits well below. */
    const val STILLNESS_SPAN = 2.0

    /** Overcast daylight, lux. The top of the ambient-light scale. */
    const val DAYLIGHT_LUX = 10_000.0

    fun magnitude(x: Double, y: Double, z: Double): Double = sqrt(x * x + y * y + z * z)

    /**
     * Violent movement or free fall, `0..1`.
     *
     * Distance from gravity in either direction: free fall reads near 0 and an impact reads
     * far above 9.81, and both are evidence. A phone sitting still reads 0.
     */
    fun shock(magnitude: Double): Double =
        min(1.0, abs(magnitude - GRAVITY) / SHOCK_SPAN)

    /**
     * How close to flat and still the device is, `0..1`.
     *
     * **This is stillness and orientation, not entrapment.** A phone face-up on a table
     * scores high. It is only evidence in combination — the deterministic classifier requires
     * darkness alongside it before treating enclosure as meaningful, and the Math Engine
     * weights it below the channels that are specific to a person in trouble. Reporting it
     * alone as "pinned" would put every phone on every desk on the responder board.
     *
     * [zAxis] is the gravity-aligned axis reading; a flat phone puts all of gravity there.
     */
    fun pinned(x: Double, y: Double, zAxis: Double): Double {
        val still = 1.0 - min(1.0, abs(magnitude(x, y, zAxis) - GRAVITY) / STILLNESS_SPAN)
        val flat = min(1.0, abs(zAxis) / GRAVITY)
        return still * flat
    }

    /**
     * Ambient light, `0..1`, where 0 is pitch dark and 1 is open sky.
     *
     * Logarithmic because the eye and the sensor both are: the difference between 0 and 10
     * lux is the difference between a sealed void and a room with a crack of light in it,
     * while 5,000 and 10,000 lux are the same "outdoors". A linear scale would compress the
     * only part of the range that carries information.
     */
    fun ambientLight(lux: Double): Double {
        if (lux <= 0.0) return 0.0
        return min(1.0, log10(1.0 + lux) / log10(1.0 + DAYLIGHT_LUX))
    }
}
