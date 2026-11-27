package com.debkosh.termulaa.push

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Watches the accelerometer while a line is spoken and calls [onStop] when
 * the phone is shaken or turned face down, the way a ringing phone is
 * silenced. Face down counts only as a turn: a phone already lying face down
 * when the voice starts does not stop it.
 */
class StopGesture(context: Context, private val onStop: () -> Unit) : SensorEventListener {

    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val detector = Detector()
    private var on = false

    @Synchronized
    fun start() {
        if (on) return
        val accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        detector.reset()
        on = sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI)
    }

    @Synchronized
    fun stop() {
        if (!on) return
        sensors.unregisterListener(this)
        on = false
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (detector.feed(e.values[0], e.values[1], e.values[2], e.timestamp / 1_000_000)) onStop()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** The rules, apart from Android, so they can be tested. Times in ms, values in m/s². */
    class Detector {
        private var faceDownSince = 0L
        private var sawFaceDown = false
        private var sawNotFaceDown = false
        private val jolts = ArrayDeque<Long>()

        fun reset() {
            faceDownSince = 0L
            sawFaceDown = false
            sawNotFaceDown = false
            jolts.clear()
        }

        /** True once when a shake or a turn face down is seen. */
        fun feed(x: Float, y: Float, z: Float, at: Long): Boolean {
            // Shake: three jolts past 2.5 g within a second.
            val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
            if (g > SHAKE_G) {
                if (jolts.isEmpty() || at - jolts.last() > JOLT_GAP_MS) jolts.addLast(at)
                while (jolts.isNotEmpty() && at - jolts.first() > SHAKE_WINDOW_MS) jolts.removeFirst()
                if (jolts.size >= SHAKE_JOLTS) {
                    reset()
                    return true
                }
            }
            // Face down: z near -g, held a moment, after the phone was not.
            val down = z < -FACE_DOWN_Z && x * x + y * y < FLAT_XY * FLAT_XY
            if (!down) {
                faceDownSince = 0L
                sawNotFaceDown = true
                return false
            }
            if (faceDownSince == 0L) faceDownSince = at
            if (at - faceDownSince >= FACE_DOWN_HOLD_MS && sawNotFaceDown && !sawFaceDown) {
                sawFaceDown = true
                return true
            }
            return false
        }

        companion object {
            const val SHAKE_G = 2.5f
            const val SHAKE_JOLTS = 3
            const val SHAKE_WINDOW_MS = 1_000L
            const val JOLT_GAP_MS = 120L
            const val FACE_DOWN_Z = 8.0f
            const val FLAT_XY = 3.0f
            const val FACE_DOWN_HOLD_MS = 300L
        }
    }
}
