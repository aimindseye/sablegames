package org.sableos.games

import kotlin.math.min
import kotlin.random.Random

internal enum class GgPhase { Riding, Drafting, StageClear, Wiped }

/** A moment worth a line on screen: a gate scored, a perk fired, something unlocked. */
internal data class GgEvent(
    val text: String,
    val good: Boolean = true,
)

/** What a run keeps between turns, so Retry can return to the last gate and Undo to the last card. */
private data class GgSnapshot(
    val x: Int,
    val y: Int,
    val score: Int,
    val combo: Int,
    val gates: Int,
    val chain: Int,
    val redraws: Int,
    val draw: List<GgCard>,
    val discard: List<GgCard>,
    val hand: List<GgCard>,
    val deck: List<GgCard>,
    val mud: Boolean,
    val lateral: Int,
    val animals: List<GgAnimal>,
    val beam: Int,
    val extraDraw: Int,
)

/**
 * One ride: a rider on a course with a deck. Cards are played with [play] (after [preview] shows what they do),
 * every [DRAFT_EVERY]th gate offers a draft, a stage's last gate ends in a panel, and a crash ends in a wipeout
 * that [retry]s from the last gate.
 */
internal class GgRun(
    val course: GgCourse,
    val rider: GgRider,
    seed: Long = 7L,
    val biomeMult: Float = if (course.night) 1.5f else 1.0f,
) {
    private val rnd = Random(seed)
    var x = course.gate(0).centerX
        private set
    var y = 0
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
    var phase = GgPhase.Riding
        private set
    var hand: List<GgCard> = emptyList()
        private set
    var animals: List<GgAnimal> = course.animals
        private set
    var draftChoices: List<GgCard> = emptyList()
        private set
    var events: List<GgEvent> = emptyList()
        private set
    var undos = START_UNDOS
        private set
    var wipeReason = ""
        private set
    var clearedStage: GgStage? = null
        private set

    /** Turns the headlight stays at full strength (the High Beam card). */
    var beam = 0
        private set
    val unlocks = ArrayList<String>()

    private var deck = ArrayList<GgCard>()
    private var draw = ArrayList<GgCard>()
    private var discard = ArrayList<GgCard>()
    private var mud = false
    private var lateral = 0
    private var chain = 0
    private var extraDraw = 0
    private var checkpoint: GgSnapshot? = null
    private var undo: GgSnapshot? = null

    init {
        startDeck()
        course.ensure(0)
        refill()
        checkpoint = snap()
    }

    val deckSize: Int get() = draw.size + discard.size + hand.size
    val drawPile: Int get() = draw.size
    val isNight: Boolean get() = course.night
    val stage: GgStage get() = course.stageOf(min(gates + 1, course.handcraftedGates).coerceAtLeast(1))
    val nextGate: GgGate get() = course.nextGate(y)

    private fun startDeck() {
        deck.clear()
        for (c in GravelGearsData.cards) repeat(c.starterQty) { deck += c }
        GravelGearsData.cards.firstOrNull { it.id == rider.signatureCard }?.let { deck += it }
        draw = ArrayList(deck.shuffled(rnd))
        discard = ArrayList()
    }

    private fun drawCards(n: Int) {
        val out = ArrayList(hand)
        repeat(n) {
            if (draw.isEmpty()) {
                draw = ArrayList(discard.shuffled(rnd))
                discard.clear()
            }
            if (draw.isNotEmpty()) out += draw.removeAt(draw.size - 1)
        }
        hand = out
    }

    private fun refill() {
        drawCards(HAND - hand.size + extraDraw)
        extraDraw = 0
    }

    private fun context() = GgContext(mud = mud, lastLateral = lateral, perk = rider.perk.perkType(), animals = animals)

    /** What playing the card in [slot] would do from here, without doing it. */
    fun preview(slot: Int): GgOutcome? {
        val card = hand.getOrNull(slot) ?: return null
        course.ensure(y)
        return GgSim.resolve(course, x, y, card, context())
    }

    private fun snap() =
        GgSnapshot(x, y, score, combo, gates, chain, redraws, draw.toList(), discard.toList(), hand.toList(), deck.toList(), mud, lateral, animals, beam, extraDraw)

    private fun restore(s: GgSnapshot) {
        x = s.x; y = s.y; score = s.score; combo = s.combo; gates = s.gates; chain = s.chain; redraws = s.redraws
        draw = ArrayList(s.draw); discard = ArrayList(s.discard); hand = s.hand; deck = ArrayList(s.deck)
        mud = s.mud; lateral = s.lateral; animals = s.animals; beam = s.beam; extraDraw = s.extraDraw
    }

    /** Plays the card in [slot]. Returns false when nothing could be played. */
    fun play(slot: Int): Boolean {
        if (phase != GgPhase.Riding) return false
        val card = hand.getOrNull(slot) ?: return false
        course.ensure(y)
        val before = snap()
        val o = GgSim.resolve(course, x, y, card, context())
        val ev = ArrayList<GgEvent>()
        hand = hand.filterIndexed { i, _ -> i != slot }
        discard += card
        undo = before
        x = o.endX
        y = o.endY
        lateral = o.lastLateral
        if (o.crash != null) {
            wipeReason = crashText(o)
            events = listOf(GgEvent(wipeReason, false))
            phase = GgPhase.Wiped
            best = maxOf(best, score)
            return true
        }
        mud = o.mud
        if (card.fx("CLEAR_TERRAIN_DEBUFFS") != null) {
            mud = false
            redraws = min(MAX_REDRAWS, redraws + 1)
            ev += GgEvent("Skid Stop: mud cleared, +1 Redraw")
        }
        if (card.fx("NEXT_TURN_EXTRA_DRAW") != null) extraDraw += card.fx("NEXT_TURN_EXTRA_DRAW")!!.n
        if (beam > 0) beam--
        card.fx("EXPAND_HEADLIGHT_FULLSCREEN")?.let { beam = it.n }
        if (o.bump != null && !o.bumpKeepsCombo) {
            combo = 1
            if (!o.animalBump) ev += GgEvent("Bump: combo lost", false) else ev += GgEvent("A marmot! Combo lost", false)
        }
        val air = card.fx("AIR_BONUS_ON_CLEAR")?.n ?: 0
        val comboPlus = card.fx("BONUS_COMBO_ON_GATE")?.n ?: 0
        val gateMult = card.fx("GATE_POINTS_MULTIPLIER")?.factor ?: 1f
        var style = o.styleBonus + o.smashPoints + (if (air > 0) o.airClears * air else 0)
        if (o.ramp) style += RAMP_BONUS
        if (o.closeCall) style += CLOSE_CALL
        if (o.bankShot) style += BANK_SHOT
        var needsPick = false
        var stageEnded: GgStage? = null
        if (o.gatesHit.isEmpty() && style > 0 && o.gatesMissed.isEmpty()) {
            // Style off the gates is still worth something, at the current combo.
            val pts = Math.round(style * biomeMult)
            score += pts
            ev += GgEvent("Style +$pts")
        }
        for (g in o.gatesHit) {
            val pts = Math.round((g.points + style) * combo * biomeMult * gateMult)
            score += pts
            style = 0
            gates = g.number
            chain++
            ev += GgEvent("Gate ${g.number}  +$pts  x$combo")
            combo = min(MAX_COMBO, combo + 1 + comboPlus)
            if (g.redraw && redraws < MAX_REDRAWS) {
                redraws++
                ev += GgEvent("+1 Redraw")
            }
            if (rider.perk.perkType() == "REDRAW_RECHARGE_CONSECUTIVE_GATES" && chain % 3 == 0 && redraws < MAX_REDRAWS) {
                redraws++
                ev += GgEvent("Flow State: +1 Redraw")
            }
            if (g.draft) needsPick = true
            val st = course.stageOf(g.number)
            if (st.unlockGate == g.number && st.unlockId.isNotEmpty()) {
                unlocks += st.unlockId
                ev += GgEvent(unlockText(st.unlockId))
            }
            if (course.isStageEnd(g.number)) stageEnded = st
        }
        if (o.gatesMissed.isNotEmpty()) {
            combo = 1
            chain = 0
            ev += GgEvent("Missed the gate: combo reset", false)
        }
        best = maxOf(best, score)
        animals = advanceAnimals()
        refill()
        events = ev
        if (needsPick) {
            draftChoices = draftOptions(o.gatesHit.last { it.draft })
            if (draftChoices.isNotEmpty()) phase = GgPhase.Drafting
        }
        if (stageEnded != null) {
            clearedStage = stageEnded
            if (phase != GgPhase.Drafting) phase = GgPhase.StageClear
        }
        if (o.gatesHit.isNotEmpty() && phase == GgPhase.Riding) checkpoint = snap()
        return true
    }

    private fun crashText(o: GgOutcome): String {
        val c = o.crash!!
        val ob = course.obstacleAt(c.x, c.y)
        val a = animals.firstOrNull { it.kind == GgAnimalKind.DEER && it.state == GgDeerState.ALERT && it.y == c.y }
        return when {
            a != null -> "Hit by a deer"
            c.x !in course.trail(c.y) -> "Off the trail"
            ob?.kind == GgKind.PINE_TREE -> "Hit a pine"
            ob?.kind == GgKind.BOULDER -> "Hit a boulder"
            ob?.kind == GgKind.LOG -> "Hit a log"
            else -> "Wiped out"
        }
    }

    private fun unlockText(id: String) =
        when (id) {
            "rider_spokes" -> "Spokes (Red Fox) unlocked!"
            "rider_knobby" -> "Knobby (Badger) unlocked!"
            "biome_night_ride_oled" -> "Night Ride unlocked!"
            else -> "Unlocked"
        }

    /** Marmots take a step along their row; a deer that has seen the rider is gone, one that sees him now is alerted. */
    private fun advanceAnimals(): List<GgAnimal> =
        animals.mapNotNull { a ->
            when (a.kind) {
                GgAnimalKind.MARMOT -> {
                    val t = course.trail(a.y)
                    var d = a.dir
                    var nx = a.x + d
                    if (nx !in t) {
                        d = -d
                        nx = a.x + d
                    }
                    a.copy(x = nx.coerceIn(t.first, t.last), dir = d)
                }

                GgAnimalKind.DEER ->
                    when (a.state) {
                        GgDeerState.ALERT -> null
                        else -> if (a.y > y && a.y - y <= DEER_SIGHT) a.copy(state = GgDeerState.ALERT) else a
                    }
            }
        }

    private fun draftOptions(gate: GgGate): List<GgCard> {
        val filter = gate.draftFilter.ifEmpty { listOf("COMMON", "TACTICAL", "RARE") }
        val pool = GravelGearsData.cards.filter { it.rarity in filter && it.rarity != "STARTER" && (!it.id.startsWith("high_beam") || course.night) }
        return pool.shuffled(rnd).take(3)
    }

    fun pick(index: Int) {
        if (phase != GgPhase.Drafting) return
        draftChoices.getOrNull(index)?.let {
            deck += it
            discard += it
            events = listOf(GgEvent("Added ${it.name} to your deck"))
        }
        closeDraft()
    }

    fun skipDraft() {
        if (phase == GgPhase.Drafting) closeDraft()
    }

    private fun closeDraft() {
        draftChoices = emptyList()
        phase = if (clearedStage != null && course.isStageEnd(gates)) GgPhase.StageClear else GgPhase.Riding
        checkpoint = snap()
    }

    /** Leaves the stage panel and carries on down the trail. */
    fun continueRide() {
        if (phase != GgPhase.StageClear) return
        clearedStage = null
        phase = GgPhase.Riding
        checkpoint = snap()
    }

    /** Spends a Redraw: the hand goes to the discard and four fresh cards come. */
    fun redraw(): Boolean {
        if (phase != GgPhase.Riding || redraws <= 0) return false
        redraws--
        discard += hand
        hand = emptyList()
        refill()
        return true
    }

    /** Takes back the last card. */
    fun undoLast(): Boolean {
        val s = undo ?: return false
        if (phase != GgPhase.Riding || undos <= 0) return false
        restore(s)
        undos--
        undo = null
        events = listOf(GgEvent("Card taken back"))
        return true
    }

    /** After a wipeout: back to the last gate, combo gone, score kept. */
    fun retry() {
        val s = checkpoint ?: return
        restore(s)
        combo = 1
        chain = 0
        phase = GgPhase.Riding
        wipeReason = ""
        events = listOf(GgEvent("Back at gate ${gates}"))
    }

    companion object {
        const val HAND = 4
        const val MAX_REDRAWS = 2
        const val MAX_COMBO = 8
        const val RAMP_BONUS = 50
        const val CLOSE_CALL = 50
        const val BANK_SHOT = 100
        const val START_UNDOS = 3
        const val DEER_SIGHT = 4
    }
}

private fun String.perkType(): String =
    when (this) {
        "Sure-Footed" -> "KEEP_COMBO_ON_STUMP_BOUNCE"
        "Flow State" -> "REDRAW_RECHARGE_CONSECUTIVE_GATES"
        "Bash Bar" -> "SMASH_SMALL_ROCKS_PASSIVE"
        else -> this
    }
