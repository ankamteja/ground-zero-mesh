package org.groundzero.mesh.app.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.groundzero.mesh.agent.NodeAgent
import org.groundzero.mesh.agent.SensoryWindow
import org.groundzero.mesh.agent.SlmFeatureVector
import org.groundzero.mesh.app.mesh.MeshStack

/**
 * L1's eyes and ears on the device: accelerometer and ambient light into the agent.
 *
 * Without this the danger score only moves when someone presses the button, which makes the
 * whole sensing half of the design decorative. With it a phone that is dropped, buried or
 * shaken raises its own score with nobody touching it.
 *
 * ### Two cadences, and why the window has its own
 *
 * The sensing ticker runs every [SENSE_INTERVAL_MS] and feeds `senseVector`. Separately, from
 * the moment an SOS opens a sensory window, the bridge accumulates the **strongest** reading
 * seen on each channel and hands that to the agent as Stage 3 at [COMPLETE_AFTER_MS] — before
 * [NodeAgent.SENSORY_WINDOW_MS] closes it. Maxima, not averages: a phone that was underwater
 * for four seconds of a thirty-second window was underwater, and averaging that away is how a
 * casualty gets scored as calm.
 *
 * ### What is missing
 *
 * Microphone RMS. It needs `RECORD_AUDIO`, which is not in the manifest, plus an
 * `AudioRecord` loop — and the audio channels are three of the five sensory slots. Left out
 * until a real device is available to check it against; until then the audio slots stay zero
 * and the board says less than it could rather than making something up.
 *
 * Sensors are read on the main looper via [Handler], matching the rest of the app's
 * threading, and `MeshStack` serialises the agent calls anyway.
 */
class SensorBridge(
    context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : SensorEventListener {

    private val sensors =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelerometer: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val lightSensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_LIGHT)

    @Volatile private var lastX = 0.0
    @Volatile private var lastY = 0.0
    @Volatile private var lastZ = SensorNormalisation.GRAVITY

    /** Null until the device reports light; a phone with no light sensor never fakes one. */
    @Volatile private var lastLux: Double? = null

    @Volatile private var running = false

    /** Strongest reading per channel since the current sensory window opened. */
    private var windowPeak: SensoryWindow? = null
    private var windowOpenedAtMs: Long = 0L

    private val tick = object : Runnable {
        override fun run() {
            sample()
            handler.postDelayed(this, SENSE_INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        accelerometer?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        lightSensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        if (accelerometer == null) Log.w(TAG, "no accelerometer — IMU channels stay zero")
        handler.postDelayed(tick, SENSE_INTERVAL_MS)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(tick)
        sensors.unregisterListener(this)
        windowPeak = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                lastX = event.values[0].toDouble()
                lastY = event.values[1].toDouble()
                lastZ = event.values[2].toDouble()
            }
            Sensor.TYPE_LIGHT -> lastLux = event.values[0].toDouble()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** One sensing tick. Visible for tests to drive without a device. */
    internal fun sample(nowMs: Long = System.currentTimeMillis()) {
        val reading = read()
        MeshStack.senseVector(
            SlmFeatureVector.from(reading.window),
            accelMagnitude = reading.shock,
        )
        trackWindow(reading.window, nowMs)
    }

    private fun read(): Reading {
        val magnitude = SensorNormalisation.magnitude(lastX, lastY, lastZ)
        return Reading(
            window = SensoryWindow(
                imuShock = SensorNormalisation.shock(magnitude),
                imuPinned = SensorNormalisation.pinned(lastX, lastY, lastZ),
                // No light sensor means no claim about darkness: 0.5 is the neutral the
                // core's default uses, and enclosure needs >= 0.8 darkness to count at all.
                ambientLight = lastLux?.let { SensorNormalisation.ambientLight(it) } ?: 0.5,
            ),
            shock = SensorNormalisation.shock(magnitude),
        )
    }

    /**
     * Accumulate the window's peaks and close it out as Stage 3 when it is time.
     *
     * The agent rejects a window handed over after [NodeAgent.SENSORY_WINDOW_MS], so this
     * fires deliberately early.
     */
    private fun trackWindow(reading: SensoryWindow, nowMs: Long) {
        if (!MeshStack.sensoryWindowOpen()) {
            windowPeak = null
            return
        }
        val peak = windowPeak
        if (peak == null) {
            windowPeak = reading
            windowOpenedAtMs = nowMs
            return
        }
        windowPeak = SensoryWindow(
            audioWater = maxOf(peak.audioWater, reading.audioWater),
            audioVoice = maxOf(peak.audioVoice, reading.audioVoice),
            audioStructural = maxOf(peak.audioStructural, reading.audioStructural),
            imuPinned = maxOf(peak.imuPinned, reading.imuPinned),
            imuShock = maxOf(peak.imuShock, reading.imuShock),
            // Darkest moment, not the brightest: a phone briefly lit by a torch was still
            // buried the rest of the time.
            ambientLight = minOf(peak.ambientLight, reading.ambientLight),
        )

        if (nowMs - windowOpenedAtMs >= COMPLETE_AFTER_MS) {
            MeshStack.completeSensoryWindow(windowPeak!!)
            windowPeak = null
        }
    }

    private class Reading(val window: SensoryWindow, val shock: Double)

    companion object {
        private const val TAG = "SensorBridge"

        /** Fast enough to catch a collapse, slow enough not to be the battery story. */
        const val SENSE_INTERVAL_MS = 1_500L

        /** Comfortably inside [NodeAgent.SENSORY_WINDOW_MS] (30 s). */
        const val COMPLETE_AFTER_MS = 25_000L
    }
}
