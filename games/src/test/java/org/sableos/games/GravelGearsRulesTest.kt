package org.sableos.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GravelGearsRulesTest {
    private fun card(id: String) = GravelGearsData.cards.first { it.id == id }

    @Test
    fun dataPackageLoads() {
        assertEquals(18, GravelGearsData.cards.size)
        assertEquals(5, GravelGearsData.stages.size)
        assertEquals(42, GravelGearsData.stages.sumOf { it.gates.size })
        assertEquals(10, GravelGearsData.cards.sumOf { it.starterQty })
    }

    @Test
    fun straightShotRidesTwoRows() {
        val course = GgCourse(night = false)
        val o = GgSim.resolve(course, 4, 0, card("straight_shot"))
        assertNull(o.crash)
        assertEquals(4, o.endX)
        assertEquals(2, o.endY)
    }

    @Test
    fun ridingOffTheTrailCrashes() {
        val course = GgCourse(night = false)
        val o = GgSim.resolve(course, 4, 0, card("hard_left"))
        assertNull(o.crash)
        val off = GgSim.resolve(course, 2, 0, card("hard_left"))
        assertNotNull(off.crash)
    }

    @Test
    fun bunnyHopClearsABoulderButARollingCardHitsIt() {
        val course = GgCourse(night = false)
        // Stage 1 gate 2 has a ramp at (4,7) and a boulder at (4,8): from (4,6) a plain card meets the ramp.
        val hop = GgSim.resolve(course, 4, 7, card("bunny_hop"))
        assertNull(hop.crash)
        assertEquals(1, hop.airClears)
        val roll = GgSim.resolve(course, 4, 7, card("straight_shot"))
        assertNotNull(roll.crash)
    }

    @Test
    fun rampThrowsTheRiderOverTheBoulder() {
        val course = GgCourse(night = false)
        val o = GgSim.resolve(course, 4, 6, card("feather_brake"))
        assertTrue(o.ramp)
        assertNull(o.crash)
        assertEquals(1, o.airClears)
        assertEquals(9, o.endY)
    }

    @Test
    fun everyHandcraftedGateHasAWayIn() {
        for (night in listOf(false, true)) {
            val course = GgCourse(night)
            for (i in 1 until course.handcraftedGates) {
                val g = course.gate(i)
                assertTrue(
                    "gate ${g.number} (night=$night) cannot be reached",
                    GgSim.canReach(
                        course,
                        null,
                        course.gate(i - 1),
                        g,
                    ),
                )
            }
        }
    }

    @Test
    fun endlessGatesAlwaysHaveACleanLine() {
        val course = GgCourse(night = false, seed = 5)
        course.ensure(400)
        for (i in course.handcraftedGates until course.known().size) {
            val g = course.gate(i)
            val prev = course.gate(i - 1)
            assertTrue("endless gate ${g.number}", GgSim.canReach(course, null, prev, g))
        }
    }

    /** Runs [run] for [seconds] at 60 frames a second with a constant [steer]. */
    private fun ride(
        run: GgRun,
        seconds: Float,
        steer: Float = 0f,
        until: (GgRun) -> Boolean = { false },
    ) {
        var t = 0f
        while (t < seconds && !until(run)) {
            run.step(1f / 60f, steer)
            t += 1f / 60f
        }
    }

    @Test
    fun theRideRollsOnItsOwnAfterTheCount() {
        val run = GgRun(GgCourse(night = false), GgRider.Gravel, seed = 3)
        assertEquals(GgPhase.Ready, run.phase)
        ride(run, 1.4f)
        assertEquals(0f, run.y, 0.001f)
        ride(run, 1.2f)
        assertEquals(GgPhase.Riding, run.phase)
        assertTrue("the rider rolls downhill with no key pressed", run.y > 1.2f)
    }

    @Test
    fun rollingStraightTakesTheRampOverTheBoulderAndScoresGates() {
        val run = GgRun(GgCourse(night = false), GgRider.Gravel, seed = 3)
        // Stage 1: gate 1 (row 5) and gate 2 (row 10) both span column 4, with a ramp at (4,7) before a boulder at (4,8).
        ride(run, 10f, until = { it.y > 10.5f || it.phase != GgPhase.Riding && it.phase != GgPhase.Ready })
        assertEquals(GgPhase.Riding, run.phase)
        assertEquals(2, run.gates)
        assertTrue(run.events.any { it.text.startsWith("Air clear") } || run.score > 300)
        assertEquals(3, run.combo)
    }

    @Test
    fun steeringOffTheTrailCrashes() {
        val run = GgRun(GgCourse(night = false), GgRider.Gravel, seed = 3)
        ride(run, 6f, steer = -1f, until = { it.phase == GgPhase.Wiped })
        assertEquals(GgPhase.Wiped, run.phase)
        assertEquals("Off the trail", run.wipeReason)
    }

    @Test
    fun aBunnyHopClearsABoulder() {
        val run = GgRun(GgCourse(night = false), GgRider.Gravel, seed = 3)
        ride(run, 10f, until = { it.y >= 6.6f })
        run.ride(card("bunny_hop"))
        assertTrue(run.airborne || run.move != null)
        ride(run, 2f, until = { it.y > 9.2f || it.phase == GgPhase.Wiped })
        assertEquals(GgPhase.Riding, run.phase)
        assertTrue(run.events.any { it.text.startsWith("Air clear") })
    }

    @Test
    fun aCardMoveFollowsItsPath() {
        val hard = GgMove(card("hard_left"), 0f, card("hard_left").steps)
        assertEquals(-2f, hard.offset(1f), 0.001f)
        val soft = GgMove(card("soft_left"), 0f, card("soft_left").steps)
        assertEquals(-1f, soft.offset(1f), 0.001f)
        assertEquals(-1f, soft.offset(2f), 0.001f)
        val hop = GgMove(card("bunny_hop"), 0f, card("bunny_hop").steps)
        assertTrue(hop.air(1f))
        assertFalse(hop.air(2f))
        assertFalse(hop.air(0.1f))
    }

    @Test
    fun aPlayedSlotRefillsAfterTheCooldown() {
        val run = GgRun(GgCourse(night = false), GgRider.Gravel, seed = 3)
        ride(run, 1.6f)
        val slot = run.hand.indices.first { run.hand[it]?.fx("NEXT_TURN_EXTRA_DRAW") == null }
        assertTrue(run.fire(slot))
        assertNull(run.hand[slot])
        assertFalse(run.fire(slot))
        ride(run, GgRun.COOLDOWN + 0.1f)
        assertNotNull(run.hand[slot])
    }

    @Test
    fun nightRidesScoreOnePointFiveTimes() {
        val day = GgRun(GgCourse(night = false), GgRider.Gravel, seed = 3)
        val night = GgRun(GgCourse(night = true), GgRider.Gravel, seed = 3)
        assertEquals(1.0f, day.biomeMult)
        assertEquals(1.5f, night.biomeMult)
    }

    @Test
    fun wipeoutRetriesFromTheLastGate() {
        val run = GgRun(GgCourse(night = false), GgRider.Gravel, seed = 3)
        ride(run, 10f, until = { it.gates >= 1 })
        assertEquals(1, run.gates)
        ride(run, 6f, steer = 1f, until = { it.phase == GgPhase.Wiped })
        assertEquals(GgPhase.Wiped, run.phase)
        run.retry()
        assertEquals(GgPhase.Ready, run.phase)
        assertEquals(5f, run.y, 0.1f)
        assertEquals(1, run.combo)
        assertTrue(run.hand.all { it != null })
    }

    @Test
    fun redrawCostsACharge() {
        val run = GgRun(GgCourse(night = false), GgRider.Gravel, seed = 3)
        assertEquals(1, run.redraws)
        assertTrue(run.redraw())
        assertEquals(0, run.redraws)
        assertFalse(run.redraw())
        assertEquals(4, run.hand.count { it != null })
    }
}
