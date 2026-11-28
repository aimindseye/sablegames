package org.sableos.games

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/*
 * What stands in a Tartarus Breach arena besides the player: pillars, foes, shots, warnings on the floor, hazard
 * zones and short-lived effects. Positions are in world units on the floor: x and y, the player looking along
 * (cos yaw, sin yaw). Plain Kotlin, so it runs in JVM tests.
 */

internal enum class TbEState { Spawn, Move, Windup, Strike, Recover, Stun, Dying }

internal class TbEnemy(
    val def: TbEnemyDef,
    var x: Float,
    var y: Float,
    val uid: Int,
) {
    var hp = def.hp.toFloat()
    val maxHp = def.hp.toFloat()
    var state = TbEState.Spawn
    var timer = if (def.isBoss) 1.4f else 0.7f
    var age = 0f
    var vx = 0f
    var vy = 0f
    var cooldown = 1.2f
    var flash = 0f
    var burn = 0f
    var burnDps = 0f
    var marked = 0f
    var lavaT = 0f
    var dirX = 0f
    var dirY = 0f
    var orbit = 0f
    var orbitDir = 1f
    var step = 0
    var hit = false
    var fall = 0f

    /** Whether this foe's death pays Obols and Favor (an imp that blew itself up does not). */
    var pays = true
    var volley = 0f

    /** The strike a boss or foe is winding up: used by the screen to flash and lean the sprite. */
    var enraged = false
    val boss get() = def.isBoss
    val alive get() = state != TbEState.Dying
}

internal class TbProj(
    var x: Float,
    var y: Float,
    val dx: Float,
    val dy: Float,
    val speed: Float,
    val dmg: Float,
    val radius: Float,
    val friendly: Boolean,
    val color: Long,
    var pierce: Int,
    var life: Float,
) {
    val hitIds = HashSet<Int>()
    var age = 0f
}

/** A warning on the floor: a circle or a line that hurts when its time runs out. */
internal class TbTele(
    val line: Boolean,
    val x: Float,
    val y: Float,
    val x2: Float,
    val y2: Float,
    val r: Float,
    var t: Float,
    val total: Float,
    val dmg: Int,
    val slows: Boolean = false,
    /** A boss that leaps to (x, y) when this ends. */
    val leaper: TbEnemy? = null,
)

internal enum class TbZoneKind { Fog, Time, Rift, LavaRing, LavaStrip, Vent, Spikes, Well }

internal class TbZone(
    val kind: TbZoneKind,
    val x: Float,
    val y: Float,
    val r: Float,
    var phase: Float,
    var life: Float = Float.MAX_VALUE,
) {
    /** For vents and spikes: 0 resting, 1 warning, 2 firing. */
    var stage = 0
    var done = false
}

internal enum class TbFxKind { Beam, Wave, Chain, Puff, Coin, Text, Burst, Ember, Hurt }

internal class TbFx(
    val kind: TbFxKind,
    val x: Float,
    val y: Float,
    val x2: Float,
    val y2: Float,
    val color: Long,
    val life: Float,
    val text: String = "",
    val n: Int = 0,
) {
    var t = 0f
}

internal class TbPillar(
    var x: Float,
    var y: Float,
    val r: Float,
    val angle: Float = 0f,
    val orbit: Float = 0f,
) {
    val moving get() = orbit > 0f
}

internal class TbToast(
    val text: String,
    val good: Boolean,
    val at: Float,
)

/** What the player holds this frame: -1..1 axes (turn: right is positive), and whether fire is down. */
internal data class TbInput(
    val forward: Float = 0f,
    val strafe: Float = 0f,
    val turn: Float = 0f,
    val fire: Boolean = false,
)

internal object TbWorld {
    const val PILLAR_R = 1.15f

