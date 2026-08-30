package org.groundzero.mesh.app.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import org.groundzero.mesh.agent.AudioFeatures
import org.groundzero.mesh.agent.AudioObservation

/**
 * The microphone half of L1's perception layer: [AudioRecord] into [AudioFeatures] and
 * nothing further.
 *
 * Mirrors [GpsBridge]'s shape deliberately — the same start/stop lifecycle tied to
 * [org.groundzero.mesh.app.node.MeshRole.NODE], the same "silently do nothing without the
 * permission" contract, and the same rule that a missing capability degrades the report
 * rather than blocking it. A phone whose owner declined the microphone still sends a
 * perfectly valid SOS; it just carries no audio evidence.
 *
 * ### Pull, not push
 *
 * [SensorBridge] samples on its own 1.5 s cadence, so this class does not call into the
 * agent. It keeps [latest] up to date and the sensing tick reads it. That keeps one writer
 * to the agent instead of two racing ones, and means an audio thread that stalls costs a
 * stale reading rather than a stalled sensing loop.
 *
 * ### Raw audio never leaves this object
 *
 * The read buffer is allocated once, overwritten in place forever, and never copied
 * anywhere. What escapes is three `0..1` confidences. There is no recording, no file, no
 * upload path, and nothing here that could become one without a deliberate change — which
 * is what makes an always-listening microphone defensible in a device people are asked to
 * carry through a disaster.
 *
 * **Not verified on hardware.** Every `AudioRecord` call below is correct by inspection
 * only; this environment has no Android SDK. See `TODO.md`.
 */
class AudioBridge(context: Context) {

    private val appContext = context.applicationContext

    @Volatile
    private var recorder: AudioRecord? = null

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var running = false

    /**
     * The most recent analysis, or [AudioObservation.SILENT] when nothing has been heard yet.
     *
     * Never null and never stale-but-loud: [readLoop] writes [AudioObservation.SILENT] on any
     * read failure, so a microphone that is revoked mid-incident decays to "no claim" rather
     * than freezing on whatever it last heard.
     */
    @Volatile
    var latest: AudioObservation = AudioObservation.SILENT
        private set

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Begin listening. A no-op when already running or when the permission is missing, so it
     * is safe to call again after a grant — the same retry path
     * `MeshForegroundService.onStartCommand` already uses for [GpsBridge].
     */
    @SuppressLint("MissingPermission") // RECORD_AUDIO is gated by hasPermission() immediately above
    fun start() {
        if (running) return
        if (!hasPermission()) {
            Log.i(TAG, "no microphone permission yet — audio channels stay zero until granted")
            return
        }

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL, ENCODING)
        if (minBuffer <= 0) {
            Log.w(TAG, "device reports no usable buffer at $SAMPLE_RATE_HZ Hz — audio disabled")
            return
        }
        // Comfortably above the platform minimum: an under-sized buffer overruns whenever the
        // main looper is busy, and an overrun reads as silence, which is the one wrong answer
        // this channel can give.
        val bufferBytes = maxOf(minBuffer * 2, WINDOW_SAMPLES * BYTES_PER_SAMPLE)

        val record = try {
            AudioRecord(AUDIO_SOURCE, SAMPLE_RATE_HZ, CHANNEL, ENCODING, bufferBytes)
        } catch (e: Exception) {
            // A device with the microphone held by another app, or a manufacturer that
            // refuses this source, must not take the mesh service down with it.
            Log.w(TAG, "could not open the microphone — audio channels stay zero", e)
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord did not initialise — audio channels stay zero")
            record.release()
            return
        }

        running = true
        recorder = record
        record.startRecording()
        thread = Thread({ readLoop(record) }, "mesh-audio").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "microphone up at $SAMPLE_RATE_HZ Hz")
    }

    fun stop() {
        if (!running) return
        running = false
        thread?.interrupt()
        thread = null
        recorder?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        recorder = null
        // A stopped microphone asserts nothing. Leaving the last reading in place would let a
        // relay-role phone keep reporting audio it is no longer listening for.
        latest = AudioObservation.SILENT
    }

    /**
     * Read forever into one reused buffer, analysing each full window.
     *
     * Short reads are accumulated rather than analysed on their own: [AudioFeatures] needs a
     * power-of-two run to transform, and handing it a 40-sample fragment would return
     * [AudioObservation.SILENT] for a room that is anything but.
     */
    private fun readLoop(record: AudioRecord) {
        val pcm = ShortArray(WINDOW_SAMPLES)
        val samples = FloatArray(WINDOW_SAMPLES)
        var filled = 0

        while (running && !Thread.currentThread().isInterrupted) {
            val read = try {
                record.read(pcm, filled, WINDOW_SAMPLES - filled)
            } catch (e: Exception) {
                Log.w(TAG, "microphone read failed — treating as silence", e)
                latest = AudioObservation.SILENT
                return
            }

            if (read <= 0) {
                // ERROR_INVALID_OPERATION / ERROR_BAD_VALUE / a revoked permission mid-run.
                // Any of them means this loop has nothing further to contribute.
                if (read < 0) {
                    Log.w(TAG, "microphone read returned $read — stopping the audio channel")
                    latest = AudioObservation.SILENT
                    return
                }
                continue
            }

            filled += read
            if (filled < WINDOW_SAMPLES) continue

            for (i in 0 until WINDOW_SAMPLES) samples[i] = pcm[i] / Short.MAX_VALUE.toFloat()
            filled = 0

            latest = try {
                AudioFeatures.analyse(samples, SAMPLE_RATE_HZ)
            } catch (e: Exception) {
                // Perception is advisory in exactly the way Stage 2 is: it may fail, and the
                // deterministic loop carries on without it.
                Log.w(TAG, "audio analysis failed — treating as silence", e)
                AudioObservation.SILENT
            }
        }
    }

    companion object {
        private const val TAG = "AudioBridge"

        /**
         * 16 kHz. Nyquist puts [AudioFeatures.HIGH_HIGH_HZ] (8 kHz) exactly at the top of the
         * analysable range, and every Android device is required to support this rate — 44.1
         * and 48 kHz would cost four times the samples to see nothing this classifier reads.
         */
        const val SAMPLE_RATE_HZ = 16_000

        /** One analysis window. 4096 at 16 kHz is 256 ms — matches [AudioFeatures.MAX_FFT]. */
        const val WINDOW_SAMPLES = AudioFeatures.MAX_FFT

        private const val BYTES_PER_SAMPLE = 2
        private val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /**
         * Plain [MediaRecorder.AudioSource.MIC], not `VOICE_RECOGNITION` or
         * `VOICE_COMMUNICATION`. Those apply noise suppression and automatic gain tuned to
         * keep speech and discard everything else — which is precisely the broadband hiss of
         * rushing water and the transient of a structural crack that this channel exists to
         * hear. The unprocessed source is the honest one here.
         */
        private val AUDIO_SOURCE = MediaRecorder.AudioSource.MIC
    }
}
