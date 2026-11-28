package org.sableos.games

import org.sableos.games.TbWorld.angDiff
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/*
 * One descent into a Gate of Tartarus Breach: the player, the stages, the weapons and the boons. [step] moves the
 * world on a frame at a time; the foes' behaviour is in TartarusFoes.kt. Follows the owner's handoff (GDD, keymap and
 * JSON data), with the three small changes he approved: 25 % of max HP back after each wave, a slow Favor trickle,
 * and a Sunbow combo that grows with hits in a row.
 */

internal enum class TbPhase { Ready, Combat, Draft, Sealed, Fallen }

internal class TbRun(
    val gate: TbGate,
    val meta: TbMeta = TbMeta(),
    seed: Long = 1L,
) {
    internal val rnd = Random(seed)
    val weapons = TartarusData.weapons
    var phase = TbPhase.Ready
        private set
    var stageIndex = 0
        private set
    val stage get() = gate.stages[stageIndex]
    var clock = 0f
        private set
    var readyT = READY_TIME
        private set
    var runTime = 0f
        private set
    var stageT = 0f
        private set

    // The player.
    var px = 0f
    var py = 0f
    var yaw = (PI / 2).toFloat()
    var hp = 0f
    var maxHp = BASE_HP + 10f * meta.level(TbUpgrade.Vigor)
        private set

    /** A short freeze after a kill, so hits land with weight. */
    private var hitStop = 0f
    var favor = 40f
    var obols = 0
        private set
    var kills = 0
        private set
    var weaponIdx = 0
        private set
    var invuln = 0f

    /** What happened this frame; the screen plays sounds for these and clears the list. */
    val events = ArrayList<TbEvent>()

    /** Recent hits on the player and the direction each came from, for the red arrows. */
    val hitDirs = ArrayList<TbHitDir>()

    internal fun emit(e: TbEvent) {
        if (events.size < 24) events += e
    }
    var dashT = 0f
    var dashCd = 0f
    private var dashX = 0f
    private var dashY = 0f
    var slowT = 0f
    var fireCd = 0f
    var kick = 0f
    var shake = 0f
    var hurtFlash = 0f
    var hitMarker = 0f
    var swapT = 0f
    var streak = 0
        private set
    var streakT = 0f
        private set
    var combo = 0
        private set
    private var quickTurn = 0f
    private var hitCount = 0
    var moveSpeed = 0f
        private set
    var strafeSpeed = 0f
        private set

    /** The foe the crosshair is locked on, for drawing the lock. */
    var aimTarget: TbEnemy? = null
        private set
    var wipeReason = ""
        private set

    val boons = LinkedHashMap<String, Int>()
    var draftChoices: List<TbBoon> = emptyList()
        private set

    internal val enemies = ArrayList<TbEnemy>()
    internal val projs = ArrayList<TbProj>()
    internal val teles = ArrayList<TbTele>()
    internal val zones = ArrayList<TbZone>()
    internal val pillars = ArrayList<TbPillar>()
    val fx = ArrayList<TbFx>()
    val toasts = ArrayList<TbToast>()
    private var spawnT = 0f
    private var minionT = 0f
    private var uid = 1
    private var lavaTick = 0f
    private var pendingTurn = 0f
    private var sealedAt = 0f

    /** True once the Guardian of this gate has fallen. */
    val sealed get() = phase == TbPhase.Sealed
    val boss: TbEnemy? get() = enemies.firstOrNull { it.boss && it.alive }
    val radius get() = stage.radius
    val weapon get() = weapons[weaponIdx]
    val wave get() = stageIndex + 1
    val aliveCount get() = enemies.count { it.alive }
    val timeLeft get() = max(0f, stageT)

    // Stats the boons and Shrine upgrades give.
    private fun mul(stat: String): Float {
        val base = boons.entries.fold(1f) { acc, (id, n) -> TartarusData.boons.first { it.id == id }.let { if (it.stat == stat && it.operation == "multiply") acc * it.value.pow(n) else acc } }
        return if (stat == "fire_rate_multiplier" && paired("ares_bloodlust", "artemis_crit_mark")) base * 0.9f else base
    }

    private fun add(stat: String): Float {
        val base = boons.entries.sumOf { (id, n) -> TartarusData.boons.first { it.id == id }.let { if (it.stat == stat && it.operation == "add") (it.value * n).toDouble() else 0.0 } }.toFloat()
        return when {
            stat == "proc_chain_damage" && paired("zeus_chain_arc", "poseidon_undertow") -> base * 1.5f
            stat == "burn_dps" && paired("apollo_radiance", "hephaestus_burn") -> base * 1.5f
            stat == "thorns_reflect_pct" && paired("athena_aegis", "hades_soul_siphon") && base > 0f -> base + 0.2f
            else -> base
        }
    }

    private fun paired(
        a: String,
        b: String,
    ) = (boons[a] ?: 0) > 0 && (boons[b] ?: 0) > 0

    /** The pair bonus choosing [b] would complete, if any. */
    fun completes(b: TbBoon): String? = SYNERGIES.firstOrNull { (x, y, _) -> (b.id == x && (boons[y] ?: 0) > 0 && (boons[x] ?: 0) == 0) || (b.id == y && (boons[x] ?: 0) > 0 && (boons[y] ?: 0) == 0) }?.third

    val fireMult get() = mul("fire_rate_multiplier")
    val moveMult get() = mul("move_speed_multiplier")
    val knockMult get() = mul("knockback_multiplier")
    val dmgMult get() = mul("damage_multiplier") * (1f + 0.05f * meta.level(TbUpgrade.Might))
    val thorns get() = add("thorns_reflect_pct")
    val burnDps get() = add("burn_dps")
    val critChance get() = add("crit_chance_pct")
    val favorRegen get() = FAVOR_TRICKLE + 0.5f * meta.level(TbUpgrade.Grace)
    val dashCooldown get() = DASH_CD * (1f - 0.08f * meta.level(TbUpgrade.Wings)) / (1f + (moveMult - 1f))

    init {
        hp = maxHp
        setupStage()
    }

    // ---- input from the screen ------------------------------------------------------------------------------------

    /** A trackpad tick or swipe: turn by [rad] at once (positive turns right). */
    fun turnBy(rad: Float) {
        pendingTurn += rad
    }

    fun quickTurn() {
        if (quickTurn <= 0f && phase != TbPhase.Fallen) quickTurn = QUICK_TURN
    }

    fun selectWeapon(i: Int) {
        if (i !in weapons.indices || i == weaponIdx || phase == TbPhase.Fallen) return
        weaponIdx = i
        swapT = 0.28f
        fireCd = max(fireCd, 0.12f)
        combo = 0
    }

    /** Hermes' Dash: a short burst the way you are moving (forward when standing still), safe from hits. */
    fun dash(
        forward: Float = 0f,
        strafe: Float = 0f,
    ) {
        if (phase != TbPhase.Combat && phase != TbPhase.Ready) return
        if (dashCd > 0f || dashT > 0f) return
        var f = forward
        var s = strafe
        if (f == 0f && s == 0f) f = 1f
        val len = hypot(f, s)
        f /= len
        s /= len
        dashX = cos(yaw) * f + sin(yaw) * s
        dashY = sin(yaw) * f - cos(yaw) * s
        dashT = DASH_TIME
        dashCd = dashCooldown
        emit(TbEvent.Dash)
        fx += TbFx(TbFxKind.Puff, px, py, 0f, 0f, 0xFF00E5A0, 0.35f)
    }

    fun pickBoon(i: Int) {
        if (phase != TbPhase.Draft || i !in draftChoices.indices) return
        val b = draftChoices[i]
        val pair = completes(b)
        boons[b.id] = (boons[b.id] ?: 0) + if (b.cursed) 2 else 1
        say("${b.name}${if (boons.getValue(b.id) > 1) " x${boons.getValue(b.id)}" else ""}")
        if (b.cursed) {
            maxHp = (maxHp * CURSE_HP).roundToInt().toFloat()
            hp = min(hp, maxHp)
        }
        if (pair != null) say(pair)
        draftChoices = emptyList()
        stageIndex++
        setupStage()
        phase = TbPhase.Ready
        readyT = READY_TIME
    }

    fun say(
        text: String,
        good: Boolean = true,
    ) {
        toasts += TbToast(text, good, clock)
        if (toasts.size > 4) toasts.removeAt(0)
    }

    // ---- the frame ------------------------------------------------------------------------------------------------

    fun step(
        dtRaw: Float,
        input: TbInput,
    ) {
        val dt = dtRaw.coerceIn(0f, 0.05f)
        clock += dt
        shake = max(0f, shake - dt * 3.2f)
        hurtFlash = max(0f, hurtFlash - dt * 2.4f)
        hitMarker = max(0f, hitMarker - dt)
        hitDirs.removeAll { clock - it.at > HIT_ARROW_TIME }
        kick = max(0f, kick - dt * 5f)
        swapT = max(0f, swapT - dt)
        toasts.removeAll { clock - it.at > TOAST_TIME }
        stepFx(dt)
        if (phase == TbPhase.Combat && hitStop > 0f) {
            hitStop -= dt
            return
        }
        when (phase) {
            TbPhase.Ready -> {
                movePlayer(dt, input)
                readyT -= dt
                if (readyT <= 0f) phase = TbPhase.Combat
            }

            TbPhase.Combat -> {
                combat(dt, input)
            }

            TbPhase.Sealed -> {
                sealedAt += dt
                enemies.forEach { if (!it.alive) it.fall += dt / DEATH_TIME }
            }

            else -> {
                Unit
            }
        }
    }

    private fun combat(
        dt: Float,
        input: TbInput,
    ) {
        runTime += dt
        if (!stage.boss) stageT -= dt
        movePlayer(dt, input)
        favor = min(MAX_FAVOR, favor + favorRegen * dt)
        streakT -= dt
        if (streakT <= 0f) streak = 0
        if (input.fire) tryFire()
        spawnFoes(dt)
        for (e in enemies.toList()) updateFoe(e, dt)
        enemies.removeAll { !it.alive && it.fall >= 1f }
        stepProjs(dt)
        stepTeles(dt)
        stepZones(dt)
        stepPillars(dt)
        if (phase != TbPhase.Combat) return
        if (stage.boss) {
            if (boss == null) seal()
        } else if (stageT <= 0f) {
            clearStage()
        }
    }

    // ---- stages ---------------------------------------------------------------------------------------------------

    private fun setupStage() {
        enemies.clear()
        projs.clear()
        teles.clear()
        pillars.clear()
        zones.clear()
        pillars += TbWorld.pillars(stage.pillars, stage.radius)
        zones += TbWorld.zones(stage.hazard, stage.radius, rnd)
        stageT = stage.durationSec * DURATION_SCALE
        spawnT = if (early) EARLY_NEXT_SPAWN else FIRST_SPAWN
        minionT = 2f
        px = 0f
        py = -stage.radius * 0.3f
        yaw = (PI / 2).toFloat()
        dashT = 0f
        if (!stage.boss && stage.pool.isNotEmpty()) {
            // One foe is already there, in front of you, so there is something to shoot straight away.
            spawn(stage.pool.first().id, ahead = true)
        }
        if (stage.boss) {
            val def = TartarusData.enemies.first { it.id == stage.bossId }
            enemies += TbEnemy(def, 0f, stage.radius * 0.62f, uid++).also { it.timer = 1.8f }
        }
    }

    private fun clearStage() {
        enemies.filter { it.alive }.forEach { banish(it) }
        projs.clear()
        teles.clear()
        hp = min(maxHp, hp + maxHp * WAVE_HEAL)
        say("Wave ${wave} cleared: +${(maxHp * WAVE_HEAL).roundToInt()} HP")
        emit(TbEvent.Draft)
        draftChoices = rollDraft()
        phase = if (draftChoices.isEmpty()) TbPhase.Ready else TbPhase.Draft
        if (draftChoices.isEmpty()) {
            stageIndex++
            setupStage()
        }
    }

    private fun rollDraft(): List<TbBoon> {
        val pool = TartarusData.boons.filter { (boons[it.id] ?: 0) < it.maxStacks }.toMutableList()
        val out = ArrayList<TbBoon>()
        while (out.size < 3 && pool.isNotEmpty()) out += pool.removeAt(rnd.nextInt(pool.size))
        // From the second wave on, one card may be a cursed boon: two stacks at once, at the cost of max HP.
        if (stageIndex >= 1 && out.isNotEmpty() && rnd.nextFloat() < CURSE_CHANCE) {
            val k = rnd.nextInt(out.size)
            val b = out[k]
            if ((boons[b.id] ?: 0) + 2 <= b.maxStacks) {
                out[k] = b.copy(name = "Cursed ${b.name}", description = "${b.description}, twice over. Max HP -10%", cursed = true)
            }
        }
        return out
    }

    private fun seal() {
        phase = TbPhase.Sealed
        sealedAt = 0f
        enemies.filter { it.alive }.forEach { banish(it) }
        projs.clear()
        teles.clear()
        say("${gate.name} sealed")
        emit(TbEvent.Seal)
    }

    private fun fall(reason: String) {
        if (phase == TbPhase.Fallen) return
        phase = TbPhase.Fallen
        emit(TbEvent.Fall)
        wipeReason = reason
        hp = 0f
        shake = 1f
    }

    // ---- the player -----------------------------------------------------------------------------------------------

    private fun movePlayer(
        dt: Float,
        input: TbInput,
    ) {
        invuln = max(0f, invuln - dt)
        fireCd = max(0f, fireCd - dt)
        dashCd = max(0f, dashCd - dt)
        slowT = max(0f, slowT - dt)
        yaw -= input.turn * TURN_SPEED * dt + pendingTurn
        pendingTurn = 0f
        if (quickTurn > 0f) {
            val step = min(quickTurn, dt)
            yaw += (PI.toFloat() * step / QUICK_TURN)
            quickTurn -= step
        }
        var f = input.forward
        var s = input.strafe
        val len = hypot(f, s)
        if (len > 1f) {
            f /= len
            s /= len
        }
        moveSpeed += (f - moveSpeed) * min(1f, dt * 14f)
        strafeSpeed += (s - strafeSpeed) * min(1f, dt * 14f)
        var speed = BASE_SPEED * moveMult
        if (slowT > 0f || zones.any { it.kind in SLOWING && TbWorld.dist(px, py, it.x, it.y) < it.r }) speed *= SLOWED
        var vx = (cos(yaw) * moveSpeed + sin(yaw) * strafeSpeed) * speed
        var vy = (sin(yaw) * moveSpeed - cos(yaw) * strafeSpeed) * speed
        if (dashT > 0f) {
            dashT -= dt
            vx = dashX * DASH_SPEED * moveMult
            vy = dashY * DASH_SPEED * moveMult
            if (hitFx(dt)) fx += TbFx(TbFxKind.Ember, px, py, 0f, 0f, 0xFF00E5A0, 0.3f)
        }
        for (z in zones) {
            if (z.kind == TbZoneKind.Well) {
                val d = TbWorld.dist(px, py, z.x, z.y)
                if (d < z.r && d > 0.1f) {
                    vx += (z.x - px) / d * WELL_PULL
                    vy += (z.y - py) / d * WELL_PULL
                }
            }
        }
        px += vx * dt
        py += vy * dt
        val v = pushOut(px, py, PLAYER_R)
        px = v.first
        py = v.second
    }

    private var fxGate = 0f

    private fun hitFx(dt: Float): Boolean {
        fxGate += dt
        return (fxGate > 0.04f).also { if (it) fxGate = 0f }
    }

    /** Pushes a circle out of the pillars and back inside the arena wall. */
    internal fun pushOut(
        x0: Float,
        y0: Float,
        r: Float,
    ): Pair<Float, Float> {
        var x = x0
        var y = y0
        for (p in pillars) {
            val dx = x - p.x
            val dy = y - p.y
            val d = hypot(dx, dy)
            val min = p.r + r
            if (d < min) {
                if (d < 1e-4f) {
                    x += min
                } else {
                    x = p.x + dx / d * min
                    y = p.y + dy / d * min
                }
            }
        }
        val d = hypot(x, y)
        val lim = stage.radius - r
        if (d > lim) {
            x *= lim / d
            y *= lim / d
        }
        return x to y
    }

    /** Hurts the player. Dash and the brief grace after a hit make it miss. */
    internal fun hurt(
        dmg: Float,
        fromX: Float = px,
        fromY: Float = py,
        slows: Boolean = false,
    ) {
        if (phase != TbPhase.Combat || invuln > 0f || dashT > 0f) return
        val dealt = dmg * WAVE_EASE[stageIndex.coerceIn(0, WAVE_EASE.lastIndex)] * DIFF_DAMAGE[meta.difficulty.coerceIn(0, 2)]
        if (fromX != px || fromY != py) hitDirs += TbHitDir(atan2(fromY - py, fromX - px), clock)
        emit(TbEvent.Hurt)
        enemies.filter { it.alive }.minByOrNull { TbWorld.dist(it.x, it.y, fromX, fromY) }?.takeIf { TbWorld.dist(it.x, it.y, fromX, fromY) < 4f }?.culprit = CULPRIT_TIME
        hp -= dealt
        invuln = HURT_GRACE
        hurtFlash = 1f
        shake = min(1f, shake + 0.7f)
        if (slows) slowT = 1.6f
        fx += TbFx(TbFxKind.Hurt, fromX, fromY, 0f, 0f, 0xFFE63946, 0.4f)
        val reflect = thorns
        if (reflect > 0f) {
            enemies.filter { it.alive && TbWorld.dist(it.x, it.y, px, py) < THORNS_RANGE }.forEach { hitEnemy(it, dmg * reflect, 0f, 0f, 0f, proc = true) }
        }
        if (hp <= 0f) fall("slain by ${enemies.minByOrNull { TbWorld.dist(it.x, it.y, fromX, fromY) }?.def?.name ?: "Tartarus"}")
    }

    // ---- weapons --------------------------------------------------------------------------------------------------

    /** The angle the shot takes: the yaw, pulled toward the nearest foe inside the weapon's horizontal magnetism. */
    private fun aim(w: TbWeapon): Float {
        if (meta.aim == 0) return yaw
        var best: TbEnemy? = null
        var bestA = Float.MAX_VALUE
        val magnet = Math.toRadians(w.magnetDeg.toDouble()).toFloat() * AIM_BOOST[meta.aim.coerceIn(0, 2)]
        for (e in enemies) {
            if (!e.alive || e.state == TbEState.Spawn) continue
            val d = TbWorld.dist(px, py, e.x, e.y)
            val rel = abs(angDiff(atan2(e.y - py, e.x - px), yaw))
            // Only a foe under the sight circle is pulled on, and the pull stays small, so a shot lands where the circle points.
            val slack = min(magnet + atan2(e.def.radius * 1.1f, d), MAX_PULL)
            if (rel <= slack && rel < bestA && !blocked(e.x, e.y)) {
                bestA = rel
                best = e
            }
        }
        aimTarget = best
        return best?.let { atan2(it.y - py, it.x - px) } ?: yaw
    }

    private fun blocked(
        tx: Float,
        ty: Float,
    ): Boolean = pillars.any { p -> TbWorld.segDist(p.x, p.y, px, py, tx, ty) < p.r * 0.85f && TbWorld.dist(px, py, p.x, p.y) < TbWorld.dist(px, py, tx, ty) }

    fun tryFire(): Boolean {
        if (phase != TbPhase.Combat || fireCd > 0f) return false
        val w = weapon
        if (favor < w.favor) {
            fireCd = 0.3f
            say("Not enough Favor", good = false)
            return false
        }
        val a = aim(w)
        val fired =
            when (w.shot) {
                TbShot.BEAM -> fireBeam(w, a)
                TbShot.WAVE -> fireWave(w, a)
                TbShot.BOLT -> fireBolts(w, a)
                TbShot.CHAIN -> fireChain(w, a)
            }
        if (!fired) {
            fireCd = 0.25f
            return false
        }
        favor -= w.favor
        emit(TbEvent.Fire)
        fireCd = w.fireMs / 1000f * fireMult
        kick = 1f
        return true
    }

    private fun fireBeam(
        w: TbWeapon,
        aim: Float,
    ): Boolean {
        val a = aim + Math.toRadians(((rnd.nextFloat() - 0.5f) * w.spreadDeg).toDouble()).toFloat()
        val rx = cos(a)
        val ry = sin(a)
        var bestT = BEAM_RANGE
        var hit: TbEnemy? = null
        for (e in enemies) {
            if (!e.alive) continue
            val t = (e.x - px) * rx + (e.y - py) * ry
            if (t <= 0f || t >= bestT) continue
            val perp = abs((e.x - px) * ry - (e.y - py) * rx)
            if (perp <= e.def.radius * HIT_SCALE) {
                bestT = t
                hit = e
            }
        }
        for (p in pillars) {
            val t = (p.x - px) * rx + (p.y - py) * ry
            if (t <= 0f || t >= bestT) continue
            if (abs((p.x - px) * ry - (p.y - py) * rx) <= p.r) {
                bestT = t
                hit = null
            }
        }
        fx += TbFx(TbFxKind.Beam, px, py, px + rx * bestT, py + ry * bestT, w.color, 0.3f)
        if (hit != null) {
            combo = min(COMBO_MAX, combo + 1)
            val bonus = 1f + 0.03f * combo
            hitEnemy(hit, w.damage * bonus, rx, ry, w.knockback)
        } else {
            combo = 0
        }
        return true
    }

    private fun fireWave(
        w: TbWeapon,
        aim: Float,
    ): Boolean {
        val half = Math.toRadians((w.spreadDeg / 2f).toDouble()).toFloat()
        fx += TbFx(TbFxKind.Wave, px, py, cos(aim), sin(aim), w.color, 0.5f)
        for (e in enemies.toList()) {
            if (!e.alive) continue
            val d = TbWorld.dist(px, py, e.x, e.y)
            if (d > WAVE_RANGE + e.def.radius) continue
            val ang = abs(angDiff(atan2(e.y - py, e.x - px), aim))
            val reach = half + atan2(e.def.radius, max(d, 0.5f))
            if (ang > reach) continue
            val rays = if (ang < half * 0.55f) w.count else max(2, w.count / 2)
            val fall = 1f - 0.45f * (d / WAVE_RANGE).coerceIn(0f, 1f)
            val dx = (e.x - px) / max(d, 0.1f)
            val dy = (e.y - py) / max(d, 0.1f)
            hitEnemy(e, w.damage * rays * fall, dx, dy, w.knockback)
        }
        return true
    }

    private fun fireBolts(
        w: TbWeapon,
        aim: Float,
    ): Boolean {
        val spread = Math.toRadians(w.spreadDeg.toDouble()).toFloat()
        for (i in 0 until w.count) {
            val a = aim + (i - (w.count - 1) / 2f) * spread
            projs += TbProj(px + cos(a) * 0.8f, py + sin(a) * 0.8f, cos(a), sin(a), w.speed, w.damage.toFloat(), 0.45f, true, w.color, w.pierce, 1.5f)
        }
        return true
    }

    private fun fireChain(
        w: TbWeapon,
        aim: Float,
    ): Boolean {
        var first = aimTarget
        if (first == null) {
            first =
                enemies
                    .filter { it.alive && abs(angDiff(atan2(it.y - py, it.x - px), yaw)) < 1.1f && TbWorld.dist(it.x, it.y, px, py) < 32f && !blocked(it.x, it.y) }
                    .minByOrNull { TbWorld.dist(it.x, it.y, px, py) }
        }
        if (first == null) {
            say("Nothing to strike", good = false)
            return false
        }
        val struck = HashSet<Int>()
        var cur: TbEnemy = first
        var fromX = px
        var fromY = py
        for (i in 0 until w.chainTargets) {
            struck += cur.uid
            fx += TbFx(TbFxKind.Chain, fromX, fromY, cur.x, cur.y, w.color, 0.35f, n = i)
            val dir = TbWorld.dist(fromX, fromY, cur.x, cur.y).coerceAtLeast(0.1f)
            hitEnemy(cur, w.damage.toFloat(), (cur.x - fromX) / dir, (cur.y - fromY) / dir, w.knockback)
            if (cur.alive && !cur.boss) {
                cur.state = TbEState.Stun
                cur.timer = AEGIS_STUN
            }
            fromX = cur.x
            fromY = cur.y
            val next = enemies.filter { it.alive && it.uid !in struck && TbWorld.dist(it.x, it.y, cur.x, cur.y) <= w.chainRadius }.minByOrNull { TbWorld.dist(it.x, it.y, cur.x, cur.y) } ?: break
            cur = next
        }
        shake = max(shake, 0.6f)
        return true
    }

    /** Damages a foe. [proc] hits (burn, thorns, chain arcs) do not trigger more procs. Returns true if it died. */
    internal fun hitEnemy(
        e: TbEnemy,
        base: Float,
        kx: Float,
        ky: Float,
        kb: Float,
        proc: Boolean = false,
    ): Boolean {
        if (!e.alive) return false
        var dmg = base * (if (proc) 1f else dmgMult)
        val crit = !proc && critChance > 0f && rnd.nextFloat() < critChance
        if (crit) dmg *= CRIT_MULT
        if (e.marked > 0f) dmg *= 1.15f
        e.hp -= dmg
        e.flash = 0.13f
        if (kb > 0f) {
            e.vx += kx * kb * knockMult * e.def.shove * 1.4f
            e.vy += ky * kb * knockMult * e.def.shove * 1.4f
        }
        if (!proc) {
            hitMarker = 0.18f
            emit(TbEvent.Hit)
            val bd = burnDps
            if (bd > 0f) {
                e.burn = 3f
                e.burnDps = bd
            }
            if (weapon.shot == TbShot.BOLT) e.marked = 3f
            hitCount++
            val chain = add("proc_chain_damage")
            if (chain > 0f && hitCount % 4 == 0) procChain(e, chain)
        }
        if (fx.size < MAX_FX) fx += TbFx(TbFxKind.Text, e.x, e.y, 0f, 0f, if (crit) 0xFFFFD447 else 0xFFFFFFFF, 0.6f, dmg.roundToInt().toString())
        if (e.hp <= 0f) {
            kill(e)
            return true
        }
        return false
    }

    private fun procChain(
        from: TbEnemy,
        dmg: Float,
    ) {
        var cur = from
        val struck = hashSetOf(from.uid)
        repeat(3) {
            val next = enemies.filter { it.alive && it.uid !in struck && TbWorld.dist(it.x, it.y, cur.x, cur.y) < 10f }.minByOrNull { TbWorld.dist(it.x, it.y, cur.x, cur.y) } ?: return
            fx += TbFx(TbFxKind.Chain, cur.x, cur.y, next.x, next.y, 0xFFFFF275, 0.3f)
            struck += next.uid
            hitEnemy(next, dmg, 0f, 0f, 0f, proc = true)
            cur = next
        }
    }

    internal fun kill(e: TbEnemy) {
        if (!e.alive) return
        hitStop = max(hitStop, if (e.boss) HIT_STOP_BOSS else HIT_STOP)
        emit(TbEvent.Kill)
        e.state = TbEState.Dying
        e.fall = 0f
        if (e.pays) {
            streak++
            streakT = STREAK_TIME
            val gain = (e.def.obols * (1f + 0.1f * meta.level(TbUpgrade.Fortune)) * (1f + 0.05f * min(streak, 10))).roundToInt()
            obols += gain
            favor = min(MAX_FAVOR, favor + e.def.favor)
            kills++
            fx += TbFx(TbFxKind.Coin, e.x, e.y, 0f, 0f, 0xFFFFD447, 0.8f, n = gain)
            if (streak in STREAK_CALLS) say("x$streak streak")
            val heal = add("heal_per_10_kills")
            if (heal > 0f && kills % 10 == 0) hp = min(maxHp, hp + heal)
        }
        fx += TbFx(TbFxKind.Burst, e.x, e.y, 0f, 0f, 0xFFB7F3FF, 0.55f, n = if (e.boss) 3 else 1)
        if (e.boss) {
            shake = 1f
            enemies.filter { it.alive && it !== e }.forEach { banish(it) }
        }
    }

    /** Removes a foe without reward: the wave ended, or the boss fell. */
    private fun banish(e: TbEnemy) {
        e.pays = false
        e.state = TbEState.Dying
        e.fall = 0f
    }

    // ---- shots, warnings and hazards ------------------------------------------------------------------------------

    private fun stepProjs(dt: Float) {
        val it = projs.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.x += p.dx * p.speed * dt
            p.y += p.dy * p.speed * dt
            p.age += dt
            p.life -= dt
            var dead = p.life <= 0f || hypot(p.x, p.y) > stage.radius + 1f || pillars.any { TbWorld.dist(p.x, p.y, it.x, it.y) < it.r }
            if (!dead && p.friendly) {
                for (e in enemies) {
                    if (!e.alive || e.uid in p.hitIds || e.state == TbEState.Spawn) continue
                    if (TbWorld.dist(p.x, p.y, e.x, e.y) < e.def.radius * HIT_SCALE + p.radius) {
                        p.hitIds += e.uid
                        hitEnemy(e, p.dmg, p.dx, p.dy, weapon.knockback)
                        p.pierce--
                        if (p.pierce <= 0) {
                            dead = true
                            break
                        }
                    }
                }
            } else if (!dead && TbWorld.dist(p.x, p.y, px, py) < PLAYER_R + p.radius) {
                hurt(p.dmg, p.x, p.y)
                dead = true
            }
            if (dead) it.remove()
        }
    }

    private fun stepTeles(dt: Float) {
        val it = teles.iterator()
        while (it.hasNext()) {
            val t = it.next()
            t.t -= dt
            if (t.t > 0f) continue
            it.remove()
            val inside =
                if (t.line) {
                    TbWorld.segDist(px, py, t.x, t.y, t.x2, t.y2) < t.r + PLAYER_R
                } else {
                    TbWorld.dist(px, py, t.x, t.y) < t.r + PLAYER_R * 0.5f
                }
            if (t.dmg > 0 && inside) hurt(t.dmg.toFloat(), t.x, t.y, slows = t.slows)
            if (!t.line && t.dmg > 0) {
                fx += TbFx(TbFxKind.Burst, t.x, t.y, 0f, 0f, 0xFFFF9A3C, 0.5f, n = 2)
                shake = max(shake, 0.4f)
            }
            t.leaper?.let { b ->
                if (b.alive) {
                    val v = pushOut(t.x, t.y, b.def.radius)
                    b.x = v.first
                    b.y = v.second
                }
            }
        }
    }

    private fun stepZones(dt: Float) {
        lavaTick -= dt
        val ticking = lavaTick <= 0f
        if (ticking) lavaTick = 0.5f
        val it = zones.iterator()
        while (it.hasNext()) {
            val z = it.next()
            z.life -= dt
            if (z.life <= 0f) {
                it.remove()
                continue
            }
            val d = TbWorld.dist(px, py, z.x, z.y)
            when (z.kind) {
                TbZoneKind.LavaRing -> {
                    if (ticking && hypot(px, py) > z.r) hurt(LAVA_TICK)
                    scorchFoes(dt) { hypot(it.x, it.y) > z.r }
                }

                TbZoneKind.LavaStrip -> {
                    if (ticking && abs(px - z.x) < z.r) hurt(LAVA_TICK)
                    scorchFoes(dt) { abs(it.x - z.x) < z.r }
                }

                TbZoneKind.Vent -> cycle(z, dt, rest = 3.2f, warn = 1.0f, fire = 0.35f) { if (d < z.r) hurt(VENT_DMG, z.x, z.y) }
                TbZoneKind.Spikes -> cycle(z, dt, rest = 2.2f, warn = 0.8f, fire = 0.6f) { if (d < z.r) hurt(SPIKE_DMG, z.x, z.y) }
                TbZoneKind.Well -> pullFoes(z, dt)
                else -> Unit
            }
        }
    }

    /** A foe shoved fast into lava burns for a big hit, so the Trident's knockback pays off. */
    private fun scorchFoes(
        dt: Float,
        inLava: (TbEnemy) -> Boolean,
    ) {
        for (e in enemies) {
            e.lavaT -= dt
            if (!e.alive || e.boss || e.lavaT > 0f || hypot(e.vx, e.vy) < SHOVE_SPEED || !inLava(e)) continue
            e.lavaT = 0.6f
            hitEnemy(e, SHOVE_LAVA, 0f, 0f, 0f, proc = true)
            fx += TbFx(TbFxKind.Burst, e.x, e.y, 0f, 0f, 0xFFFF7B00, 0.4f)
        }
    }

    private fun pullFoes(
        z: TbZone,
        dt: Float,
    ) {
        for (e in enemies) {
            if (!e.alive || e.def.flies || e.boss) continue
            val d = TbWorld.dist(e.x, e.y, z.x, z.y)
            if (d < z.r && d > 0.1f) {
                e.x += (z.x - e.x) / d * WELL_PULL * 0.5f * dt
                e.y += (z.y - e.y) / d * WELL_PULL * 0.5f * dt
            }
        }
    }

    /** Vents and spike plates: rest, warn, then fire once. */
    private fun cycle(
        z: TbZone,
        dt: Float,
        rest: Float,
        warn: Float,
        fire: Float,
        onFire: () -> Unit,
    ) {
        z.phase += dt
        val total = rest + warn + fire
        if (z.phase >= total) {
            z.phase -= total
            z.done = false
        }
        val s = if (z.phase < rest) 0 else if (z.phase < rest + warn) 1 else 2
        if (s == 2 && !z.done) {
            z.done = true
            onFire()
            fx += TbFx(TbFxKind.Burst, z.x, z.y, 0f, 0f, 0xFFFF7B00, 0.4f)
        }
        z.stage = s
    }

    private fun stepPillars(dt: Float) {
        for (p in pillars) {
            if (!p.moving) continue
            val a = p.angle + clock * 0.18f
            p.x = cos(a) * p.orbit
            p.y = sin(a) * p.orbit
            val d = TbWorld.dist(px, py, p.x, p.y)
            if (d < p.r + PLAYER_R) {
                val v = pushOut(px, py, PLAYER_R)
                px = v.first
                py = v.second
            }
        }
    }

    private fun stepFx(dt: Float) {
        val it = fx.iterator()
        while (it.hasNext()) {
            val f = it.next()
            f.t += dt
            if (f.t >= f.life) it.remove()
        }
    }

    // ---- spawning -------------------------------------------------------------------------------------------------

    private fun spawnFoes(dt: Float) {
        val s = stage
        if (s.boss) {
            minionT -= dt
            val minions = enemies.count { it.alive && !it.boss }
            if (minionT <= 0f && s.minionId.isNotEmpty() && minions < s.minionMax && boss != null) {
                spawn(s.minionId)
                minionT = s.minionMs / 1000f
            }
            return
        }
        spawnT -= dt
        if (spawnT > 0f || aliveCount >= min(s.maxConcurrent, concurrentCap) || s.pool.isEmpty()) return
        var roll = rnd.nextInt(s.pool.sumOf { it.weight })
        // The very first stage is melee only: nothing shoots at you from a distance yet.
        val pick = if (early && stageIndex == 0) s.pool.first() else s.pool.first { p -> (roll < p.weight).also { if (!it) roll -= p.weight } }
        spawn(pick.id)
        spawnT = s.spawnMs / 1000f * SPAWN_PACE * earlyPace * DIFF_PACE[meta.difficulty.coerceIn(0, 2)]
    }

    /** The first gate eases you in: foes are slower, arrive further apart and fewer at once in its first stages. */
    private val early get() = gate.number == 1 && stageIndex < EARLY_STAGES

    /** In the first stages foes come straight at you instead of circling or strafing. */
    internal val straightOnly get() = early && stageIndex < STRAIGHT_STAGES

    internal val foeSpeedScale get() = (if (early) EARLY_SPEED[stageIndex] else 1f) * DIFF_SPEED[meta.difficulty.coerceIn(0, 2)]

    /** Seconds a foe waits after appearing before it may attack. */
    /** Seconds a melee foe rests between swings. */
    internal val meleeRest get() = if (early) EARLY_MELEE_REST[stageIndex] else MELEE_REST

    internal val attackGrace get() = if (early) EARLY_GRACE[stageIndex] else ATTACK_GRACE
    private val earlyPace get() = if (early) EARLY_PACE[stageIndex] else 1f
    private val concurrentCap get() = if (early) EARLY_CAP[stageIndex] else Int.MAX_VALUE

    internal fun spawn(
        id: String,
        ahead: Boolean = false,
    ) {
        val def = TartarusData.enemies.first { it.id == id }
        var x = 0f
        var y = 0f
        if (ahead) {
            x = px + cos(yaw) * AHEAD_DIST
            y = py + sin(yaw) * AHEAD_DIST
        }
        for (i in 0 until if (ahead) 0 else 10) {
            // Early on, foes arrive in the arc you are facing; later from anywhere.
            val a = if (early) yaw + (rnd.nextFloat() - 0.5f) * EARLY_ARC[stageIndex.coerceIn(0, EARLY_ARC.lastIndex)] else rnd.nextFloat() * 2f * PI.toFloat()
            val lim = stage.radius - 1.6f
            x = if (early) px + cos(a) * AHEAD_DIST else cos(a) * min(lim, SPAWN_RING)
            y = if (early) py + sin(a) * AHEAD_DIST else sin(a) * min(lim, SPAWN_RING)
            val len = hypot(x, y)
            if (len > lim) {
                x *= lim / len
                y *= lim / len
            }
            if (TbWorld.dist(x, y, px, py) > SPAWN_CLEAR) break
        }
        val e = TbEnemy(def, x, y, uid++)
        e.orbit = atan2(y - py, x - px)
        e.orbitDir = if (rnd.nextBoolean()) 1f else -1f
        e.cooldown = 1f + rnd.nextFloat() * 1.5f
        enemies += e
        fx += TbFx(TbFxKind.Puff, x, y, 0f, 0f, 0xFF8A7A9A, 0.5f)
    }

    /** Test and debug hook: how much of the wave timer is left. */
    internal fun skipTime(seconds: Float) {
        stageT = max(0f, stageT - seconds)
    }

    companion object {
        /** Shown on the Gate screen; bump it with every balance change. */
        const val BALANCE = "B4"

        const val BASE_HP = 100f
        const val MAX_FAVOR = 100f
        const val FAVOR_TRICKLE = 2f
        const val BASE_SPEED = 6.2f
        const val SLOWED = 0.6f
        const val PLAYER_R = 0.55f
        const val TURN_SPEED = 2.4f
        const val QUICK_TURN = 0.14f
        const val DASH_TIME = 0.18f
        const val DASH_SPEED = 21f
        const val DASH_CD = 1.4f
        const val HURT_GRACE = 0.55f

        /** How long the foe that hurt you stays marked in red. */
        const val CULPRIT_TIME = 1.8f
        const val READY_TIME = 2.2f
        const val TOAST_TIME = 2.4f
        const val DEATH_TIME = 0.8f
        const val WAVE_HEAL = 0.25f
        const val BEAM_RANGE = 46f
        const val WAVE_RANGE = 10f
        const val COMBO_MAX = 10
        const val CRIT_MULT = 1.8f
        const val THORNS_RANGE = 4.5f
        const val STREAK_TIME = 3f
        const val SPAWN_CLEAR = 4f

        /** Foes appear this far from the arena's centre, close enough to read on a small screen. */
        const val SPAWN_RING = 9f

        /** Seconds before the first foe of a stage appears, and how much slower than the data they keep arriving. */
        const val FIRST_SPAWN = 3.5f
        const val SPAWN_PACE = 1.7f

        /** Stages last this much longer than the data says, so there is time to settle in. */
        const val DURATION_SCALE = 1.6f

        /** The first foe of a stage starts this far in front of you. */
        const val AHEAD_DIST = 8f

        /** The second foe of an early stage comes this many seconds in, and no foe attacks for a while after it appears. */
        const val EARLY_NEXT_SPAWN = 8f
        const val ATTACK_GRACE = 1.2f
        val EARLY_GRACE = floatArrayOf(3f, 2.5f, 2f, 1.5f)
        const val MELEE_REST = 1f
        val EARLY_MELEE_REST = floatArrayOf(2.5f, 2f, 1.6f, 1.3f)

        /** Gate 1, stages 1 to 4: foe speed, spacing between arrivals, and most foes alive at once. */
        const val EARLY_STAGES = 4
        const val STRAIGHT_STAGES = 2
        val EARLY_SPEED = floatArrayOf(0.4f, 0.55f, 0.7f, 0.85f)
        val EARLY_PACE = floatArrayOf(2.2f, 1.8f, 1.4f, 1.2f)
        /** How wide the arc in front of you is, in radians, that early foes arrive in: your starting view, then wider. */
        val EARLY_ARC = floatArrayOf(0.25f, 0.5f, 0.9f, 1.4f)
        val EARLY_CAP = intArrayOf(2, 3, 5, 7)

        /** Foe damage by wave, so the first waves let you settle in. The Guardian hits for full. */
        val WAVE_EASE = floatArrayOf(0.3f, 0.4f, 0.55f, 0.75f, 1f)

        /** Shots hit a foe this much wider than its body circle, to match the larger sprites, and aim pulls this much harder. */
        const val MAX_PULL = 0.16f
        const val HIT_SCALE = 1.7f
        val AIM_BOOST = floatArrayOf(0f, 0.5f, 0.9f)

        /** Foe damage and spawn gaps for Easy, Normal and Hard. */
        val DIFF_DAMAGE = floatArrayOf(0.7f, 1f, 1.3f)
        val DIFF_PACE = floatArrayOf(1.3f, 1f, 0.8f)

        /** How fast foes move on Easy, Normal and Hard. */
        val DIFF_SPEED = floatArrayOf(0.8f, 1f, 1.1f)
        const val HIT_ARROW_TIME = 1.3f
        const val HIT_STOP = 0.045f
        const val HIT_STOP_BOSS = 0.09f
        const val AEGIS_STUN = 0.7f
        const val CURSE_CHANCE = 0.3f
        const val CURSE_HP = 0.9f
        const val SHOVE_SPEED = 3.5f
        const val SHOVE_LAVA = 40f

        /** Pairs of boons that give a bonus together, with the line shown when the second one is picked. */
        val SYNERGIES =
            listOf(
                Triple("zeus_chain_arc", "poseidon_undertow", "Storm Surge: Zeus + Poseidon, chains hit 50% harder"),
                Triple("apollo_radiance", "hephaestus_burn", "Sun Forge: Apollo + Hephaestus, burns 50% hotter"),
                Triple("athena_aegis", "hades_soul_siphon", "Stygian Guard: Athena + Hades, +20% reflect"),
                Triple("ares_bloodlust", "artemis_crit_mark", "Hunt: Ares + Artemis, 10% faster fire"),
            )
        const val LAVA_TICK = 5f
        const val VENT_DMG = 16f
        const val SPIKE_DMG = 12f
        const val WELL_PULL = 3.6f
        const val MAX_FX = 90
        val STREAK_CALLS = setOf(5, 10, 15, 20, 30, 40)
        val SLOWING = setOf(TbZoneKind.Fog, TbZoneKind.Time, TbZoneKind.Rift)
    }
}
