package org.sableos.games

import android.view.InputDevice
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Trackpad pacing and which arrows count as the trackpad. */
class InputFeelTest {
    private val right = KeyEvent.KEYCODE_DPAD_RIGHT
    private val left = KeyEvent.KEYCODE_DPAD_LEFT

    @Test
    fun normalIsOneTickOneStep() {
        val pacer = TrackpadPacer()
        assertEquals(List(4) { 1 }, (0 until 4).map { pacer.steps(right, it * 20L, InputFeel.ticksPerStep(InputFeel.NORMAL_TRACKPAD)) })
    }

    @Test
    fun slowerLevelsSwallowTicksButNeverTheFirst() {
        val pacer = TrackpadPacer()
        // Slowest: three ticks a step, starting with a step.
        assertEquals(listOf(1, 0, 0, 1, 0, 0), (0 until 6).map { pacer.steps(right, it * 20L, 3) })
        // A new direction starts the count again.
        assertEquals(1, pacer.steps(left, 140L, 3))
        // So does a pause.
        pacer.steps(left, 160L, 3)
        assertEquals(1, pacer.steps(left, 1000L, 3))
    }

    @Test
    fun fastRepeatsEachTick() {
        val pacer = TrackpadPacer()
        assertEquals(2, pacer.steps(right, 0L, InputFeel.ticksPerStep(InputFeel.trackpadLevels.lastIndex)))
    }

    @Test
    fun onlyTrackpadArrowsArePaced() {
        assertTrue(InputFeel.fromTrackpad(right, InputDevice.SOURCE_KEYBOARD, DeviceProfile.Q25))
        assertTrue(InputFeel.fromTrackpad(right, InputDevice.SOURCE_TRACKBALL, DeviceProfile.Other))
        assertFalse(InputFeel.fromTrackpad(right, InputDevice.SOURCE_KEYBOARD, DeviceProfile.Other))
        assertFalse(InputFeel.fromTrackpad(KeyEvent.KEYCODE_ENTER, InputDevice.SOURCE_TRACKBALL, DeviceProfile.Q25))
    }
}
