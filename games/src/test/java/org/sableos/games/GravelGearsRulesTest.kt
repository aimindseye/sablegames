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
                assertTrue("gate ${g.number} (night=$night) cannot be reached", GgSim.canReach(course, null, course.gate(i - 1), g))
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

    @Test
    fun runPlaysAGateAndScores() {
        val run = GgRun(GgCourse(night = false), GgRider.Gravel, seed = 3)
        assertEquals(4, run.hand.size)
        assertEquals(11, run.deckSize)
        var guard = 0
        while (run.gates < 1 && run.phase == GgPhase.Riding && guard++ < 6) {
            val slot = run.hand.indices.firstOrNull { s -> run.preview(s)?.let { it.crash == null && it.gatesHit.isNotEmpty() } == true }
                ?: run.hand.indices.firstOrNull { s -> run.preview(s)?.crash == null } ?: break
            run.play(slot)
        }
        assertTrue(run.gates >= 1 || run.phase == GgPhase.Wiped)
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
        val slot = run.hand.indices.firstOrNull { run.preview(it)?.crash != null }
        if (slot != null) {
            run.play(slot)
            assertEquals(GgPhase.Wiped, run.phase)
            run.retry()
            assertEquals(GgPhase.Riding, run.phase)
            assertEquals(0, run.y)
            assertFalse(run.hand.isEmpty())
        }
    }

    @Test
    fun redrawCostsACharge() {
        val run = GgRun(GgCourse(night = false), GgRider.Gravel, seed = 3)
        assertEquals(1, run.redraws)
        assertTrue(run.redraw())
        assertEquals(0, run.redraws)
        assertFalse(run.redraw())
        assertEquals(4, run.hand.size)
    }
}
