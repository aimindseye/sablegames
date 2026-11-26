package org.sableos.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rides through many generated trails, day and night, and lists anything badly placed. */
class GravelGearsLayoutTest {
    private fun problems(course: GgCourse): List<String> {
        val out = ArrayList<String>()
        val gates = course.known()
        val obstacles = gates.flatMap { it.obstacles }
        val gateRows = gates.map { it.row }.toSet()

        fun at(
            x: Int,
            y: Int,
        ) = course.obstacleAt(x, y)
        for (g in gates.drop(1)) {
            val prev = gates[g.number - 2]
            if (!GgSim.canReach(course, null, prev, g)) out += "gate ${g.number}: no clean line in"
            val t = course.trail(g.row)
            if (g.left < t.first || g.right > t.last) out += "gate ${g.number}: opening off the trail"
            // The rows just before and after a gate stay clear in front of its opening.
            for (x in g.left - 1..g.right + 1) {
                at(x, g.row - 1)?.let { out += "gate ${g.number}: ${it.kind} right in front of the gate" }
                at(x, g.row)?.let { out += "gate ${g.number}: ${it.kind} on the gate row" }
            }
        }
        for (o in obstacles) {
            val t = course.trail(o.y)
            for (i in 0 until o.span) if (o.x + i !in t) out += "${o.kind} at ${o.x},${o.y} off the trail"
            if (o.y in gateRows) out += "${o.kind} at ${o.x},${o.y} on a gate row"
            if (o.kind == GgKind.RAMP) {
                if (t.last - t.first >= 2 && (o.x == t.first || o.x == t.last)) out += "ramp at ${o.x},${o.y} on the trail edge"
                val lx = o.x + o.dir
                if (lx !in course.trail(o.y + 2) || lx !in course.trail(o.y + 3)) out += "ramp at ${o.x},${o.y} lands off the trail"
                for (yy in o.y + 2..o.y + 3) at(lx, yy)?.let { out += "ramp at ${o.x},${o.y} lands on a ${it.kind}" }
                val behind = at(o.x, o.y + 1)
                if (behind != null && behind.kind !in setOf(GgKind.BOULDER, GgKind.LOG)) out += "ramp at ${o.x},${o.y} jumps a ${behind.kind}"
                for (xx in o.x - 1..o.x + 1) if (xx != o.x) at(xx, o.y)?.let { out += "ramp at ${o.x},${o.y} crowded by a ${it.kind}" }
            }
        }
        // Wildlife lives on a quiet row: nothing standing on it, not a gate row, clear of every kicker.
        val ramps = obstacles.filter { it.kind == GgKind.RAMP }
        for (a in course.animals) {
            if (a.y in gateRows) out += "${a.kind} on gate row ${a.y}"
            obstacles.firstOrNull { it.y == a.y }?.let { out += "${a.kind} on row ${a.y} with a ${it.kind}" }
            ramps.firstOrNull { a.y in it.y - 1..it.y + 3 }?.let { out += "${a.kind} on row ${a.y} in a kicker's way" }
        }
        // Nothing stacked straight behind something else, apart from a kicker and what it jumps.
        for (o in obstacles) {
            val below = at(o.x, o.y + 1) ?: continue
            if (o.kind == GgKind.RAMP) continue
            if (below.kind in setOf(GgKind.MUD, GgKind.GRAVEL) && o.kind in setOf(GgKind.MUD, GgKind.GRAVEL)) continue
            out += "${o.kind} at ${o.x},${o.y} stacked on a ${below.kind}"
        }
        return out
    }

    @Test
    fun everyGeneratedTrailIsLaidOutCleanly() {
        val all = ArrayList<String>()
        for (seed in 1L..120L) {
            for (night in listOf(false, true)) {
                val course = GgCourse(night, seed = seed, fresh = true)
                course.ensure(260)
                problems(course).forEach { all += "seed $seed night=$night: $it" }
            }
        }
        assertTrue("${all.size} placement problems, first: ${all.take(5)}", all.isEmpty())
    }

