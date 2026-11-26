package org.sableos.games

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/*
 * Gravel & Gears as a flow ride (owner request 2026-11-26: the turn-based ride felt static; he chose a nonstop ride
 * where the cards are live moves). The rider rolls downhill on their own; steering (trackpad, keys or tilt) nudges
 * them across the trail, and a card key fires that card's move at once. A played card's slot refills from the deck
 * after [GgRun.COOLDOWN] seconds. Positions are in tiles: x is the column (a float, 0..8), y the row, growing downhill.
 * No Android in here, so it runs in plain JVM tests: the screen calls [GgRun.step] once a frame.
 */

internal enum class GgPhase { Ready, Riding, Drafting, StageClear, Wiped }

/** A moment worth a line on screen: a gate scored, a perk fired, something unlocked. [at] is the ride clock. */
internal data class GgEvent(
    val text: String,
    val good: Boolean = true,
    val at: Float = 0f,
)

/** A card being ridden: its path laid from where it was fired, read by how many rows the rider has gone since. */
internal class GgMove(
    val card: GgCard,
    val y0: Float,
    steps: List<GgStep>,
) {
    /** (row, column offset) keyframes; steps on the same row collapse to the last, so a hard carve is one sharp swing. */
    private val keys: List<Pair<Float, Float>> =
        listOf(0f to 0f) +
            steps.groupBy { it.dy }.toSortedMap().map { (dy, s) -> dy.toFloat() to s.last().dx.toFloat() }
    private val airRows: List<Int> = steps.filter { it.air }.map { it.dy }
    val rows: Float = keys.last().first

    /** The sideways offset from the start, [p] rows in. Each leg eases in and out so a carve looks like a carve. */
    fun offset(p: Float): Float {
        if (p >= rows) return keys.last().second
        for (i in 1 until keys.size) {
            val (r1, x1) = keys[i]
            if (p <= r1) {
                val (r0, x0) = keys[i - 1]
                val u = ((p - r0) / (r1 - r0)).coerceIn(0f, 1f)
                val e = u * u * (3 - 2 * u)
                return x0 + (x1 - x0) * e
            }
        }
        return keys.last().second
    }

    /** Whether the rider is off the ground [p] rows in: from just before the first air row to just after the last. */
    fun air(p: Float): Boolean = airRows.isNotEmpty() && p > airRows.first() - AIR_LEAD && p < airRows.last() + AIR_TAIL

    /** How high the hop is, 0 on the ground and 1 at the top, for drawing. */
    fun lift(p: Float): Float {
        if (!air(p)) return 0f
        val a = airRows.first() - AIR_LEAD
        val b = airRows.last() + AIR_TAIL
        return sin(((p - a) / (b - a)).coerceIn(0f, 1f) * Math.PI.toFloat())
    }

    val lateral: Int get() =
        keys.last().second.let {
            if (it > 0) {
                1
            } else if (it < 0) {
                -1
            } else {
                0
            }
        }

    companion object {
        const val AIR_LEAD = 0.75f
        const val AIR_TAIL = 0.7f
    }
}

/** A live animal on the trail: a marmot trots across its row; a deer freezes when it sees the rider, then bolts. */
internal data class GgCritter(
    val kind: GgAnimalKind,
    val x: Float,
    val y: Int,
    val dir: Int,
    val state: GgDeerState = GgDeerState.IDLE,
    /** Seconds a deer has been alert; it bolts at [GgRun.DEER_FREEZE]. */
    val alertFor: Float = 0f,
    /** Whether the deer is running (it is a danger only then). */
    val bolting: Boolean = false,
    /** This animal already met the rider, so it cannot bump them twice. */
    val met: Boolean = false,
)

/** One point of the tyre track. */
internal data class GgTrack(
    val x: Float,
    val y: Float,
    val air: Boolean,
)

