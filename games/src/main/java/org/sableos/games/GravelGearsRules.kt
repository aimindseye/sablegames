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
) {
    val stages: List<GgStage> = GravelGearsData.stages.filter { it.night == night }
    private val gates = ArrayList<GgGate>()
    private val obstacleMap = HashMap<Int, GgObstacle>()
    private val animalList = ArrayList<GgAnimal>()

    init {
        stages.forEach { s -> s.gates.forEach { add(it) } }
        if (!night) {
            // Marmots from Stage 2 and deer from Stage 3, on fixed gates so every ride meets them in the same places.
            stages.getOrNull(1)?.gates?.filter { it.number in MARMOT_GATES }?.forEach {
                animalList += GgAnimal(GgAnimalKind.MARMOT, it.centerX, it.row - 2, if (it.number % 2 == 0) 1 else -1)
            }
            stages.getOrNull(2)?.gates?.filter { it.number in DEER_GATES }?.forEach {
                animalList += GgAnimal(GgAnimalKind.DEER, it.centerX, it.row - 4)
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
        val n = gates.size
        val last = gates.last()
        val template = stages.last()
        val rnd = Random(seed * 31 + n)
        var best: GgGate? = null
        repeat(40) {
            val cx = (last.centerX + rnd.nextInt(-1, 2)).coerceIn(template.width / 2 + 1, 8 - template.width / 2 - 1)
            val row = last.row + 5
            val left = (cx - rnd.nextInt(0, 2)).coerceIn(0, 7)
            val obstacles = ArrayList<GgObstacle>()
            val band = (cx - template.width / 2)..(cx - template.width / 2 + template.width - 1)
            for (y in last.row + 2 until row) {
                for (x in band) {
                    if (rnd.nextFloat() < template.density * 0.5f && obstacleMap[y * KEY + x] == null) {
                        val kind = ENDLESS_KINDS[rnd.nextInt(ENDLESS_KINDS.size)]
                        val span = if (kind == GgKind.LOG && x < band.last) 2 else 1
                        obstacles += GgObstacle(x, y, kind, span)
                    }
                }
            }
            val gate =
                GgGate(
                    number = last.number + 1, row = row, centerX = cx, width = template.width, left = left, right = left + 1,
                    style = last.style, points = 100, draft = (last.number + 1) % 3 == 0, redraw = (last.number + 1) % 5 == 0,
                    draftFilter = listOf("COMMON", "TACTICAL", "RARE") + if (night) listOf("NIGHT_BIOME") else emptyList(),
                    obstacles = obstacles,
                )
            best = gate
            val probe = GgCourse.Probe(this, gate)
            if (GgSim.canReach(this, probe, last, gate)) {
                add(gate)
                return
            }
        }
        // No random layout had a clean line: ride in on a bare trail, which always has one.
        add(best!!.copy(obstacles = emptyList()))
    }

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
        private val ENDLESS_KINDS =
            listOf(GgKind.BOULDER, GgKind.BOULDER, GgKind.STUMP, GgKind.LOG, GgKind.MUD, GgKind.RAMP, GgKind.GRAVEL, GgKind.SMALL_ROCK)
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
                            queue += GgStep(s.dx, s.dy + 1, true)
                            queue += GgStep(s.dx, s.dy + 2, false)
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
