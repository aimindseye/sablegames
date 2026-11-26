package org.sableos.games

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
}
