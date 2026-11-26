package org.sableos.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot

class GravelGearsRigTest {
    private val still = GgRigPose(yaw = 0.9f, roll = 0f, pitch = 0f, crank = 0f, wheel = 0f, stand = 0f, tuck = 0f)

    @Test
    fun theKneeKeepsBothBonesTheirLength() {
        val hip = floatArrayOf(-0.24f, 0.86f)
        for (k in 0 until 12) {
            val foot = GgRigMath.pedal(k * PI.toFloat() / 6f)
            val knee = GgRigMath.knee(hip, foot)
            assertEquals(GgRigMath.THIGH, hypot(knee[0] - hip[0], knee[1] - hip[1]), 0.001f)
            assertEquals(GgRigMath.SHIN, hypot(foot[0] - knee[0], foot[1] - knee[1]), 0.001f)
            assertTrue("the knee bends forward", knee[0] > hip[0])
        }
    }

    @Test
    fun thePedalsGoRoundTheBottomBracket() {
        val top = GgRigMath.pedal((PI / 2).toFloat())
        val bottom = GgRigMath.pedal((-PI / 2).toFloat())
        assertEquals(GgRigMath.BB[1] + GgRigMath.CRANK, top[1], 0.001f)
        assertEquals(GgRigMath.BB[1] - GgRigMath.CRANK, bottom[1], 0.001f)
    }

    @Test
    fun theBikeTurnsAndLeansOnScreen() {
        // Facing right, the front wheel is right of the back one; facing left, the other way round.
        val right = GgRigMath.project(still, GgRigMath.FRONT, 0f, GgRigMath.WHEEL)[0]
        val left = GgRigMath.project(still.copy(yaw = -0.9f), GgRigMath.FRONT, 0f, GgRigMath.WHEEL)[0]
        assertTrue(right > 0f)
        assertTrue(left < 0f)
        // Up is up the screen, and banking right moves the top of the bike right.
        assertTrue(GgRigMath.project(still, 0f, 0f, 1f)[1] < 0f)
        val banked = GgRigMath.project(still.copy(yaw = 0f, roll = 0.4f), 0f, 0f, 1f)[0]
        assertTrue(banked > 0f)
        // A pitched-up bike lifts its front wheel.
        val nose = GgRigMath.project(still.copy(pitch = 0.3f), GgRigMath.FRONT, 0f, GgRigMath.WHEEL)[1]
        assertTrue(nose < GgRigMath.project(still, GgRigMath.FRONT, 0f, GgRigMath.WHEEL)[1])
    }

    @Test
    fun theBikeFacesDownTheTrailAndTurnsOnlyWhileSteered() {
        val run = GgRun(GgCourse(night = false), GgRider.Gravel, seed = 3)
        var t = 0f
        while (t < 2.2f) {
            run.step(1f / 60f, if (t > 1.6f) -1f else 0f)
            t += 1f / 60f
            if (t in 1.5f..1.6f) assertEquals("rides straight with no steer", 0f, run.facing, 0.001f)
        }
        assertTrue("facing ${run.facing}", run.facing < -0.5f)
        while (t < 3.4f) {
            run.step(1f / 60f, 0f)
            t += 1f / 60f
        }
        assertTrue("straightens again: ${run.facing}", kotlin.math.abs(run.facing) < 0.1f)
    }

    @Test
    fun everyRiderHasItsOwnLook() {
        val looks = GgRider.all.map { GgLook.of(it.id) }
        assertEquals(3, looks.toSet().size)
        assertNotEquals(GgLook.of("gravel").helmet, GgLook.of("spokes").helmet)
    }
}
