package org.sableos.games

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/*
 * Gravel & Gears: the rules, with no Android in them so they run in plain JVM tests.
 *
 * The trail runs down the screen: x is the column 0..8 and y the row, growing downhill. A card is a short path
 * of steps measured from the rider. [GgSim.resolve] walks that path over the course and says what happens,
 * and both the ghost preview and the real move use it, so the preview can never disagree with the ride.
 */

internal enum class GgKind { PINE_TREE, BOULDER, LOG, STUMP, SMALL_ROCK, RAMP, BERM_LEFT, BERM_RIGHT, MUD, GRAVEL }

internal data class GgObstacle(
    val x: Int,
    val y: Int,
    val kind: GgKind,
    val span: Int = 1,
    /** A fraction of a tile off the column, so a generated trail doesn't line up on a grid. */
    val shift: Float = 0f,
    /** Which way a ramp kicks: -1 left, 0 straight on, 1 right. */
    val dir: Int = 0,
) {
    /** Whether a rider on the ground at column [cx] touches this obstacle. */
    fun covers(cx: Int) = cx >= x && cx < x + span
}

internal data class GgStep(
    val dx: Int,
    val dy: Int,
    val air: Boolean,
)

/** One effect of a card: its type from the data package, a whole-number value and a factor. */
internal data class GgFx(
    val type: String,
    val n: Int,
    val factor: Float,
)

internal data class GgCard(
    val id: String,
    val name: String,
    val category: String,
    val rarity: String,
    val starterQty: Int,
    val dx: Int,
    val dy: Int,
    val steps: List<GgStep>,
    val fx: List<GgFx>,
    val description: String,
) {
    fun fx(type: String): GgFx? = fx.firstOrNull { it.type == type }
}

internal data class GgGate(
    val number: Int,
    val row: Int,
    val centerX: Int,
    val width: Int,
    val left: Int,
    val right: Int,
    val style: String,
    val points: Int,
    val draft: Boolean,
    val redraw: Boolean,
    val draftFilter: List<String>,
    val obstacles: List<GgObstacle>,
) {
    fun spans(x: Int) = x in left..right
}

internal data class GgStage(
    val id: String,
    val name: String,
    val night: Boolean,
    val width: Int,
    val density: Float,
    val mult: Float,
    val endless: Boolean,
    val unlockGate: Int,
    val unlockId: String,
    val gates: List<GgGate>,
)

internal enum class GgAnimalKind { MARMOT, DEER }

internal enum class GgDeerState { IDLE, ALERT, GONE }

/** A live animal. A marmot walks sideways a tile a turn; a deer stands, sees the rider and then bolts across. */
internal data class GgAnimal(
    val kind: GgAnimalKind,
    val x: Int,
    val y: Int,
    val dir: Int = 1,
    val state: GgDeerState = GgDeerState.IDLE,
)

internal data class GgRider(
    val id: String,
    val name: String,
    val species: String,
    val signatureCard: String,
    val perk: String,
    val perkText: String,
) {
    companion object {
        val Gravel = GgRider("gravel", "Gravel", "Trail Goat", "bunny_hop", "Sure-Footed", "Bouncing off a stump keeps your combo.")
        val Spokes = GgRider("spokes", "Spokes", "Red Fox", "switchback_l", "Flow State", "3 gates in a row give a free Redraw.")
        val Knobby = GgRider("knobby", "Knobby", "Badger", "trail_bash", "Bash Bar", "Smashes small rocks for +50 without deflecting.")
        val all = listOf(Gravel, Spokes, Knobby)

        fun byId(id: String) = all.firstOrNull { it.id == id } ?: Gravel
    }
}

/** What happens when a card is played from a cell: everything the preview and the ride need. */
internal data class GgOutcome(
    /** The cells the rider crosses in order, ending on the one it stops in (or crashes at). */
    val path: List<GgCell>,
    val endX: Int,
    val endY: Int,
    /** The cell of a fatal hit, or null. */
    val crash: GgCell?,
    /** Where a non-fatal bump threw the rider (the cyan arrow), or null. */
    val bump: GgCell?,
    val bumpKeepsCombo: Boolean,
    val gatesHit: List<GgGate>,
    val gatesMissed: List<GgGate>,
    val airClears: Int,
    val ramp: Boolean,
    val bankShot: Boolean,
    val closeCall: Boolean,
    val smashed: Int,
    val smashPoints: Int,
    val styleBonus: Int,
    /** The next card is shortened by mud. */
    val mud: Boolean,
    /** An animal knocked the rider: combo lost. */
    val animalBump: Boolean,
    val lastLateral: Int,
)

