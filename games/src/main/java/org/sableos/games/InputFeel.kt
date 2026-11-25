package org.sableos.games

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/**
 * How fast the trackpad and swipes move things, set by the player in Controls and kept for every game
 * on this phone (owner request 2026-11-25: calibrate both so they are neither too fast nor too slow).
 *
 * Trackpad: each level says how many trackpad ticks make one step, or, at the fastest, how many steps
 * one tick makes. Normal is one tick, one step, as before.
 * Swipes: each level is how far a finger must travel before a swipe counts.
 */
internal object InputFeel {
    val trackpadLevels = listOf("Slowest", "Slow", "Normal", "Fast")
    val swipeLevels = listOf("Long", "Medium", "Normal", "Short", "Shortest")
    const val NORMAL_TRACKPAD = 2
    const val NORMAL_SWIPE = 2

    /** Ticks per step; a negative value is steps per tick. */
    private val TICKS = listOf(3, 2, 1, -2)

    /** The finger travel for a swipe, in dp. */
    private val SWIPE_DP = listOf(36f, 24f, 16f, 11f, 7f)

    var trackpad by mutableIntStateOf(NORMAL_TRACKPAD)
    var swipe by mutableIntStateOf(NORMAL_SWIPE)

    fun swipePx(density: Density): Float = with(density) { SWIPE_DP[swipe.coerceIn(SWIPE_DP.indices)].dp.toPx() }

    fun ticksPerStep(level: Int = trackpad): Int = TICKS[level.coerceIn(TICKS.indices)]

    private const val FILE = "sable_shell"

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        trackpad = prefs.getInt("trackpad_speed", NORMAL_TRACKPAD).coerceIn(trackpadLevels.indices)
        swipe = prefs.getInt("swipe_length", NORMAL_SWIPE).coerceIn(swipeLevels.indices)
    }

    fun save(context: Context) {
        context
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putInt("trackpad_speed", trackpad)
            .putInt("swipe_length", swipe)
            .apply()
    }

    val ARROWS =
        setOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
        )

    /**
     * Whether an arrow key-down came from the trackpad: its source says trackball, or the phone has no
     * arrow keys of its own (the Q25's and Titan 2's arrows all come from the pad).
     */
    fun fromTrackpad(
        keyCode: Int,
        source: Int,
        profile: DeviceProfile,
    ): Boolean =
        keyCode in ARROWS &&
            (
                (source and InputDevice.SOURCE_TRACKBALL) == InputDevice.SOURCE_TRACKBALL ||
                    profile == DeviceProfile.Q25 ||
                    profile == DeviceProfile.Titan2
            )
}

/**
 * Turns trackpad ticks into steps at the player's speed. A pause or a change of direction starts the
 * count again, so a slow setting never swallows the first tick of a new move.
 */
internal class TrackpadPacer(
    private val idleMs: Long = 300L,
) {
    private var lastKey = 0
    private var lastAt = Long.MIN_VALUE / 2
    private var ticks = 0

    /** How many steps this tick makes: 0 swallows it, 1 passes it on, more repeats it. */
    fun steps(
        keyCode: Int,
        atMs: Long,
        ticksPerStep: Int,
    ): Int {
        if (keyCode != lastKey || atMs - lastAt > idleMs) ticks = 0
        lastKey = keyCode
        lastAt = atMs
        if (ticksPerStep < 0) return -ticksPerStep
        // The first tick of a move always steps, then every ticksPerStep-th after it.
        val step = ticks % ticksPerStep == 0
        ticks++
        return if (step) 1 else 0
    }
}