/** What a run keeps at each gate, so Retry can start from there. */
private data class GgSnapshot(
    val x: Float,
    val y: Float,
    val score: Int,
    val gates: Int,
    val redraws: Int,
    val draw: List<GgCard>,
    val discard: List<GgCard>,
    val hand: List<GgCard?>,
    val deck: List<GgCard>,
    val critters: List<GgCritter>,
)

/**
 * One ride: a rider rolling down a course with a deck. [step] moves the world on, [fire] plays the card in a slot,
 * [nudge] and the steer passed to [step] move the rider across. Every [DRAFT_EVERY]th gate offers a draft, a
 * stage's last gate ends in a panel, and a crash ends in a wipeout that [retry]s from the last gate.
 */
internal class GgRun(
    val course: GgCourse,
    val rider: GgRider,
    seed: Long = 7L,
    val biomeMult: Float = if (course.night) 1.5f else 1.0f,
) {
    private val rnd = Random(seed)
    var x = course.gate(0).centerX.toFloat()
        private set
    var y = 0f
        private set
    var speed = 0f
        private set
    var score = 0
        private set
    var combo = 1
        private set
    var gates = 0
        private set
    var best = 0
        private set
    var redraws = 1
        private set
    var phase = GgPhase.Ready
        private set

    /** Seconds left of the "Ready" count before the ride rolls. */
    var countdown = START_COUNT
        private set

    /** The ride clock, in seconds of riding. */
    var clock = 0f
        private set
    var hand: List<GgCard?> = emptyList()
        private set

    /** Seconds until each empty slot gets its next card. */
    var cooldown = FloatArray(HAND)
        private set
    var critters: List<GgCritter> = course.animals.map { GgCritter(it.kind, it.x.toFloat(), it.y, it.dir) }
        private set
    var draftChoices: List<GgCard> = emptyList()
        private set
    var events: List<GgEvent> = emptyList()
        private set
    var wipeReason = ""
        private set
    var clearedStage: GgStage? = null
        private set

    /** The card being ridden, or null when the rider just rolls. */
    var move: GgMove? = null
        private set

    /** Seconds the headlight stays at full strength (the High Beam card). */
    var beam = 0f
        private set

    /** How hard the rider is leaning, -1 (left) to 1 (right), for the pose. */
    var lean = 0f
        private set
    /** Which way the drawn bike faces: 0 straight down the trail, -1 turned left, 1 turned right while steering. */
    var facing = 0f
        private set
    val unlocks = ArrayList<String>()

    /** Where the wheels have been lately, oldest first, with whether they were in the air: tyre tracks and dust. */
    val tracks = ArrayDeque<GgTrack>()

    /** Seconds since the wheels touched down, counting down from [LAND_TIME]: the landing squash. */
    var landing = 0f
        private set

    /** Seconds since the crash, for the tumble. */
    var sinceWipe = 0f
        private set

    private var deck = ArrayList<GgCard>()
    private var draw = ArrayList<GgCard>()
    private var discard = ArrayList<GgCard>()
    private var moveStartX = 0f
    private var rampRows = 0f
    private var rampTotal = 0f
    private var rampDir = 0
    private var push = 0f
    private var nudged = 0f
    private var mudFor = 0f
    private var boostFor = 0f
    private var boost = 1f
    private var pedal = 0f
    private var lateral = 0
    private var chain = 0
    private var smashLeft = 0
    private var checkpoint: GgSnapshot? = null

    init {
        startDeck()
        course.ensure(0)
        hand = List(HAND) { takeCard() }
        checkpoint = snap()
    }

    val deckSize: Int get() = draw.size + discard.size + hand.count { it != null }
    val drawPile: Int get() = draw.size
    val isNight: Boolean get() = course.night
    val stage: GgStage get() = course.stageOf(min(gates + 1, course.handcraftedGates).coerceAtLeast(1))
    val nextGate: GgGate get() = course.nextGate(floor(y).toInt())
    val muddy: Boolean get() = mudFor > 0f

    /** Whether the rider is off the ground: in a card's hop or thrown by a ramp. */
    val airborne: Boolean get() = rampRows > 0f || move?.let { it.air(y - it.y0) } == true

    /** How high the rider is drawn, 0 to 1. */
    val lift: Float
        get() =
            when {
                rampRows > 0f -> sin((1f - rampRows / rampTotal).coerceIn(0f, 1f) * Math.PI.toFloat())
                else -> move?.lift(y - move!!.y0) ?: 0f
            }

    private fun startDeck() {
        deck.clear()
        for (c in GravelGearsData.cards) repeat(c.starterQty) { deck += c }
        GravelGearsData.cards.firstOrNull { it.id == rider.signatureCard }?.let { deck += it }
        draw = ArrayList(deck.shuffled(rnd))
        discard = ArrayList()
    }

    private fun takeCard(): GgCard? {
        if (draw.isEmpty()) {
            draw = ArrayList(discard.shuffled(rnd))
            discard.clear()
        }
        return if (draw.isEmpty()) null else draw.removeAt(draw.size - 1)
    }

    fun say(
        text: String,
        good: Boolean = true,
    ) {
        events = (events + GgEvent(text, good, clock)).takeLast(4)
    }

    /** The cruising speed in rows a second: faster on narrower stages and deeper into the endless trail. */
    private fun cruise(): Float {
        val st = stage
        var v =
            when {
                st.width >= 5 -> 2.4f
                st.width == 4 -> 2.8f
                else -> 3.1f
            }
        // The ride builds: a little faster every gate, and faster still out on the endless trail.
        v += min(RAMP_UP_MAX, gates * RAMP_UP)
        if (gates > course.handcraftedGates) v += min(0.9f, (gates - course.handcraftedGates) * 0.04f)
        if (isNight) v *= 0.9f
        return v
    }

    /** One trackpad tick or arrow press: a short shove across, a fraction of a tile. */
    fun nudge(dir: Int) = slide(dir * NUDGE)

    /** A finger drag across the trail, in tiles. */
    fun slide(tiles: Float) {
        if (phase != GgPhase.Riding && phase != GgPhase.Ready) return
        nudged = (nudged + tiles).coerceIn(-MAX_NUDGE, MAX_NUDGE)
    }

    /** Up pedals harder, down brakes, a little each press; both fade back to cruising. */
    fun pedal(dir: Int) {
        pedal = (pedal - dir * PEDAL_STEP).coerceIn(-0.45f, 0.3f)
    }

    /** Plays the card in [slot] at once. Returns false when nothing could be played (empty slot, in the air). */
    fun fire(slot: Int): Boolean {
        if (phase != GgPhase.Riding) return false
        val card = hand.getOrNull(slot) ?: return false
        if (airborne) return false
        hand = hand.toMutableList().also { it[slot] = null }
        cooldown[slot] = if (card.fx("NEXT_TURN_EXTRA_DRAW") != null) FAST_COOLDOWN else COOLDOWN
        discard += card
        ride(card)
        return true
    }

    /** Starts riding [card]'s move from where the rider is: [fire] after taking it from the hand, and the tests. */
    fun ride(card: GgCard) {
        var steps = card.steps
        if (card.fx("MIRROR_LAST_LATERAL_SIGN") != null) {
            val sign = if (lateral == 0) 1 else lateral
            steps = steps.map { GgStep(sign, it.dy, it.air) }
            score += Math.round(card.fx("STYLE_BONUS")!!.n * biomeMult)
            say("Berm Whip +${card.fx("STYLE_BONUS")!!.n}")
        }
        if (muddy && card.fx("IGNORE_TERRAIN_MODIFIERS") == null) {
            val cap = max(1, card.dy - 1)
            steps = steps.filter { it.dy <= cap }.ifEmpty { listOf(steps.first()) }
        }
        move = GgMove(card, y, steps)
        moveStartX = x
        smashLeft = if (card.fx("SMASH_BUMPER") != null) 1 else 0
        when (card.id) {
            "straight_shot" -> speedUp(1.2f, 1.0f)
            "tuck_and_bomb" -> speedUp(1.4f, 1.6f)
            "feather_brake" -> speedUp(0.6f, 0.8f)
            "skid_stop" -> speedUp(0.35f, 0.7f)
        }
        if (card.fx("CLEAR_TERRAIN_DEBUFFS") != null) {
            mudFor = 0f
            redraws = min(MAX_REDRAWS, redraws + 1)
            say("Skid Stop: mud cleared, +1 Redraw")
        }
        card.fx("EXPAND_HEADLIGHT_FULLSCREEN")?.let { beam = it.n * 2.5f }
        if (move!!.lateral != 0) lateral = move!!.lateral
    }

    private fun speedUp(
        factor: Float,
        seconds: Float,
    ) {
        boost = factor
        boostFor = seconds
    }

    /** Spends a Redraw: the hand goes to the discard and four fresh cards come at once. */
    fun redraw(): Boolean {
        if (phase != GgPhase.Riding && phase != GgPhase.Ready || redraws <= 0) return false
        redraws--
        hand.filterNotNull().forEach { discard += it }
        hand = List(HAND) { takeCard() }
        cooldown = FloatArray(HAND)
        return true
    }

    /**
     * Moves the ride on by [dt] seconds. [steer] is the held or tilted lean, -1 (left) to 1 (right). Everything that
     * happens on the way (gates, obstacles, animals) is checked as the rider crosses each row.
     */
    fun step(
        dt: Float,
        steer: Float = 0f,
    ) {
        when (phase) {
            GgPhase.Ready -> {
                countdown -= dt
                if (countdown <= 0f) phase = GgPhase.Riding
                return
            }

            GgPhase.Riding -> {
                Unit
            }

            GgPhase.Wiped -> {
                sinceWipe += dt
                return
            }

            else -> {
                return
            }
        }
        clock += dt
        course.ensure(y.toInt() + 2)
        // Speed eases toward cruising, times any card boost, mud and the rider's own pedalling.
        if (boostFor > 0f) boostFor -= dt else boost = 1f
        if (mudFor > 0f) mudFor -= dt
        if (beam > 0f) beam = max(0f, beam - dt)
        pedal *= (1f - min(1f, dt * 0.8f))
        val target = cruise() * boost * (if (muddy) MUD_SLOW else 1f) * (1f + pedal)
        speed += (target - speed) * min(1f, dt * (if (target < speed) 6f else 2.5f))
        val prevY = y
        val prevX = x
        val wasAir = airborne
        y += speed * dt
        if (rampRows > 0f) {
            val flown = min(rampRows, speed * dt)
            rampRows -= flown
            // An angled ramp carries the rider a tile across over the jump.
            x += rampDir * flown / rampTotal
        }
        // Sideways: the card's path, then the held or tilted steer, a trackpad shove and any bounce.
        val m = move
        if (m != null) {
            val before = m.offset(prevY - m.y0)
            val p = y - m.y0
            x += m.offset(p) - before
            if (p >= m.rows) move = null
        }
        x += steer.coerceIn(-1f, 1f) * STEER_SPEED * dt
        val shove = nudged.coerceIn(-NUDGE_SPEED * dt, NUDGE_SPEED * dt)
        x += shove
        nudged -= shove
        val bounce = push.coerceIn(-PUSH_SPEED * dt, PUSH_SPEED * dt)
        x += bounce
        push -= bounce
        val dx = (x - prevX) / dt.coerceAtLeast(0.001f)
        lean += ((dx / 3f).coerceIn(-1f, 1f) - lean) * min(1f, dt * 10f)
        val face = if (lean > FACE_TURN) 1f else if (lean < -FACE_TURN) -1f else 0f
        facing += (face - facing) * min(1f, dt * FACE_SPEED)
        if (abs(steer) > 0.2f || abs(shove) > 0.001f) lateral = if (x > prevX) 1 else -1
        landing = if (wasAir && !airborne) LAND_TIME else max(0f, landing - dt)
        if (tracks.isEmpty() || y - tracks.last().y > TRACK_GAP) {
            tracks.addLast(GgTrack(x, y, airborne))
            if (tracks.size > TRACK_KEEP) tracks.removeFirst()
        }
        moveCritters(dt)
        // Each row the rider's wheel crosses: gates, obstacles and animals on it.
        var r = floor(prevY).toInt() + 1
        while (r <= floor(y).toInt() && phase == GgPhase.Riding) {
            crossRow(r)
            r++
        }
        if (phase == GgPhase.Riding && !onTrail(x, y)) wipe("Off the trail")
        for (i in 0 until HAND) {
            if (hand[i] == null) {
                cooldown[i] -= dt
                if (cooldown[i] <= 0f) {
                    cooldown[i] = 0f
                    hand = hand.toMutableList().also { it[i] = takeCard() }
                }
            }
        }
    }

    /** The trail band at a height between rows, so its edges are as smooth as they are drawn. */
    fun edges(yy: Float): Pair<Float, Float> {
        val r = floor(yy).toInt()
        val a = course.trail(r)
        val b = course.trail(r + 1)
        val u = yy - r
        val left = a.first + (b.first - a.first) * u - 0.5f
        val right = a.last + (b.last - a.last) * u + 0.5f
        return left to right
    }

    private fun onTrail(
        xx: Float,
        yy: Float,
    ): Boolean {
        val (l, r) = edges(yy)
        return xx >= l - EDGE_GRACE && xx <= r + EDGE_GRACE
    }

    private fun crossRow(row: Int) {
        val air = airborne
        val m = move
        var nearMiss = false
        for (col in -1..GG_COLS) {
            val o = course.obstacleAt(col, row) ?: continue
            if (o.x != col) continue
            val centre = o.x + (o.span - 1) / 2f + o.shift
            val d = abs(x - centre)
            val reach = o.span / 2f + RIDER_HALF
            if (d >= reach) {
                if (o.kind in LETHAL && d < reach + CLOSE_CALL_GAP && !air) nearMiss = true
                continue
            }
            if (!hit(o, air, m)) return
        }
        if (nearMiss) style("Close call", CLOSE_CALL)
        for (c in critters) {
            if (c.y != row || c.met) continue
            val d = abs(x - c.x)
            when (c.kind) {
                GgAnimalKind.MARMOT -> {
                    if (d < ANIMAL_REACH) {
                        meet(c)
                        if (air) {
                            style(
                                "Over the marmot",
                                AIR_CLEAR,
                            )
                        } else {
                            bump("A marmot! Combo lost", keepCombo = false, from = c.x)
                        }
                    }
                }

                GgAnimalKind.DEER -> {
                    if (c.bolting && d < ANIMAL_REACH + 0.15f) {
                        meet(c)
                        if (air) {
                            style("Hopped the deer", AIR_CLEAR)
                        } else {
                            wipe("Hit by a deer")
                            return
                        }
                    }
                }
            }
        }
        course.known().firstOrNull { it.row == row }?.let { passGate(it, m) }
    }

    private fun meet(c: GgCritter) {
        critters = critters.map { if (it === c) it.copy(met = true) else it }
    }

    /** The rider meets obstacle [o]. Returns false when it ends the ride. */
    private fun hit(
        o: GgObstacle,
        air: Boolean,
        m: GgMove?,
    ): Boolean {
        val ignoreTerrain = m?.card?.fx("IGNORE_TERRAIN_MODIFIERS") != null
        if (air) {
            when (o.kind) {
                GgKind.PINE_TREE -> {
                    wipe("Hit a pine")
                    return false
                }

                GgKind.BOULDER, GgKind.LOG -> {
                    style("Air clear", AIR_CLEAR)
                }

                else -> {
                    Unit
                }
            }
            return true
        }
        when (o.kind) {
            GgKind.PINE_TREE, GgKind.BOULDER, GgKind.LOG -> {
                wipe(
                    when (o.kind) {
                        GgKind.PINE_TREE -> "Hit a pine"
                        GgKind.BOULDER -> "Hit a boulder"
                        else -> "Hit a log"
                    },
                )
                return false
            }

            GgKind.STUMP, GgKind.SMALL_ROCK -> {
                when {
                    smashLeft > 0 -> {
                        smashLeft--
                        style("Trail Bash", SMASH)
                    }

                    o.kind == GgKind.SMALL_ROCK && rider.perk == "Bash Bar" -> {
                        style("Bash Bar", BASH_BAR)
                    }

                    else -> {
                        val keep = o.kind == GgKind.STUMP && rider.perk == "Sure-Footed"
                        bump(if (keep) "Bounced off a stump, combo kept" else "Bump: combo lost", keep, o.x + (o.span - 1) / 2f + o.shift)
                    }
                }
            }

            GgKind.RAMP -> {
                rampRows = RAMP_ROWS
                rampTotal = RAMP_ROWS
                rampDir = o.dir
                move = null
                speedUp(1.15f, 0.8f)
                style(
                    when (o.dir) {
                        -1 -> "Kicker left!"
                        1 -> "Kicker right!"
                        else -> "Ramp!"
                    },
                    RAMP_BONUS,
                )
            }

            GgKind.BERM_LEFT, GgKind.BERM_RIGHT -> {
                push += if (o.kind == GgKind.BERM_LEFT) 2f else -2f
                move = null
                say("Berm sling")
            }

            GgKind.MUD -> {
                if (!ignoreTerrain) {
                    if (!muddy) say("Mud! Slowed down", false)
                    mudFor = MUD_SECONDS
                }
            }

            GgKind.GRAVEL -> {
                if (!ignoreTerrain) {
                    push += if (lateral != 0) lateral.toFloat() else towardCentre()
                    say("Loose gravel", false)
                }
            }
        }
        return true
    }

    private fun towardCentre(): Float {
        val (l, r) = edges(y)
        return if (x < (l + r) / 2f) 1f else -1f
    }

    /** A knock off a stump, rock or marmot: away from what was hit at [from], unless that is off the trail. */
    private fun bump(
        text: String,
        keepCombo: Boolean,
        from: Float = Float.NaN,
    ) {
        val away = if (from.isNaN() || x == from) towardCentre() else if (x > from) 1f else -1f
        push += if (onTrail(x + away, y)) away else towardCentre()
        move = null
        if (!keepCombo) {
            combo = 1
            chain = 0
        }
        say(text, keepCombo)
    }

    private fun style(
        text: String,
        points: Int,
    ) {
        val pts = Math.round(points * biomeMult)
        score += pts
        best = max(best, score)
        say("$text +$pts")
    }

    private fun passGate(
        g: GgGate,
        m: GgMove?,
    ) {
        if (x < g.left - GATE_SLACK || x > g.right + GATE_SLACK) {
            combo = 1
            chain = 0
            say("Missed gate ${g.number}: combo reset", false)
            return
        }
        val mult = m?.card?.fx("GATE_POINTS_MULTIPLIER")?.factor ?: 1f
        val pts = Math.round(g.points * combo * biomeMult * mult)
        score += pts
        gates = g.number
        chain++
        say("Gate ${g.number}  +$pts  x$combo")
        combo = min(MAX_COMBO, combo + 1 + (m?.card?.fx("BONUS_COMBO_ON_GATE")?.n ?: 0))
        if (g.redraw && redraws < MAX_REDRAWS) {
            redraws++
            say("+1 Redraw")
        }
        if (rider.perk == "Flow State" && chain % 3 == 0 && redraws < MAX_REDRAWS) {
            redraws++
            say("Flow State: +1 Redraw")
        }
        best = max(best, score)
        val st = course.stageOf(g.number)
        if (st.unlockGate == g.number && st.unlockId.isNotEmpty()) {
            unlocks += st.unlockId
            say(unlockText(st.unlockId))
        }
        checkpoint = snap(g)
        if (g.draft) {
            draftChoices = draftOptions(g)
            if (draftChoices.isNotEmpty()) phase = GgPhase.Drafting
        }
        if (course.isStageEnd(g.number)) {
            clearedStage = st
            if (phase != GgPhase.Drafting) phase = GgPhase.StageClear
        }
    }

    private fun moveCritters(dt: Float) {
        critters =
            critters.mapNotNull { c ->
                when (c.kind) {
                    GgAnimalKind.MARMOT -> {
                        val (l, r) = edges(c.y.toFloat())
                        var d = c.dir
                        var nx = c.x + d * MARMOT_SPEED * dt
                        if (nx < l + 0.5f || nx > r - 0.5f) {
                            d = -d
                            nx = nx.coerceIn(l + 0.5f, r - 0.5f)
                        }
                        c.copy(x = nx, dir = d)
                    }

                    GgAnimalKind.DEER -> {
                        when {
                            c.bolting -> {
                                val nx = c.x + c.dir * DEER_SPEED * dt
                                val (l, r) = edges(c.y.toFloat())
                                if (nx < l - 1.5f || nx > r + 1.5f) null else c.copy(x = nx)
                            }

                            c.state == GgDeerState.ALERT -> {
                                val t = c.alertFor + dt
                                if (t >= DEER_FREEZE) {
                                    // It bolts away from the near edge, straight across the rider's line.
                                    val (l, r) = edges(c.y.toFloat())
                                    c.copy(alertFor = t, bolting = true, dir = if (c.x < (l + r) / 2f) 1 else -1)
                                } else {
                                    c.copy(alertFor = t)
                                }
                            }

                            c.y > y && c.y - y <= DEER_SIGHT -> {
                                c.copy(state = GgDeerState.ALERT)
                            }

                            else -> {
                                c
                            }
                        }
                    }
                }
            }
    }

    private fun wipe(reason: String) {
        sinceWipe = 0f
        wipeReason = reason
        say(reason, false)
        phase = GgPhase.Wiped
        move = null
        best = max(best, score)
    }

    private fun unlockText(id: String) =
        when (id) {
            "rider_spokes" -> "Spokes (Red Fox) unlocked!"
            "rider_knobby" -> "Knobby (Badger) unlocked!"
            "biome_night_ride_oled" -> "Night Ride unlocked!"
            else -> "Unlocked"
        }

    private fun draftOptions(gate: GgGate): List<GgCard> {
        val filter = gate.draftFilter.ifEmpty { listOf("COMMON", "TACTICAL", "RARE") }
        val pool =
            GravelGearsData.cards.filter {
                it.rarity in filter && it.rarity != "STARTER" &&
                    (!it.id.startsWith("high_beam") || course.night)
            }
        return pool.shuffled(rnd).take(3)
    }

    fun pick(index: Int) {
        if (phase != GgPhase.Drafting) return
        draftChoices.getOrNull(index)?.let {
            deck += it
            discard += it
            say("Added ${it.name} to your deck")
        }
        closeDraft()
    }

    fun skipDraft() {
        if (phase == GgPhase.Drafting) closeDraft()
    }

    private fun closeDraft() {
        draftChoices = emptyList()
        if (clearedStage != null && course.isStageEnd(gates)) {
            phase = GgPhase.StageClear
        } else {
            ready(SHORT_COUNT)
        }
        checkpoint = snap(course.gateByNumber(gates))
    }

    /** Leaves the stage panel and carries on down the trail. */
    fun continueRide() {
        if (phase != GgPhase.StageClear) return
        clearedStage = null
        ready(SHORT_COUNT)
    }

    private fun ready(seconds: Float) {
        phase = GgPhase.Ready
        countdown = seconds
        speed = 0f
        pedal = 0f
        boostFor = 0f
        boost = 1f
        nudged = 0f
        push = 0f
        rampRows = 0f
        rampDir = 0
        move = null
    }

    private fun snap(g: GgGate? = null) =
        GgSnapshot(
            x = g?.let { (it.left + it.right) / 2f } ?: x,
            y = g?.let { it.row + 0.05f } ?: y,
            score = score,
            gates = gates,
            redraws = redraws,
            draw = draw.toList(),
            discard = discard.toList(),
            hand = hand.toList(),
            deck = deck.toList(),
            critters = critters,
        )

    /** After a wipeout: back to the last gate, combo gone, score kept, with a short count to get ready. */
    fun retry() {
        if (phase != GgPhase.Wiped) return
        val s = checkpoint ?: return
        x = s.x
        y = s.y
        score = s.score
        gates = s.gates
        redraws = s.redraws
        draw = ArrayList(s.draw)
        discard = ArrayList(s.discard)
        hand = s.hand
        deck = ArrayList(s.deck)
        critters = s.critters
        cooldown = FloatArray(HAND)
        for (i in 0 until HAND) if (hand[i] == null) hand = hand.toMutableList().also { it[i] = takeCard() }
        combo = 1
        chain = 0
        mudFor = 0f
        wipeReason = ""
        lean = 0f
        facing = 0f
        tracks.clear()
        landing = 0f
        ready(START_COUNT)
        say(if (gates == 0) "Back at the top" else "Back at gate $gates")
    }

    companion object {
        const val HAND = 4
        const val LAND_TIME = 0.25f
        const val TRACK_GAP = 0.12f
        const val TRACK_KEEP = 16
        const val MAX_REDRAWS = 2
        const val MAX_COMBO = 8
        const val RAMP_BONUS = 50
        const val CLOSE_CALL = 25
        const val AIR_CLEAR = 75
        const val SMASH = 100
        const val BASH_BAR = 50
        const val DEER_SIGHT = 4

        /** Seconds before a played card's slot fills again. */
        const val COOLDOWN = 1.1f
        const val FAST_COOLDOWN = 0.3f
        const val START_COUNT = 1.5f
        const val SHORT_COUNT = 0.9f

        /** Tiles a second for a full lean or a held key, and for a trackpad shove. */
        const val STEER_SPEED = 3.4f
        const val NUDGE = 0.5f
        const val MAX_NUDGE = 1.2f
        const val NUDGE_SPEED = 5f
        const val PUSH_SPEED = 6f
        const val PEDAL_STEP = 0.15f
        const val MUD_SLOW = 0.6f
        const val MUD_SECONDS = 1.5f
        const val RAMP_ROWS = 2.4f
        const val RIDER_HALF = 0.12f

        /** How hard a lean turns the bike to face across the trail, and how fast it turns and straightens. */
        const val FACE_TURN = 0.15f

        /** Rows a second added for every gate passed, up to a cap. */
        const val RAMP_UP = 0.025f
        const val RAMP_UP_MAX = 0.6f
        const val FACE_SPEED = 7f
        const val CLOSE_CALL_GAP = 0.35f
        const val ANIMAL_REACH = 0.6f
        const val GATE_SLACK = 0.5f
        const val EDGE_GRACE = 0.05f
        const val MARMOT_SPEED = 1.1f
        const val DEER_SPEED = 4.5f
        const val DEER_FREEZE = 0.45f
        private const val GG_COLS = 9
        private val LETHAL = setOf(GgKind.PINE_TREE, GgKind.BOULDER, GgKind.LOG)
    }
}
