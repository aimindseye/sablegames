package org.sableos.games

import org.jbox2d.callbacks.ContactImpulse
import org.jbox2d.callbacks.ContactListener
import org.jbox2d.collision.Manifold
import org.jbox2d.collision.WorldManifold
import org.jbox2d.collision.shapes.CircleShape
import org.jbox2d.collision.shapes.EdgeShape
import org.jbox2d.collision.shapes.PolygonShape
import org.jbox2d.common.Vec2
import org.jbox2d.dynamics.Body
import org.jbox2d.dynamics.BodyDef
import org.jbox2d.dynamics.BodyType
import org.jbox2d.dynamics.FixtureDef
import org.jbox2d.dynamics.World
import org.jbox2d.dynamics.contacts.Contact
import java.util.IdentityHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal enum class PieceKind { Block, Slop, Battery, Melon, Seed, Banner, Shield }

/** One body in the world, with what it is and the hit points it has left. */
internal class MelonPiece(
    val kind: PieceKind,
    val body: Body,
    val w: Float,
    val h: Float,
    val maxHp: Float,
    val material: MelonMaterial? = null,
    val slop: SlopKind? = null,
    val fixed: Boolean = false,
    /** A melon's kind; also set on the three pieces a Split Cantaloupe breaks into. */
    val melonKind: MelonKind? = null,
) {
    var hp = maxHp
    var dead = false

    /** A battery's blast. */
    var blastRadius = 0f
    var blastDamage = 0f

    /** A beam still hanging on its cables, the hit points its cables have left, and how much heavier it falls. */
    var hanging = false
    var cableHp = 0f
    var dropMass = 1f

    /** A drone platform: held up by thrusters rather than cables. */
    var drone = false

    /** A firewall shield's gap, in degrees. */
    var gapDegrees = 0f

    /** Seconds since a melon or seed was fired, used to clear seeds away. */
    var age = 0f

    val x: Float get() = body.position.x
    val y: Float get() = body.position.y
    val angle: Float get() = body.angle
    val speed: Float get() = body.linearVelocity.length()
    val cracked: Boolean get() = !fixed && hp < maxHp * 0.5f
}

/** Points that float up from where something broke. */
internal class MelonPopup(
    val x: Float,
    val y: Float,
    val text: String,
) {
    var age = 0f
}

/** A battery going off: drawn as a ring that grows to [radius]. */
internal class MelonBlast(
    val x: Float,
    val y: Float,
    val radius: Float,
    val damage: Float,
) {
    var age = 0f
}

internal enum class MelonPhase { Aiming, Flying, Cleared, Failed }

/**
 * One Bitter Melons level in play: a JBox2D world with the structure, the AI Slop and the melon queue.
 * Hits do damage in proportion to the impulse of contacts that were closing faster than
 * [HIT_SPEED], so resting stacks never wear themselves out. Everything is deterministic: the same
 * shots give the same result, which the tests rely on.
 */