internal data class GgCell(
    val x: Int,
    val y: Int,
    val air: Boolean = false,
)

/** The state a card's path depends on besides the cell it starts from. */
internal data class GgContext(
    val mud: Boolean = false,
    val lastLateral: Int = 0,
    val perk: String = "",
    val animals: List<GgAnimal> = emptyList(),
)

/**
 * The trail: the handcrafted stages of one biome, then endless gates made from a seed. Every endless gate is
 * checked with [GgSim.canReach] so at least one clean line to it always exists.
 */
internal class GgCourse(
    val night: Boolean,
    private val seed: Long = 1L,
    /** True lays a new trail for every ride from the stage rules; false keeps the hand-made one in the data. */
    private val fresh: Boolean = false,
) {
    val stages: List<GgStage> = GravelGearsData.stages.filter { it.night == night }
    private val gates = ArrayList<GgGate>()
    private val obstacleMap = HashMap<Int, GgObstacle>()
    private val animalList = ArrayList<GgAnimal>()

    init {
        if (fresh) {
            stages.forEach { s -> s.gates.forEach { meta -> lay(s, meta) } }
        } else {
            stages.forEach { s -> s.gates.forEach { add(it) } }
        }
        if (!night) {
            // Marmots from Stage 2 and deer from Stage 3: on fixed gates on the hand-made trail, anywhere on a fresh one.
            val rnd = Random(seed * 7 + 3)
            fun pick(
                stage: GgStage?,
                fixed: Set<Int>,
            ): List<GgGate> {
                val gs = stage?.gates?.map { gateByNumber(it.number) } ?: return emptyList()
                return if (fresh) gs.drop(1).shuffled(rnd).take(fixed.size) else gs.filter { it.number in fixed }
            }
            pick(stages.getOrNull(1), MARMOT_GATES).forEach {
                animalList += GgAnimal(GgAnimalKind.MARMOT, it.centerX, it.row - 2, if (it.number % 2 == 0) 1 else -1)
            }
            pick(stages.getOrNull(2), DEER_GATES).forEach {
                val prev = gates[it.number - 2]
                animalList += GgAnimal(GgAnimalKind.DEER, it.centerX, if (fresh) prev.row + 1 else it.row - 4)
            }
        }
    }

    val handcraftedGates: Int = gates.size
    val animals: List<GgAnimal> get() = animalList.toList()

    private fun add(g: GgGate) {
        gates += g
        g.obstacles.forEach { o -> obstacleMap[o.y * KEY + o.x] = o; for (i in 1 until o.span) obstacleMap[o.y * KEY + o.x + i] = o }
    }

    fun gate(index: Int): GgGate {
        while (index >= gates.size) generate()
        return gates[index]
    }

    /** Makes sure the trail is laid at least [rows] rows past row [y], so a card never meets the end of the course. */
    fun ensure(y: Int, rows: Int = 14) {
        while (gates.last().row < y + rows) generate()
    }

    /** The gates laid so far, plus [probe]'s while a new one is being tried. Never generates. */
    fun known(probe: Probe? = null): List<GgGate> = if (probe == null) gates else gates + probe.gate

    fun gateByNumber(number: Int): GgGate = gate(number - 1)

    /** The stage a gate number belongs to; endless gates stay in the last one. */
    fun stageOf(gateNumber: Int): GgStage = stages.firstOrNull { gateNumber in it.gates.first().number..it.gates.last().number } ?: stages.last()

    fun isStageEnd(gateNumber: Int): Boolean = stages.any { it.gates.last().number == gateNumber }

    fun obstacleAt(
        x: Int,
        y: Int,
    ): GgObstacle? = obstacleMap[y * KEY + x]

    /** The next gate a rider on row [y] has still to pass, or null before the first. */
    fun nextGate(y: Int): GgGate {
        ensure(y)
        return gates.first { it.row > y }
    }

    /** The playable columns on row [y]: the trail band between the pine borders. */
    fun trail(y: Int): IntRange {
        var i = gates.indexOfFirst { it.row >= y }
        if (i < 0) i = gates.size - 1
        val next = gates[i]
        val prev = if (i == 0) null else gates[i - 1]
        val centre =
            if (prev == null) {
                next.centerX.toFloat()
            } else {
                val t = (y - prev.row).toFloat() / (next.row - prev.row)
                prev.centerX + (next.centerX - prev.centerX) * t
            }
        val w = next.width
        val left = Math.round(centre) - w / 2
        return left..(left + w - 1)
    }

    private fun generate() {
        val template = stages.last()
        val last = gates.last()
        val n = last.number + 1
        val meta =
            GgGate(
                number = n, row = last.row + 5, centerX = last.centerX, width = template.width, left = 0, right = 1,
                style = last.style, points = 100, draft = n % 3 == 0, redraw = n % 5 == 0,
                draftFilter = listOf("COMMON", "TACTICAL", "RARE") + if (night) listOf("NIGHT_BIOME") else emptyList(),
                obstacles = emptyList(),
            )
        lay(template, meta)
    }

    /**
     * Lays the gate [meta] (its number, width, draft and redraw) after the last one: a new spacing, a new bend and
     * a new mix of obstacles, tried until [GgSim.canReach] finds a clean line in.
     */
    private fun lay(
        stage: GgStage,
        meta: GgGate,
    ) {
        val rnd = Random(seed * 31 + meta.number * 7919L)
        val w = meta.width
        val lo = w / 2
        val hi = 8 - w / 2
        if (gates.isEmpty()) {
            // The first gate: straight ahead of the start, with one thing to steer round.
            val left = 4 - w / 2
            val obstacles = listOf(GgObstacle(if (rnd.nextBoolean()) left else left + w - 1, 3, GgKind.SMALL_ROCK, 1, shift(rnd)))
            add(meta.copy(row = 5, centerX = 4, left = 3, right = 4, obstacles = obstacles))
            return
        }
        val last = gates.last()
        var best: GgGate? = null
        repeat(40) {
            val gap = rnd.nextInt(GAP_MIN, GAP_MAX + 1) + (if (stage.width >= 5) 1 else 0)
            val row = last.row + gap
            val reach = if (gap >= 6) 2 else 1
            val cx = (last.centerX + rnd.nextInt(-reach, reach + 1)).coerceIn(lo, hi)
            val band = (cx - w / 2) until (cx - w / 2 + w)
            val left = band.first + rnd.nextInt(0, w - 1)
            val obstacles = segment(stage, last, row, cx, rnd)
            val gate = meta.copy(row = row, centerX = cx, left = left, right = left + 1, obstacles = obstacles)
            best = gate
            if (GgSim.canReach(this, Probe(this, gate), last, gate)) {
                add(gate)
                return
            }
        }
        // No layout had a clean line: ride in on a bare trail, which always has one.
        add(best!!.copy(obstacles = emptyList()))
    }

    /** The obstacles between gate [last] and a new gate at [row], centred on [cx]: one or two patterns. */
    private fun segment(
        stage: GgStage,
        last: GgGate,
        row: Int,
        cx: Int,
        rnd: Random,
    ): List<GgObstacle> {
        val out = ArrayList<GgObstacle>()
        val tier = stages.indexOf(stage).coerceAtLeast(0) + (if (stage.endless) 1 else 0)
        fun band(y: Int): IntRange {
            val t = (y - last.row).toFloat() / (row - last.row)
            val c = Math.round(last.centerX + (cx - last.centerX) * t)
            val l = c - stage.width / 2
            return l..(l + stage.width - 1)
        }

        fun free(
            x: Int,
            y: Int,
        ) = obstacleMap[y * KEY + x] == null && out.none { it.y == y && it.covers(x) } && x in band(y)

        fun put(
            x: Int,
            y: Int,
            kind: GgKind,
            span: Int = 1,
        ) {
            if ((0 until span).all { free(x + it, y) }) out += GgObstacle(x, y, kind, span, if (span == 1) shift(rnd) else 0f)
        }
        val bumpers = listOf(GgKind.STUMP, GgKind.SMALL_ROCK)
        val terrain = if (tier >= 2) listOf(GgKind.MUD, GgKind.GRAVEL) else listOf(GgKind.MUD)
        val first = last.row + 1
        val end = row - 1
        val patterns = 1 + (if (rnd.nextFloat() < stage.density * 2f) 1 else 0)
        repeat(patterns) {
            val y = rnd.nextInt(first, end + 1)
            val b = band(y)
            // Kickers come up twice as often as the rest; mud, logs and berms wait for the later stages.
            val pattern = (if (tier == 0) listOf(0, 1, 2, 3, 3, 4) else listOf(0, 1, 2, 3, 3, 4, 5, 6, 7)).random(rnd)
            when (pattern) {
                // A scatter of rocks and stumps.
                0, 1 -> {
                    for (yy in first..end) for (x in band(yy)) {
                        if (rnd.nextFloat() < stage.density * 0.7f) put(x, yy, if (rnd.nextInt(3) == 0) GgKind.BOULDER else bumpers.random(rnd))
                    }
                }
                // A rock garden across the trail with one gap to thread.
                2 -> {
                    val gapAt = b.first + rnd.nextInt(b.last - b.first + 1)
                    for (x in b) if (x != gapAt) put(x, y, if (rnd.nextBoolean()) GgKind.BOULDER else bumpers.random(rnd))
                }
                // A kicker: a ramp, and something to fly over behind it. Most kick left or right, toward open trail.
                3 -> {
                    val x = b.first + rnd.nextInt(b.last - b.first + 1)
                    if (y + 1 <= end) {
                        val dirs = listOf(-1, 1, -1, 1, 0).filter { x + it in band(y + 2) }
                        val dir = dirs.random(rnd)
                        if (free(x, y)) out += GgObstacle(x, y, GgKind.RAMP, 1, 0f, dir)
                        put(x, y + 1, if (tier >= 2 && rnd.nextBoolean()) GgKind.LOG else GgKind.BOULDER)
                    }
                }
                // A chicane: one side, then the other.
                4 -> {
                    put(b.first + rnd.nextInt(2), y, GgKind.BOULDER)
                    if (y + 2 <= end) band(y + 2).let { put(it.last - rnd.nextInt(2), y + 2, bumpers.random(rnd)) }
                }
                // A soft patch: mud or loose gravel.
                5 -> {
                    val k = terrain.random(rnd)
                    val x = b.first + rnd.nextInt(b.last - b.first + 1)
                    put(x, y, k)
                    put(x + (if (rnd.nextBoolean()) 1 else -1), y, k)
                }
                // A fallen log with a way round.
                6 -> {
                    if (stage.width >= 4) put(b.first + rnd.nextInt(b.last - b.first), y, GgKind.LOG, 2) else put(b.first, y, GgKind.BOULDER)
                }
                // A berm to carve on the bend.
                else -> {
                    put(if (cx < last.centerX) b.first else b.last, y, if (cx < last.centerX) GgKind.BERM_LEFT else GgKind.BERM_RIGHT)
                }
            }
        }
        return out
    }

    private fun shift(rnd: Random) = (rnd.nextFloat() - 0.5f) * 2f * MAX_SHIFT

    /** Lets [GgSim.canReach] see a gate's obstacles before the gate is added to the course. */
    class Probe(
        val course: GgCourse,
        val gate: GgGate,
    ) {
        fun obstacleAt(
            x: Int,
            y: Int,
        ): GgObstacle? =
            gate.obstacles.firstOrNull { it.y == y && it.covers(x) } ?: course.obstacleAt(x, y)

        fun trail(y: Int): IntRange {
            val prev = course.known().last()
            if (y > prev.row) {
                val t = (y - prev.row).toFloat() / (gate.row - prev.row)
                val centre = prev.centerX + (gate.centerX - prev.centerX) * t
                val left = Math.round(centre) - gate.width / 2
                return left..(left + gate.width - 1)
            }
            return course.trail(y)
        }
    }

    companion object {
        const val KEY = 16
        private val MARMOT_GATES = setOf(8, 11, 14)
        private val DEER_GATES = setOf(17, 20, 23)

        /** Rows between gates on a generated trail: one more on the wide first stage. */
        private const val GAP_MIN = 4
        private const val GAP_MAX = 6

        /** How far, in tiles, a generated rock or stump may sit off its column. */
        const val MAX_SHIFT = 0.22f
    }
}