    @Test
    fun theNightRideHasItsOwnWildlife() {
        val seen = HashSet<GgAnimalKind>()
        for (seed in 1L..20L) {
            val night = GgCourse(true, seed = seed, fresh = true)
            seen += night.animals.map { it.kind }
            assertTrue(night.animals.none { it.kind == GgAnimalKind.MARMOT || it.kind == GgAnimalKind.DEER })
            val day = GgCourse(false, seed = seed, fresh = true)
            assertTrue(day.animals.all { it.kind == GgAnimalKind.MARMOT || it.kind == GgAnimalKind.DEER })
        }
        val nightKinds = setOf(GgAnimalKind.RACCOON, GgAnimalKind.STAG, GgAnimalKind.OWL, GgAnimalKind.SKUNK, GgAnimalKind.FIREFLIES)
        assertTrue("missing ${nightKinds - seen}", seen.containsAll(nightKinds))
    }

    /** Rides [run] steering toward [target] each frame until it passes [row] or the ride stops. */
    private fun rideTo(
        run: GgRun,
        target: () -> Float,
        row: Int,
    ) {
        var t = 0f
        while (t < 120f && run.y < row + 0.5f && run.phase != GgPhase.Wiped) {
            if (run.phase == GgPhase.Drafting) run.skipDraft()
            if (run.phase == GgPhase.StageClear) run.continueRide()
            run.step(1f / 60f, ((target() - run.x) * 2f).coerceIn(-1f, 1f))
            t += 1f / 60f
        }
    }

    @Test
    fun firefliesScoreAndLightTheTrail() {
        var collected = 0
        for (seed in 1L..30L) {
            val course = GgCourse(true, seed = seed, fresh = true)
            val ff = course.animals.firstOrNull { it.kind == GgAnimalKind.FIREFLIES } ?: continue
            val run = GgRun(course, GgRider.Gravel, seed = 3)
            rideTo(run, { if (run.y > ff.y - 3) ff.x.toFloat() else course.nextGate(run.y.toInt()).let { (it.left + it.right) / 2f } }, ff.y)
            if (run.events.any { it.text.startsWith("Fireflies") }) {
                collected++
                assertTrue(run.beam > 0f)
            }
        }
        assertTrue("fireflies collected in $collected rides", collected > 0)
    }

    @Test
    fun aSkunkSpraysARiderWhoBrushesPast() {
        var sprayed = 0
        for (seed in 1L..40L) {
            val course = GgCourse(true, seed = seed, fresh = true)
            val sk = course.animals.firstOrNull { it.kind == GgAnimalKind.SKUNK } ?: continue
            val run = GgRun(course, GgRider.Gravel, seed = 3)
            rideTo(run, {
                val live = run.critters.firstOrNull { it.kind == GgAnimalKind.SKUNK && it.y == sk.y }
                if (run.y > sk.y - 2 && live != null) live.x else course.nextGate(run.y.toInt()).let { (it.left + it.right) / 2f }
            }, sk.y)
            if (run.events.any { it.text.startsWith("Skunked") }) {
                sprayed++
                assertTrue(run.stink > 0f)
                assertTrue(run.muddy)
                assertEquals(1, run.combo)
            }
        }
        assertTrue("sprayed in $sprayed rides", sprayed > 0)
    }

    /** An owl on row 4 of the hand-made first stage, coming in from the right; the rider steers under it. */
    private fun owlRun(hop: Boolean): GgRun {
        val course = GgCourse(night = false)
        val owl = GgAnimal(GgAnimalKind.OWL, course.trail(4).last + 2, 4, -1)
        val run = GgRun(course, GgRider.Gravel, seed = 3, animals = listOf(owl))
        var hopped = false
        rideTo(run, {
            val live = run.critters.firstOrNull { it.kind == GgAnimalKind.OWL }
            if (hop && !hopped && run.phase == GgPhase.Riding && run.y > 2.7f) {
                hopped = true
                run.ride(GravelGearsData.cards.first { it.id == "bunny_hop" })
            }
            if (live != null && live.bolting) live.x.coerceIn(3f, 6f) else 4f
        }, 4)
        return run
    }

    @Test
    fun anOwlSwoopsLowAndOnlyCatchesARiderInTheAir() {
        val ground = owlRun(hop = false)
        assertTrue(ground.events.map { it.text }.toString(), ground.events.any { it.text.startsWith("Ducked the owl") })
        val air = owlRun(hop = true)
        assertTrue(air.events.map { it.text }.toString(), air.events.any { it.text.startsWith("Owl strike") })
        assertEquals(1, air.combo)
    }
}
