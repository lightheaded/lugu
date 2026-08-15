package io.github.lightheaded.lugu.playback

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlin.math.sqrt

/**
 * Decides whether a run of accelerometer samples was a shake.
 *
 * Kept apart from the sensor so the thresholds can be argued about in a test rather than
 * by shaking a phone. A shake is one sample whose acceleration exceeds the threshold,
 * followed by a cooldown: a real shake is half a second of violent samples, and without
 * the cooldown a single gesture would add half an hour to the sleep timer.
 */
class ShakeGesture(
    private val thresholdMs2: Float,
    private val cooldownMs: Long = COOLDOWN_MS,
) {
    /**
     * Null rather than zero, because timestamps here come from the clock since boot: with
     * a zero sentinel the first shake of a session is compared against the boot instant
     * and swallowed if the phone has been on for less than the cooldown. Rare in the
     * field, certain in a test, and wrong in both.
     */
    private var lastShakeAtMs: Long? = null

    /**
     * @param magnitudeMs2 total acceleration in m/s², gravity included.
     * @return true when this sample completes a shake worth acting on.
     */
    fun onSample(magnitudeMs2: Float, atMs: Long): Boolean {
        val excess = magnitudeMs2 - GRAVITY_MS2
        if (excess < thresholdMs2) return false
        lastShakeAtMs?.let { previous -> if (atMs - previous < cooldownMs) return false }
        lastShakeAtMs = atMs
        return true
    }

    companion object {
        /** One gesture is many samples; this is how long they collapse into one shake. */
        const val COOLDOWN_MS = 1_500L

        /**
         * Standard gravity, as a resting accelerometer reports it. Written out rather
         * than read from `SensorManager` so this class needs no Android at all.
         */
        const val GRAVITY_MS2 = 9.80665f

        /**
         * Acceleration above gravity that counts as a shake, per sensitivity level.
         *
         * Level 1 is a deliberate two-handed shake and level 3 a firm nudge, which is
         * what the settings screen promises. The floor is well above what a phone
         * experiences being turned over in bed, because a sleep timer that extends
         * itself when someone rolls over is worse than one that needs a proper shake.
         */
        fun thresholdFor(sensitivity: Int): Float = when (sensitivity.coerceIn(1, 3)) {
            1 -> 13.0f
            2 -> 9.0f
            else -> 6.0f
        }
    }
}

/**
 * Listens to the accelerometer, but only while somebody is asking.
 *
 * A permanently registered sensor is a battery bug that nothing in the UI would ever
 * show, so registration is tied strictly to the sleep timer being armed. [stop] is
 * idempotent and safe to call from anywhere, because the paths that have to call it —
 * pause, timer cancelled, service destroyed — overlap.
 */
internal class ShakeDetector(
    context: Context,
    private val onShake: () -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val handler = Handler(Looper.getMainLooper())

    private var gesture: ShakeGesture? = null

    /** Registers, or re-registers at a new sensitivity. Does nothing without a sensor. */
    fun start(sensitivity: Int) {
        val manager = sensorManager ?: return
        val sensor = accelerometer ?: return
        gesture = ShakeGesture(ShakeGesture.thresholdFor(sensitivity))
        // Unregister first so a sensitivity change does not leave two registrations.
        manager.unregisterListener(this)
        manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI, handler)
    }

    fun stop() {
        gesture = null
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val detector = gesture ?: return
        if (event.values.size < 3) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z)
        // The sensor's own timestamp is in nanoseconds since boot, which is monotonic —
        // wall clock is not, and a clock correction must not swallow a shake.
        if (detector.onSample(magnitude, event.timestamp / 1_000_000L)) onShake()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