    fun pillars(
        preset: String,
        r: Float,
    ): List<TbPillar> {
        fun ring(
            n: Int,
            frac: Float,
            offset: Float = 0f,
        ) = (0 until n).map { i ->
            val a = offset + i * 2f * PI.toFloat() / n
            TbPillar(cos(a) * r * frac, sin(a) * r * frac, PILLAR_R)
        }
        return when (preset) {
            "open_ring_4" -> ring(4, 0.55f, PI.toFloat() / 4)
            "open_ring_6" -> ring(6, 0.6f)
            "outer_ring_6" -> ring(6, 0.8f)
            "inner_pillars_4" -> ring(4, 0.3f, PI.toFloat() / 4)
            "grid_6" -> listOf(-0.5f, 0f, 0.5f).flatMap { gy -> listOf(-0.34f, 0.34f).map { gx -> TbPillar(gx * r, gy * r, PILLAR_R) } }
            "cross_8" -> listOf(0.38f, 0.7f).flatMap { f -> listOf(TbPillar(f * r, 0f, PILLAR_R), TbPillar(-f * r, 0f, PILLAR_R), TbPillar(0f, f * r, PILLAR_R), TbPillar(0f, -f * r, PILLAR_R)) }
            "maze_corridors_a" -> corridors(r, vertical = false)
            "maze_corridors_b" -> corridors(r, vertical = true)
            "shifting_walls_4" -> (0 until 4).map { i -> TbPillar(0f, 0f, PILLAR_R * 1.2f, angle = i * PI.toFloat() / 2f, orbit = r * 0.5f) }
            else -> emptyList()
        }
    }

    /** Two rows of pillars with gaps, so the arena reads as corridors. */
    private fun corridors(
        r: Float,
        vertical: Boolean,
    ): List<TbPillar> {
        val out = ArrayList<TbPillar>()
        for (row in listOf(-0.3f, 0.3f)) {
            var k = -0.65f
            while (k <= 0.66f) {
                if (k < -0.12f || k > 0.12f) {
                    out += if (vertical) TbPillar(row * r, k * r, PILLAR_R) else TbPillar(k * r, row * r, PILLAR_R)
                }
                k += 0.2f
            }
        }
        return out
    }

    fun zones(
        hazard: String,
        r: Float,
        rnd: Random,
    ): List<TbZone> {
        fun spot(
            n: Int,
            minF: Float,
            maxF: Float,
            kind: TbZoneKind,
            size: Float,
        ) = (0 until n).map { i ->
            val a = i * 2f * PI.toFloat() / n + rnd.nextFloat() * 0.8f
            val d = r * (minF + rnd.nextFloat() * (maxF - minF))
            TbZone(kind, cos(a) * d, sin(a) * d, size, rnd.nextFloat() * 3f)
        }
        return when (hazard) {
            "soul_fog_slow_zones" -> spot(3, 0.25f, 0.65f, TbZoneKind.Fog, 4.5f)
            "time_dilation_zones" -> spot(3, 0.25f, 0.65f, TbZoneKind.Time, 4.8f)
            "lava_border" -> listOf(TbZone(TbZoneKind.LavaRing, 0f, 0f, r - 1.7f, 0f))
            "lava_channels_2" -> listOf(TbZone(TbZoneKind.LavaStrip, -r * 0.42f, 0f, 1.5f, 0f), TbZone(TbZoneKind.LavaStrip, r * 0.42f, 0f, 1.5f, 0f))
            "erupting_lava_vents" -> spot(4, 0.3f, 0.7f, TbZoneKind.Vent, 2.6f)
            "spike_floor_plates" -> spot(5, 0.25f, 0.7f, TbZoneKind.Spikes, 1.7f)
            "void_gravity_wells" -> spot(2, 0.35f, 0.6f, TbZoneKind.Well, 8f)
            else -> emptyList()
        }
    }

    fun dist(
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
    ) = hypot(ax - bx, ay - by)

    /** The distance from (px, py) to the segment (ax, ay)-(bx, by). */
    fun segDist(
        px: Float,
        py: Float,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
    ): Float {
        val vx = bx - ax
        val vy = by - ay
        val len2 = vx * vx + vy * vy
        val t = if (len2 < 1e-6f) 0f else (((px - ax) * vx + (py - ay) * vy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + vx * t), py - (ay + vy * t))
    }

    /** The angle difference a - b folded to -PI..PI. */
    fun angDiff(
        a: Float,
        b: Float,
    ): Float {
        var d = (a - b) % (2f * PI.toFloat())
        if (d > PI) d -= 2f * PI.toFloat()
        if (d < -PI) d += 2f * PI.toFloat()
        return d
    }
}

/** Things that happened this frame, for the sound and vibration to answer. */
internal enum class TbEvent { Fire, Hit, Kill, Hurt, Dash, Draft, Seal, Fall }

/** Where a hit on the player came from, in the world, and when. */
internal class TbHitDir(
    val angle: Float,
    val at: Float,
)
