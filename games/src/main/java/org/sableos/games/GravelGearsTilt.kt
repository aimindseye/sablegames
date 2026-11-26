package org.sableos.games

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

/**
 * Steering by tilt for Gravel & Gears: the phone's side-to-side lean, read from the accelerometer while a ride is on
 * screen. [level] takes the way the phone is held now as straight ahead, so it plays sitting up or lying back.
 * [steer] is -1 (full left) to 1 (full right), with a dead zone so a steady hand rides straight.
 */
internal class GgTilt(
    context: Context,
) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var listening = false
    private var raw = 0f
    private var base = 0f
    private var fresh = true

    val available: Boolean get() = sensor != null

    /** The current lean, -1 to 1. */
    val steer: Float get() = GgTiltMath.steer(raw - base)

    fun start() {
        if (listening || sensor == null) return
        fresh = true
        listening = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (!listening) return
        manager?.unregisterListener(this)
        listening = false
    }

    /** The phone as it is held now is level. */
    fun level() {
        base = raw
    }

    override fun onSensorChanged(event: SensorEvent) {
        // values[0] grows as the phone's left edge drops, so it is negated: tipping right steers right.
        val x = -event.values[0]
        raw = if (fresh) x else raw + (x - raw) * GgTiltMath.SMOOTH
        if (fresh) {
            base = raw
            fresh = false
        }
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int,
    ) = Unit
}

/** The tilt curve, kept apart from Android so it can be tested. */
internal object GgTiltMath {
    /** m/s² of lean (about 4° and 20°) where steering starts and where it is full. */
    const val DEAD = 0.7f
    const val FULL = 3.4f
    const val SMOOTH = 0.35f

    fun steer(lean: Float): Float {
        val a = abs(lean)
        if (a < DEAD) return 0f
        val s = ((a - DEAD) / (FULL - DEAD)).coerceAtMost(1f)
        return if (lean > 0) s else -s
    }
}