internal object GgSim {
    private val LETHAL_GROUND = setOf(GgKind.PINE_TREE, GgKind.BOULDER, GgKind.LOG)

    fun resolve(
        course: GgCourse,
        x0: Int,
        y0: Int,
        card: GgCard,
        ctx: GgContext = GgContext(),
        probe: GgCourse.Probe? = null,
    ): GgOutcome {
        fun obstacle(
            x: Int,
            y: Int,
        ) = probe?.obstacleAt(x, y) ?: course.obstacleAt(x, y)

        fun trail(y: Int) = probe?.trail(y) ?: course.trail(y)

        val mirror = card.fx("MIRROR_LAST_LATERAL_SIGN") != null
        val ignoreTerrain = card.fx("IGNORE_TERRAIN_MODIFIERS") != null
        val bashing = card.fx("SMASH_BUMPER") != null
        var steps = card.steps
        if (mirror) {
            val sign = ctx.lastLateral
            steps = steps.map { GgStep(sign, it.dy, it.air) }
        }
        if (ctx.mud && !ignoreTerrain) {
            val cap = max(1, card.dy - 1)
            steps = steps.filter { it.dy <= cap }.ifEmpty { listOf(steps.first()) }
        }
        val cells = ArrayList<GgCell>()
        var crash: GgCell? = null
        var bump: GgCell? = null
        var keepsCombo = false
        var bank = false
        var air = 0
        var ramp = false
        var smashed = 0
        var smashPoints = 0
        var mud = false
        var animalBump = false
        var closeCall = false
        var endX = x0
        var endY = y0
        var i = 0
        val queue = ArrayList(steps)
        var gravelSlide = 0

        fun lethalAt(
            x: Int,
            y: Int,
            airborne: Boolean,
        ): Boolean {
            if (x !in trail(y)) return true
            val o = obstacle(x, y) ?: return false
            return !airborne && o.kind in LETHAL_GROUND
        }

        fun towardCentre(
            x: Int,
            y: Int,
        ): Int {
            val t = trail(y)
            val mid = (t.first + t.last) / 2f
            return if (x < mid) 1 else if (x > mid) -1 else 1
        }

        fun nearLethal(
            x: Int,
            y: Int,
        ): Boolean {
            for (ddx in -1..1) for (ddy in -1..1) {
                if (ddx == 0 && ddy == 0) continue
                val o = obstacle(x + ddx, y + ddy) ?: continue
                if (o.kind in LETHAL_GROUND) return true
            }
            return false
        }

        loop@ while (i < queue.size) {
            val s = queue[i++]
            val nx = x0 + s.dx
            val ny = y0 + s.dy
            val cell = GgCell(nx, ny, s.air)
            cells += cell
            endX = nx
            endY = ny
            if (nx !in trail(ny)) {
                crash = cell
                break
            }
            // A deer bolting across this row is fatal to anyone on the ground in it.
            val animal = ctx.animals.firstOrNull { a ->
                when (a.kind) {
                    GgAnimalKind.MARMOT -> a.x == nx && a.y == ny
                    GgAnimalKind.DEER -> a.state == GgDeerState.ALERT && a.y == ny && !s.air
                }
            }
            if (animal != null) {
                if (animal.kind == GgAnimalKind.DEER) {
                    crash = cell
                    break
                }
                if (!s.air) {
                    val dir = towardCentre(nx, ny)
                    val bx = nx + dir
                    val b = GgCell(bx, ny)
                    animalBump = true
                    if (lethalAt(bx, ny, false)) {
                        cells += b
                        crash = b
                        endX = bx
                    } else {
                        cells += b
                        bump = b
                        endX = bx
                    }
                    break@loop
                }
            }
            val o = obstacle(nx, ny)
            if (o != null) {
                if (s.air) {
                    if (o.kind == GgKind.BOULDER || o.kind == GgKind.LOG) air++
                    if (o.kind == GgKind.PINE_TREE) {
                        crash = cell
                        break
                    }
                } else {
                    when (o.kind) {
                        GgKind.PINE_TREE, GgKind.BOULDER, GgKind.LOG -> {
                            crash = cell
                            break@loop
                        }

                        GgKind.STUMP -> {
                            if (bashing && smashed == 0) {
                                smashed++
                                smashPoints += card.fx("SMASH_BUMPER")!!.n
                            } else {
                                val dir = towardCentre(nx, ny)
                                val bx = nx + dir
                                val b = GgCell(bx, ny)
                                cells += b
                                endX = bx
                                if (lethalAt(bx, ny, false)) {
                                    crash = b
                                } else {
                                    bump = b
                                    val g = course.nextGateAtOrOn(ny)
                                    bank = g != null && g.row == ny && g.spans(bx)
                                    keepsCombo = bank || ctx.perk == "KEEP_COMBO_ON_STUMP_BOUNCE"
                                }
                                break@loop
                            }
                        }

                        GgKind.SMALL_ROCK -> {
                            if (bashing && smashed == 0) {
                                smashed++
                                smashPoints += card.fx("SMASH_BUMPER")!!.n
                            } else if (ctx.perk == "SMASH_SMALL_ROCKS_PASSIVE") {
                                smashed++
                                smashPoints += 50
                            } else {
                                val dir = towardCentre(nx, ny)
                                val b = GgCell(nx + dir, ny + 1)
                                cells += b
                                endX = b.x
                                endY = b.y
                                if (lethalAt(b.x, b.y, false)) crash = b else bump = b
                                break@loop
                            }
                        }

                        GgKind.RAMP -> {
                            // The rest of the card is dropped: the ramp throws the rider two tiles on, in the air.
                            ramp = true
                            queue.subList(i, queue.size).clear()
                            queue += GgStep(s.dx + o.dir, s.dy + 1, true)
                            queue += GgStep(s.dx + o.dir, s.dy + 2, false)
                        }

                        GgKind.BERM_LEFT, GgKind.BERM_RIGHT -> {
                            val inward = if (o.kind == GgKind.BERM_LEFT) 1 else -1
                            var cx = nx
                            for (k in 1..2) {
                                cx += inward
                                val b = GgCell(cx, ny)
                                cells += b
                                endX = cx
                                if (lethalAt(cx, ny, false)) {
                                    crash = b
                                    break@loop
                                }
                            }
                            bump = GgCell(cx, ny)
                            keepsCombo = true
                            break@loop
                        }

                        GgKind.MUD -> if (!ignoreTerrain) mud = true
                        GgKind.GRAVEL -> if (!ignoreTerrain && i >= queue.size) gravelSlide = if (s.dx != 0) sign(s.dx) else towardCentre(nx, ny)
                    }
                }
            }
            if (!s.air && nearLethal(nx, ny)) closeCall = true
        }
        if (crash == null && gravelSlide != 0) {
            val b = GgCell(endX + gravelSlide, endY)
            cells += b
            endX = b.x
            if (lethalAt(b.x, b.y, false)) crash = b
        }
        // Gates between the start and the stop: a cell inside the span scores it, anything else misses it.
        val hit = ArrayList<GgGate>()
        val missed = ArrayList<GgGate>()
        if (crash == null) {
            for (g in course.known(probe)) {
                if (g.row <= y0 || g.row > endY) continue
                if (cells.any { it.y == g.row && g.spans(it.x) }) hit += g else missed += g
            }
        }
        val style = card.fx("STYLE_BONUS")?.n ?: 0
        val sgn = if (endX != x0) sign(endX - x0) else ctx.lastLateral
        return GgOutcome(
            path = cells, endX = endX, endY = endY, crash = crash, bump = bump, bumpKeepsCombo = keepsCombo,
            gatesHit = hit, gatesMissed = missed, airClears = air, ramp = ramp, bankShot = bank, closeCall = closeCall && crash == null,
            smashed = smashed, smashPoints = smashPoints, styleBonus = style, mud = mud && crash == null,
            animalBump = animalBump, lastLateral = sgn,
        )
    }