internal class MelonsWorld(
    val level: MelonLevel,
) {
    private val world = World(Vec2(0f, -GRAVITY))
    val pieces = mutableListOf<MelonPiece>()
    val popups = mutableListOf<MelonPopup>()
    val blasts = mutableListOf<MelonBlast>()

    /** Aim in degrees above the horizon, and power in percent of the level's top speed. */
    var angle = 30f
        private set
    var power = 70f
        private set

    /** Melons not fired yet, the one on the slingshot included. */
    var melonsLeft = level.melons
        private set
    var phase = MelonPhase.Aiming
        private set
    var score = 0
        private set
    var shots = 0
        private set

    /** The melon in the air, or the last one fired until the next is loaded. */
    var melon: MelonPiece? = null
        private set
    var burstUsed = false
        private set

    /** Seconds the Debris Combo has left after a Premium Ad-Slop breaks: block points count double. */
    var comboTime = 0f
        private set
    var time = 0f
        private set

    private var armed = false
    private var accumulator = 0f
    private var stillTime = 0f
    private var flightTime = 0f
    private var clearTime = -1f
    private val damage = IdentityHashMap<MelonPiece, Float>()
    private val closing = IdentityHashMap<Contact, Float>()
    private val pendingBlasts = mutableListOf<Pair<MelonBlast, Float>>()
    private val worldManifold = WorldManifold()
    private var popping: MelonPiece? = null

    init {
        world.setContactListener(Hits())
        ground()
        level.blocks.forEach(::addBlock)
        level.banners.forEach(::addBanner)
        level.hanging.forEach(::addHanging)
        level.drones.forEach(::addDrone)
        placeLoose()
        // Let everything settle on the ground before anything can break.
        repeat(SETTLE_STEPS) { world.step(STEP, VELOCITY_ITERATIONS, POSITION_ITERATIONS) }
        // The shields go in last, so they draw over the structure; they only meet melons, so settling can't move them.
        level.shields.forEach(::addShield)
        damage.clear()
        armed = true
    }

    val slopLeft: Int get() = pieces.count { it.kind == PieceKind.Slop && !it.dead }

    /** Seeds won so far: one for each threshold reached, and at least one for a clear. */
    val seeds: Int
        get() {
            val reached = level.seeds.count { score >= it }
            return if (phase == MelonPhase.Cleared) max(1, reached) else reached
        }

    val launchSpeed: Float get() = level.maxSpeed * power / 100f

    fun aim(
        dAngle: Float,
        dPower: Float,
    ) {
        angle = (angle + dAngle).coerceIn(MIN_ANGLE, MAX_ANGLE)
        power = (power + dPower).coerceIn(MIN_POWER, 100f)
    }

    fun setAim(
        newAngle: Float,
        newPower: Float,
    ) {
        angle = newAngle.coerceIn(MIN_ANGLE, MAX_ANGLE)
        power = newPower.coerceIn(MIN_POWER, 100f)
    }

    /** The melon on the slingshot, or next to load. */
    val loaded: MelonKind? get() = level.queue.getOrNull(shots)

    /** The melons still to load after the one on the slingshot, in order. */
    val waiting: List<MelonKind> get() = level.queue.drop(shots + 1)

    /** Where the melon would be [t] seconds after launch, ignoring what it hits. */
    fun preview(t: Float): Pair<Float, Float> {
        if (level.fans.isNotEmpty()) return fanPath(t)
        val rad = angle * PI.toFloat() / 180f
        val v = launchSpeed
        return (SLING_X + v * cos(rad) * t) to (SLING_Y + v * sin(rad) * t - GRAVITY * t * t / 2)
    }

    private var pathAim = Float.NaN to Float.NaN
    private var path = FloatArray(0)

    /**
     * The flight through the fans' updrafts, stepped as the physics steps it and kept until the aim changes. [t]
     * past the end holds at the end.
     */
    private fun fanPath(t: Float): Pair<Float, Float> {
        if (pathAim != angle to power) {
            pathAim = angle to power
            val steps = (LANDING_MAX_TIME / STEP).toInt() + 1
            path = FloatArray(steps * 2)
            val rad = angle * PI.toFloat() / 180f
            var x = SLING_X
            var y = SLING_Y
            var vx = launchSpeed * cos(rad)
            var vy = launchSpeed * sin(rad)
            for (i in 0 until steps) {
                path[i * 2] = x
                path[i * 2 + 1] = y
                vy += (lift(x, y) - GRAVITY) * STEP
                x += vx * STEP
                y += vy * STEP
            }
        }
        val i = (t / STEP).toInt().coerceIn(0, path.size / 2 - 1)
        return path[i * 2] to path[i * 2 + 1]
    }

    /** The upward pull of any fan's updraft at [x], [y], in m/s². */
    private fun lift(
        x: Float,
        y: Float,
    ): Float =
        level.fans.sumOf { fan ->
            val inside = abs(x - fan.x) <= fan.w / 2 && y >= fan.y && y <= fan.y + fan.high
            if (inside) fan.lift.toDouble() else 0.0
        }.toFloat()

    /**
     * Where and when the aimed shot first meets a standing block, enemy or battery, or the ground: its flight
     * time and the melon's centre then. The flight is the plain arc of [preview]; pieces are taken as upright
     * boxes, which they are until something hits them.
     */
    fun landing(): Triple<Float, Float, Float> {
        val targets = pieces.filter { !it.dead && it.kind != PieceKind.Melon && it.kind != PieceKind.Seed }
        var t = 0f
        while (t < LANDING_MAX_TIME) {
            t += STEP
            val (x, y) = preview(t)
            val hit =
                y <= MELON_R || x >= STAGE_W ||
                    targets.any { if (it.kind == PieceKind.Shield) onRing(it, x, y) else touches(it, x, y) }
            if (hit) return Triple(t, x, y.coerceAtLeast(MELON_R))
        }
        val (x, y) = preview(t)
        return Triple(t, x, y)
    }

    /** Whether a melon centred at [x], [y] touches [piece], taken as a box turned by its angle. */
    private fun touches(
        piece: MelonPiece,
        x: Float,
        y: Float,
    ): Boolean {
        val c = cos(-piece.angle)
        val s = sin(-piece.angle)
        val dx = x - piece.x
        val dy = y - piece.y
        val lx = dx * c - dy * s
        val ly = dx * s + dy * c
        return abs(lx) <= piece.w / 2 + MELON_R && abs(ly) <= piece.h / 2 + MELON_R
    }

    /** Whether a melon centred at [x], [y] touches a shield's ring, outside its gap as the ring is turned now. */
    private fun onRing(
        shield: MelonPiece,
        x: Float,
        y: Float,
    ): Boolean {
        val r = shield.w / 2
        val dx = x - shield.x
        val dy = y - shield.y
        if (abs(hypot(dx, dy) - r) > MELON_R + RING_T) return false
        if (shield.gapDegrees <= 0f) return true
        val at = (atan2(dy, dx) - shield.angle) * 180f / PI.toFloat()
        val fromGap = abs(((at - GAP_AT) % 360f + 540f) % 360f - 180f)
        return fromGap > shield.gapDegrees / 2
    }

    /** Fires the melon on the slingshot. False if there is none, or one is still in the air. */
    fun launch(): Boolean {
        if (phase != MelonPhase.Aiming || melonsLeft == 0) return false
        val kind = loaded ?: return false
        melon?.let(::remove)
        val rad = angle * PI.toFloat() / 180f
        val piece = circle(PieceKind.Melon, SLING_X, SLING_Y, kind.r, kind.density, 0f, melonKind = kind)
        piece.body.linearVelocity = Vec2(launchSpeed * cos(rad), launchSpeed * sin(rad))
        melon = piece
        melonsLeft--
        shots++
        burstUsed = false
        flightTime = 0f
        stillTime = 0f
        phase = MelonPhase.Flying
        return true
    }

    /**
     * Space in the air, once a shot: the Bitter Melon's Seed Burst (three fast seeds in a ±12° spread) or the Split
     * Cantaloupe's split (three smaller melons in a ±10° spread, at its speed).
     */
    fun ability(): Boolean {
        val m = melon ?: return false
        if (phase != MelonPhase.Flying || burstUsed || m.dead || m.kind != PieceKind.Melon ||
            flightTime < MIN_BURST_TIME
        ) {
            return false
        }
        burstUsed = true
        if (m.melonKind == MelonKind.Split) {
            split(m)
            return true
        }
        if (m.melonKind == MelonKind.Overripe) {
            pop(m)
            return true
        }
        if (m.melonKind == MelonKind.Heavy) {
            // The slam: straight down, hard.
            m.body.linearVelocity = Vec2(m.body.linearVelocity.x * SLAM_KEEP, -SLAM_SPEED)
            return true
        }
        val v = m.body.linearVelocity
        val heading = atan2(v.y, v.x)
        val speed = max(v.length(), BURST_MIN_SPEED) * BURST_BOOST
        for (spread in listOf(-BURST_SPREAD, 0f, BURST_SPREAD)) {
            val a = heading + spread * PI.toFloat() / 180f
            val seed =
                circle(
                    PieceKind.Seed,
                    m.x + cos(a) * (MELON_R + SEED_R),
                    m.y + sin(a) * (MELON_R + SEED_R),
                    SEED_R,
                    SEED_DENSITY,
                    0f,
                )
            seed.body.linearVelocity = Vec2(speed * cos(a), speed * sin(a))
        }
        return true
    }

    private fun split(m: MelonPiece) {
        val v = m.body.linearVelocity
        val heading = atan2(v.y, v.x)
        val speed = v.length()
        val spin = m.body.angularVelocity
        val (x, y) = m.x to m.y
        remove(m)
        val parts =
            listOf(-SPLIT_SPREAD, 0f, SPLIT_SPREAD).map { spread ->
                val a = heading + spread * PI.toFloat() / 180f
                // Side by side across the heading, so the three don't start inside each other.
                val side = spread / SPLIT_SPREAD * SPLIT_R * 2.2f
                val part =
                    circle(
                        PieceKind.Seed,
                        x - sin(heading) * side,
                        y + cos(heading) * side,
                        SPLIT_R,
                        MelonKind.Split.density,
                        0f,
                        melonKind = MelonKind.Split,
                    )
                part.body.linearVelocity = Vec2(speed * cos(a), speed * sin(a))
                part.body.angularVelocity = spin
                part
            }
        // The camera follows the middle one.
        melon = parts[1]
    }

    /** Runs the world on by [dt] seconds, in fixed steps. */
    fun step(dt: Float) {
        accumulator = min(accumulator + dt, MAX_CATCH_UP)
        while (accumulator >= STEP) {
            tick()
            accumulator -= STEP
        }
    }

    /** One fixed step. */
    fun tick() {
        if (phase == MelonPhase.Cleared || phase == MelonPhase.Failed) {
            age(STEP)
            return
        }
        time += STEP
        blow()
        world.step(STEP, VELOCITY_ITERATIONS, POSITION_ITERATIONS)
        closing.clear()
        applyDamage()
        // An Overripe Melon that hit something hard bursts.
        popping?.let { if (it === melon && !it.dead && !burstUsed && phase == MelonPhase.Flying) pop(it) }
        popping = null
        fireBlasts()
        dropLost()
        age(STEP)
        comboTime = max(0f, comboTime - STEP)
        if (phase == MelonPhase.Flying) follow()
        checkEnd()
    }

    private fun follow() {
        flightTime += STEP
        val m = melon
        val moving = pieces.any { !it.dead && !it.fixed && it.kind != PieceKind.Seed && it.speed > STILL_SPEED }
        stillTime = if (moving) 0f else stillTime + STEP
        val melonGone = m == null || m.dead
        // The shot is over when everything has come to rest, or after a long wait.
        if (stillTime >= STILL_TIME || flightTime >= MAX_FLIGHT || (melonGone && stillTime >= STILL_TIME / 2)) {
            if (slopLeft > 0) phase = if (melonsLeft > 0) MelonPhase.Aiming else MelonPhase.Failed
        }
    }

    private fun checkEnd() {
        if (slopLeft > 0) return
        if (clearTime < 0f) clearTime = time
        // A moment for the last debris to land and score.
        if (time - clearTime >= CLEAR_DELAY) {
            val bonus = melonsLeft * UNUSED_MELON_BONUS
            if (bonus > 0) {
                score += bonus
                popups += MelonPopup(SLING_X, SLING_Y + 3f, "+$bonus")
            }
            phase = MelonPhase.Cleared
        }
    }

    private fun age(dt: Float) {
        popups.forEach { it.age += dt }
        popups.removeAll { it.age > POPUP_TIME }
        blasts.forEach { it.age += dt }
        blasts.removeAll { it.age > BLAST_TIME }
        pieces.filter { it.kind == PieceKind.Seed }.forEach {
            it.age += dt
            if (it.age > (if (it.melonKind != null) SPLIT_TIME else SEED_TIME)) remove(it)
        }
    }

    private fun applyDamage() {
        if (damage.isEmpty()) return
        val hits = damage.map { (piece, amount) -> piece to amount }
        damage.clear()
        for ((piece, amount) in hits) hurt(piece, amount)
    }

    /** An Overripe Melon bursts: it is gone, and its juice shockwave pushes and breaks what is near. */
    private fun pop(m: MelonPiece) {
        burstUsed = true
        val blast = MelonBlast(m.x, m.y, POP_RADIUS, POP_DAMAGE)
        remove(m)
        blasts += blast
        explode(blast)
    }

    /** The fans lift every melon and seed in their updrafts. */
    private fun blow() {
        if (level.fans.isEmpty()) return
        for (piece in pieces) {
            if (piece.kind != PieceKind.Melon && piece.kind != PieceKind.Seed) continue
            val up = lift(piece.x, piece.y)
            if (up > 0f) piece.body.applyForceToCenter(Vec2(0f, piece.body.mass * up))
        }
    }

    /** A hanging beam's cables snap: it falls, heavier than plain steel. */
    private fun snap(piece: MelonPiece) {
        piece.hanging = false
        val fixture = piece.body.fixtureList
        fixture.density = fixture.density * piece.dropMass
        piece.body.type = BodyType.DYNAMIC
        piece.body.resetMassData()
        piece.body.isAwake = true
        popups += MelonPopup(piece.x, piece.y + 1f, if (piece.drone) "CRASH" else "SNAP")
    }

    private fun hurt(
        piece: MelonPiece,
        amount: Float,
    ) {
        if (piece.hanging) {
            piece.cableHp -= amount
            if (piece.cableHp <= 0f) snap(piece)
            return
        }
        if (piece.dead || piece.fixed || piece.maxHp <= 0f) return
        piece.hp -= amount
        if (piece.hp <= 0f) destroy(piece)
    }

    private fun destroy(piece: MelonPiece) {
        if (piece.dead) return
        val points =
            when (piece.kind) {
                PieceKind.Block -> (piece.material?.hp?.toInt() ?: 0) * (if (comboTime > 0f) 2 else 1)
                PieceKind.Slop -> piece.slop?.points ?: 0
                PieceKind.Battery -> BATTERY_POINTS
                else -> 0
            }
        if (points > 0) {
            score += points
            popups += MelonPopup(piece.x, piece.y + 1f, points.toString())
        }
        if (piece.slop == SlopKind.Ad) comboTime = COMBO_TIME
        if (piece.kind == PieceKind.Battery) {
            val blast = MelonBlast(piece.x, piece.y, piece.blastRadius, piece.blastDamage)
            pendingBlasts += blast to BLAST_DELAY
        }
        remove(piece)
    }

    private fun fireBlasts() {
        if (pendingBlasts.isEmpty()) return
        val now = pendingBlasts.map { (blast, wait) -> blast to wait - STEP }
        pendingBlasts.clear()
        for ((blast, wait) in now) {
            if (wait > 0f) {
                pendingBlasts += blast to wait
                continue
            }
            blasts += blast
            explode(blast)
        }
    }

    private fun explode(blast: MelonBlast) {
        for (piece in pieces.toList()) {
            if (piece.dead || piece.fixed) continue
            val dx = piece.x - blast.x
            val dy = piece.y - blast.y
            val d = hypot(dx, dy)
            if (d >= blast.radius) continue
            val f = 1f - d / blast.radius
            val push = min(BLAST_PUSH * f, piece.body.mass * MAX_BLAST_SPEED)
            val nx = if (d > 0.01f) dx / d else 0f
            val ny = if (d > 0.01f) dy / d else 1f
            piece.body.applyLinearImpulse(Vec2(nx * push, ny * push + push * 0.3f), piece.body.worldCenter)
            if (piece.kind != PieceKind.Melon && piece.kind != PieceKind.Seed) hurt(piece, blast.damage * f)
        }
    }

    /** Anything that falls off the stage is gone; slop that does still scores. */
    private fun dropLost() {
        for (piece in pieces.toList()) {
            if (piece.dead || piece.fixed) continue
            if (piece.y < -LOST_DEPTH || piece.x < -LOST_DEPTH || piece.x > STAGE_W + LOST_DEPTH) {
                if (piece.kind == PieceKind.Slop || piece.kind == PieceKind.Battery) destroy(piece) else remove(piece)
            }
        }
    }

    private fun remove(piece: MelonPiece) {
        if (!piece.dead) {
            piece.dead = true
            world.destroyBody(piece.body)
        }
        pieces.remove(piece)
    }

    private fun ground() {
        val def = BodyDef().apply { position.set(STAGE_W / 2, -1f) }
        val body = world.createBody(def)
        val shape = PolygonShape().apply { setAsBox(STAGE_W * 2, 1f) }
        body.createFixture(
            FixtureDef().apply {
                this.shape = shape
                friction = GROUND_FRICTION
            },
        )
    }

    private fun box(
        kind: PieceKind,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        density: Float,
        hp: Float,
        fixed: Boolean = false,
        material: MelonMaterial? = null,
        slop: SlopKind? = null,
        radians: Float = 0f,
        bounce: Float = BLOCK_BOUNCE,
        friction: Float = BLOCK_FRICTION,
    ): MelonPiece {
        val def =
            BodyDef().apply {
                type = if (fixed) BodyType.STATIC else BodyType.DYNAMIC
                position.set(x, y)
                angle = radians
            }
        val body = world.createBody(def)
        val shape = PolygonShape().apply { setAsBox(w / 2, h / 2) }
        body.createFixture(
            FixtureDef().apply {
                this.shape = shape
                this.density = density
                this.friction = friction
                restitution = bounce
            },
        )
        val piece = MelonPiece(kind, body, w, h, hp, material, slop, fixed)
        body.userData = piece
        pieces += piece
        return piece
    }

    private fun circle(
        kind: PieceKind,
        x: Float,
        y: Float,
        r: Float,
        density: Float,
        hp: Float,
        slop: SlopKind? = null,
        melonKind: MelonKind? = null,
    ): MelonPiece {
        val def =
            BodyDef().apply {
                type = BodyType.DYNAMIC
                position.set(x, y)
                bullet = kind == PieceKind.Melon || kind == PieceKind.Seed
                angularDamping = ROLL_DAMPING
            }
        val body = world.createBody(def)
        val shape = CircleShape().apply { m_radius = r }
        body.createFixture(
            FixtureDef().apply {
                this.shape = shape
                this.density = density
                friction = MELON_FRICTION
                restitution = MELON_BOUNCE
                if (kind == PieceKind.Melon || kind == PieceKind.Seed) filter.categoryBits = MELON_BIT
            },
        )
        val piece = MelonPiece(kind, body, r * 2, r * 2, hp, slop = slop, melonKind = melonKind)
        body.userData = piece
        pieces += piece
        return piece
    }

    private fun addBlock(b: MelonBlock) {
        box(PieceKind.Block, b.x, b.y, b.w, b.h, b.material.density, b.material.hp, b.fixed, material = b.material)
    }

    /** A steel beam held still by its cables until they snap. */
    private fun addHanging(b: MelonHangingBeam) {
        val steel = MelonMaterial.Steel
        val piece = box(PieceKind.Block, b.x, b.y, b.w, HANGING_H, steel.density, steel.hp, material = steel)
        piece.body.type = BodyType.STATIC
        piece.hanging = true
        piece.cableHp = b.cableHp
        piece.dropMass = b.massFactor
    }

    /** A drone platform: steel held still by its thrusters until they break. */
    private fun addDrone(d: MelonDrone) {
        val steel = MelonMaterial.Steel
        val piece = box(PieceKind.Block, d.x, d.y, d.w, DRONE_H, steel.density, steel.hp, material = steel)
        piece.body.type = BodyType.STATIC
        piece.hanging = true
        piece.drone = true
        piece.cableHp = d.thrusterHp
    }

    /** A firewall shield: a turning ring of edges, with its gap, that only melons and seeds collide with. */
    private fun addShield(sh: MelonShield) {
        val def =
            BodyDef().apply {
                type = BodyType.KINEMATIC
                position.set(sh.x, sh.y)
                angularVelocity = sh.turn * PI.toFloat() / 180f
            }
        val body = world.createBody(def)
        val start = GAP_AT + sh.gap / 2
        val sweep = 360f - sh.gap
        val segments = (sweep / RING_SEGMENT).toInt().coerceAtLeast(1)
        for (i in 0 until segments) {
            val a0 = (start + sweep * i / segments) * PI.toFloat() / 180f
            val a1 = (start + sweep * (i + 1) / segments) * PI.toFloat() / 180f
            val edge = EdgeShape().apply { set(Vec2(sh.r * cos(a0), sh.r * sin(a0)), Vec2(sh.r * cos(a1), sh.r * sin(a1))) }
            body.createFixture(
                FixtureDef().apply {
                    shape = edge
                    restitution = SHIELD_BOUNCE
                    friction = 0f
                    filter.categoryBits = SHIELD_BIT
                    filter.maskBits = MELON_BIT
                },
            )
        }
        val piece = MelonPiece(PieceKind.Shield, body, sh.r * 2, sh.r * 2, sh.hp)
        piece.gapDegrees = sh.gap
        body.userData = piece
        pieces += piece
    }

    /** A banner is a bolted, springy plate: it can't break, and throws a melon back harder than it came. */
    private fun addBanner(b: MelonBanner) {
        box(
            PieceKind.Banner,
            b.x,
            b.y,
            b.w,
            BANNER_T,
            0f,
            0f,
            fixed = true,
            radians = b.degrees * PI.toFloat() / 180f,
            bounce = b.bounce,
            friction = BANNER_FRICTION,
        )
    }

    /**
     * Sets each slop and battery down on the highest thing under it that it isn't above (or the ground),
     * lowest first, so the data's heights only need to name the floor and stacked slop stays stacked.
     */
    private fun placeLoose() {
        val tops =
            (
                level.blocks.map { Triple(it.x - it.w / 2, it.x + it.w / 2, it.y + it.h / 2) } +
                    level.drones.map { Triple(it.x - it.w / 2, it.x + it.w / 2, it.y + DRONE_H / 2) }
            ).toMutableList()
        val loose =
            level.slop.map { Triple(it.x, it.y, it as Any) } + level.batteries.map { Triple(it.x, it.y, it as Any) }
        for ((x, y, thing) in loose.sortedBy { it.second }) {
            val (w, h) =
                when (thing) {
                    is MelonSlop -> thing.kind.w to thing.kind.h
                    else -> BATTERY_W to BATTERY_H
                }
            val floor =
                tops
                    .filter { (left, right, top) ->
                        right > x - w / 2 + PLACE_SLACK && left < x + w / 2 - PLACE_SLACK &&
                            top <= y + h / 2
                    }.maxOfOrNull { it.third } ?: 0f
            val at = floor + h / 2 + PLACE_GAP
            when (thing) {
                is MelonSlop -> {
                    if (thing.kind.round) {
                        circle(PieceKind.Slop, x, at, w / 2, SLOP_DENSITY, thing.kind.hp, thing.kind)
                    } else {
                        box(PieceKind.Slop, x, at, w, h, SLOP_DENSITY, thing.kind.hp, slop = thing.kind)
                    }
                }

                is MelonBattery -> {
                    box(PieceKind.Battery, x, at, w, h, SLOP_DENSITY, BATTERY_HP).also {
                        it.blastRadius = thing.radius
                        it.blastDamage = thing.damage
                    }
                }
            }
            tops += Triple(x - w / 2, x + w / 2, floor + h + PLACE_GAP)
        }
    }

    /** Hit damage: the impulse of a contact that was closing fast, shared out to both sides. */
    private inner class Hits : ContactListener {
        override fun beginContact(contact: Contact) = Unit

        override fun endContact(contact: Contact) = Unit

        override fun preSolve(
            contact: Contact,
            oldManifold: Manifold,
        ) {
            if (!armed) return
            val a = contact.fixtureA.body
            val b = contact.fixtureB.body
            contact.getWorldManifold(worldManifold)
            val point = worldManifold.points[0]
            val va = a.getLinearVelocityFromWorldPoint(point)
            val vb = b.getLinearVelocityFromWorldPoint(point)
            val n = worldManifold.normal
            closing[contact] = abs((vb.x - va.x) * n.x + (vb.y - va.y) * n.y)
        }

        override fun postSolve(
            contact: Contact,
            impulse: ContactImpulse,
        ) {
            if (!armed) return
            val speed = closing[contact] ?: return
            if (speed < HIT_SPEED) return
            var total = 0f
            for (i in 0 until impulse.count) total += impulse.normalImpulses[i]
            if (total <= 0f) return
            share(contact.fixtureA.body.userData as? MelonPiece, total)
            share(contact.fixtureB.body.userData as? MelonPiece, total)
        }

        private fun share(
            piece: MelonPiece?,
            total: Float,
        ) {
            if (piece == null) return
            if (piece.kind == PieceKind.Melon && piece.melonKind == MelonKind.Overripe && total >= POP_IMPULSE) popping = piece
            val scale =
                when (piece.kind) {
                    PieceKind.Slop -> {
                        SLOP_FRAGILITY
                    }

                    PieceKind.Battery -> {
                        BATTERY_FRAGILITY
                    }

                    PieceKind.Shield -> {
                        1f
                    }

                    PieceKind.Block -> {
                        when (piece.material) {
                            MelonMaterial.Stone -> STONE_TOUGHNESS
                            MelonMaterial.Steel -> STEEL_TOUGHNESS
                            else -> 1f
                        }
                    }

                    else -> {
                        return
                    }
                }
            damage[piece] = (damage[piece] ?: 0f) + total * scale
        }
    }

    companion object {
        const val STAGE_W = 100f
        const val STAGE_H = 40f
        const val GRAVITY = 9.8f
        const val SLING_X = 12f
        const val SLING_Y = 5f
        const val MIN_ANGLE = 0f
        const val MAX_ANGLE = 80f
        const val MIN_POWER = 20f
        const val MELON_R = 0.9f
        const val SEED_R = 0.3f
        const val BATTERY_W = 1.4f
        const val BATTERY_H = 2f
        const val BATTERY_POINTS = 75
        const val UNUSED_MELON_BONUS = 500

        private const val STEP = 1f / 60f

        /** The aimed arc is followed this many seconds at most. */
        private const val LANDING_MAX_TIME = 8f
        private const val MAX_CATCH_UP = 0.1f
        private const val VELOCITY_ITERATIONS = 8
        private const val POSITION_ITERATIONS = 3
        private const val SETTLE_STEPS = 90

        private const val SEED_DENSITY = 6f
        private const val SLOP_DENSITY = 0.6f
        private const val BLOCK_FRICTION = 0.7f
        private const val BLOCK_BOUNCE = 0.05f
        private const val GROUND_FRICTION = 0.9f
        private const val MELON_FRICTION = 0.6f
        private const val MELON_BOUNCE = 0.25f
        private const val ROLL_DAMPING = 1.5f

        private const val HIT_SPEED = 1.2f
        private const val SLOP_FRAGILITY = 1.5f
        private const val BATTERY_FRAGILITY = 3f
        private const val STONE_TOUGHNESS = 0.7f
        private const val STEEL_TOUGHNESS = 0.5f
        private const val BATTERY_HP = 10f

        private const val PLACE_SLACK = 0.05f
        private const val PLACE_GAP = 0.02f
        private const val BLAST_PUSH = 60f
        private const val MAX_BLAST_SPEED = 18f
        private const val BLAST_DELAY = 0.12f
        const val BLAST_TIME = 0.5f

        private const val MIN_BURST_TIME = 0.1f
        private const val BURST_MIN_SPEED = 18f
        private const val BURST_BOOST = 1.25f
        private const val BURST_SPREAD = 12f
        private const val SEED_TIME = 3f
        private const val SPLIT_SPREAD = 10f
        const val SPLIT_R = 0.65f
        private const val SPLIT_TIME = 5f
        const val BANNER_T = 0.5f
        const val HANGING_H = 1.4f
        const val DRONE_H = 1f

        /** A shield's gap is centred this many degrees round from its right, as its sprite draws it. */
        const val GAP_AT = 50f
        private const val RING_T = 0.3f
        private const val RING_SEGMENT = 10f
        private const val SHIELD_BOUNCE = 0.5f
        private const val SHIELD_BIT = 0x0002
        private const val MELON_BIT = 0x0004
        private const val POP_RADIUS = 5.5f
        private const val POP_DAMAGE = 140f
        private const val POP_IMPULSE = 40f
        private const val SLAM_SPEED = 28f
        private const val SLAM_KEEP = 0.2f
        private const val BANNER_FRICTION = 0.2f

        private const val STILL_SPEED = 0.25f
        private const val STILL_TIME = 0.8f
        private const val MAX_FLIGHT = 9f
        private const val CLEAR_DELAY = 1.2f
        private const val COMBO_TIME = 4f
        private const val LOST_DEPTH = 8f
        const val POPUP_TIME = 1.2f
    }
}
