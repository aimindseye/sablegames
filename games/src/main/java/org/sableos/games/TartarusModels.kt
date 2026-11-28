package org.sableos.games

/*
 * Tartarus Breach data types. The numbers come from the owner's handoff JSON (docs/tartarus-breach/data) through
 * tools/tartarus/gen_data.py into TartarusData.kt. No Android in here, so the rules run in plain JVM tests.
 */

internal enum class TbShot { BEAM, WAVE, BOLT, CHAIN }

internal data class TbWeapon(
    val id: String,
    val name: String,
    val label: String,
    /** The keycap badge: Q, W, E or R. */
    val badge: String,
    val damage: Int,
    val fireMs: Int,
    val favor: Int,
    val shot: TbShot,
    val count: Int,
    val spreadDeg: Float,
    val pierce: Int,
    val knockback: Float,
    val color: Long,
    val magnetDeg: Float,
    val speed: Float,
    val chainTargets: Int,
    val chainRadius: Float,
)

internal data class TbBoon(
    val id: String,
    val deity: String,
    val name: String,
    val description: String,
    val accent: Long,
    val maxStacks: Int,
    val stat: String,
    val value: Float,
    val operation: String,
    val cursed: Boolean = false,
)

internal data class TbEnemyDef(
    val id: String,
    val name: String,
    val tier: String,
    val hp: Int,
    val speed: Float,
    val attack: String,
    val damage: Int,
    val range: Float,
    val obols: Int,
    val favor: Int,
) {
    val isBoss get() = tier == "boss"

    /** How big the enemy is on the floor, for hits, pillars and crowding. */
    val radius: Float
        get() =
            when (tier) {
                "boss" -> 2.1f
                "heavy" -> 1.35f
                "elite" -> if (id == "tartarus_titan") 1.6f else 1.0f
                "flanker" -> 0.8f
                else -> 0.7f
            }

    /** How hard knockback shoves it: bosses and heavies barely move. */
    val shove: Float
        get() =
            when (tier) {
                "boss" -> 0.05f
                "heavy" -> 0.35f
                "elite" -> 0.6f
                else -> 1f
            }

    val flies get() = attack == "swoop_fireball"
}

internal data class TbPool(
    val id: String,
    val weight: Int,
)

internal data class TbStage(
    val number: Int,
    val title: String,
    val boss: Boolean,
    val durationSec: Int,
    val radius: Float,
    val pillars: String,
    val hazard: String,
    val spawnMs: Int,
    val maxConcurrent: Int,
    val pool: List<TbPool>,
    val bossId: String,
    val minionId: String,
    val minionMax: Int,
    val minionMs: Int,
)

internal data class TbGate(
    val id: String,
    val number: Int,
    val name: String,
    val sky: Long,
    val floor: Long,
    val pillar: Long,
    val glow: Long,
    val stages: List<TbStage>,
)

/** What the player keeps between runs: Obols, the gates sealed, bests and the Shrine upgrades bought. */
internal data class TbMeta(
    val obols: Int = 0,
    /** The highest gate number open to play (1 to 4). */
    val unlocked: Int = 1,
    /** Fastest clear of each gate in seconds, 0 where not cleared yet (index 0 is Gate 1). */
    val bestClear: List<Int> = listOf(0, 0, 0, 0),
    /** The furthest wave reached in each gate. */
    val bestWave: List<Int> = listOf(0, 0, 0, 0),
    /** Shrine upgrade levels, by [TbUpgrade] order. */
    val levels: List<Int> = TbUpgrade.entries.map { 0 },
) {
    fun level(u: TbUpgrade) = levels.getOrElse(u.ordinal) { 0 }

    fun cost(u: TbUpgrade) = u.baseCost * (level(u) + 1)

    fun canBuy(u: TbUpgrade) = level(u) < u.maxLevel && obols >= cost(u)

    fun buy(u: TbUpgrade): TbMeta =
        if (!canBuy(u)) {
            this
        } else {
            copy(obols = obols - cost(u), levels = levels.toMutableList().also { it[u.ordinal] = level(u) + 1 })
        }

    fun encode() = listOf(obols, unlocked, bestClear.joinToString(";"), bestWave.joinToString(";"), levels.joinToString(";")).joinToString(",")

    companion object {
        fun decode(text: String?): TbMeta {
            val p = text?.split(",") ?: return TbMeta()
            if (p.size < 5) return TbMeta()
            fun ints(s: String) = s.split(";").mapNotNull { it.toIntOrNull() }
            val clear = ints(p[2]).let { if (it.size == 4) it else listOf(0, 0, 0, 0) }
            val wave = ints(p[3]).let { if (it.size == 4) it else listOf(0, 0, 0, 0) }
            val levels = ints(p[4]).let { l -> TbUpgrade.entries.mapIndexed { i, u -> l.getOrElse(i) { 0 }.coerceIn(0, u.maxLevel) } }
            return TbMeta(p[0].toIntOrNull() ?: 0, (p[1].toIntOrNull() ?: 1).coerceIn(1, 4), clear, wave, levels)
        }
    }
}

/** The permanent upgrades the Underworld Shrine sells for Obols. */
internal enum class TbUpgrade(
    val label: String,
    val blurb: String,
    val baseCost: Int,
    val maxLevel: Int,
) {
    Vigor("Vigor", "+10 max HP", 120, 5),
    Might("Might", "+5% weapon damage", 150, 5),
    Grace("Grace", "+0.5 Favor per second", 100, 4),
    Wings("Wings", "Hermes' Dash recharges 8% faster", 130, 4),
    Fortune("Fortune", "+10% Obols from kills", 140, 5),
}