    private fun sign(v: Int) = if (v > 0) 1 else if (v < 0) -1 else 0

    /** Whether some card (any, not just the deck) rides from [from]'s row to [gate] without a crash within a few turns. */
    fun canReach(
        course: GgCourse,
        probe: GgCourse.Probe?,
        from: GgGate,
        gate: GgGate,
    ): Boolean {
        val seen = HashSet<Int>()

        fun go(
            x: Int,
            y: Int,
            mud: Boolean,
            depth: Int,
        ): Boolean {
            if (depth > 4) return false
            if (!seen.add(((y * 16 + x) * 2 + (if (mud) 1 else 0)) * 8 + depth)) return false
            for (card in GravelGearsData.cards) {
                if (card.rarity == "NIGHT_BIOME") continue
                val o = resolve(course, x, y, card, GgContext(mud = mud), probe)
                if (o.crash != null) continue
                if (o.gatesHit.any { it.number == gate.number }) return true
                if (o.endY >= gate.row) continue
                if (go(o.endX, o.endY, o.mud, depth + 1)) return true
            }
            return false
        }
        return (from.left..from.right).any { go(it, from.row, false, 0) }
    }
}

/** The gate at row [y], or null: used to tell a bounce into a gate (a bank shot) from a plain one. */
internal fun GgCourse.nextGateAtOrOn(y: Int): GgGate? = known().firstOrNull { it.row == y }
