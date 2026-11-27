package org.sableos.games

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource

/** The art of Tartarus Breach, made from the owner's handoff by tools/tartarus/cut_sprites.py. */
internal class TbSprites(
    private val load: (Int) -> ImageBitmap,
) {
    private val backgrounds by lazy {
        listOf(R.drawable.tb_bg_1, R.drawable.tb_bg_2, R.drawable.tb_bg_3, R.drawable.tb_bg_4).map(load)
    }
    private val portrait by lazy { load(R.drawable.tb_portrait) }
    private val enemies: Map<String, ImageBitmap> by lazy {
        mapOf(
            "skeletal_hoplite" to R.drawable.tb_skeletal_hoplite,
            "shade_wisp" to R.drawable.tb_shade_wisp,
            "stygian_stalker" to R.drawable.tb_stygian_stalker,
            "fire_harpy" to R.drawable.tb_fire_harpy,
            "cinder_imp" to R.drawable.tb_cinder_imp,
            "magma_cyclops" to R.drawable.tb_magma_cyclops,
            "charging_minotaur" to R.drawable.tb_charging_minotaur,
            "gorgon_sentinel" to R.drawable.tb_gorgon_sentinel,
            "tartarus_titan" to R.drawable.tb_tartarus_titan,
            "void_acolyte" to R.drawable.tb_void_acolyte,
            "cerberus_hound" to R.drawable.tb_cerberus_hound,
            "hephaestus_automaton" to R.drawable.tb_hephaestus_automaton,
            "asterion_the_bull" to R.drawable.tb_asterion_the_bull,
            "kronos_echo" to R.drawable.tb_kronos_echo,
        ).mapValues { load(it.value) }
    }
    private val weapons: Map<String, ImageBitmap> by lazy {
        mapOf(
            "apollo_sunbow" to R.drawable.tb_fp_apollo_sunbow,
            "poseidon_trident" to R.drawable.tb_fp_poseidon_trident,
            "hades_bident" to R.drawable.tb_fp_hades_bident,
            "zeus_aegis_bolt" to R.drawable.tb_fp_zeus_aegis_bolt,
        ).mapValues { load(it.value) }
    }
    private val boons: Map<String, ImageBitmap> by lazy {
        mapOf(
            "Apollo" to R.drawable.tb_boon_apollo,
            "Ares" to R.drawable.tb_boon_ares,
            "Artemis" to R.drawable.tb_boon_artemis,
            "Athena" to R.drawable.tb_boon_athena,
            "Hades" to R.drawable.tb_boon_hades,
            "Hephaestus" to R.drawable.tb_boon_hephaestus,
            "Hermes" to R.drawable.tb_boon_hermes,
            "Poseidon" to R.drawable.tb_boon_poseidon,
            "Zeus" to R.drawable.tb_boon_zeus,
        ).mapValues { load(it.value) }
    }

    fun background(gate: Int) = backgrounds[(gate - 1).coerceIn(0, 3)]

    fun portrait() = portrait

    fun enemy(id: String) = enemies.getValue(id)

    fun weapon(id: String) = weapons.getValue(id)

    fun boon(deity: String) = boons.getValue(deity)

    /** Decodes everything, off the main thread, before the first frame so no frame stalls on a bitmap. */
    fun preload() {
        backgrounds
        portrait
        enemies
        weapons
        boons
    }
}

@Composable
internal fun rememberTbSprites(): TbSprites {
    val resources = LocalContext.current.resources
    return remember(resources) { TbSprites { id -> ImageBitmap.imageResource(resources, id) } }
}

/** How big each foe stands in the world (units tall) and how high a flier hovers. */
internal object TbLook {
    fun height(def: TbEnemyDef): Float =
        when (def.id) {
            "skeletal_hoplite" -> 2.1f
            "shade_wisp" -> 2.3f
            "stygian_stalker" -> 1.7f
            "fire_harpy" -> 2.4f
            "cinder_imp" -> 1.5f
            "magma_cyclops" -> 3.7f
            "charging_minotaur" -> 3.3f
            "gorgon_sentinel" -> 2.7f
            "tartarus_titan" -> 4.6f
            "void_acolyte" -> 2.5f
            "cerberus_hound" -> 4.6f
            "hephaestus_automaton" -> 6.4f
            "asterion_the_bull" -> 5.4f
            else -> 6.0f
        }

    /** A flier or ghost floats above the floor. */
    fun hover(def: TbEnemyDef): Float =
        when (def.id) {
            "fire_harpy" -> 1.5f
            "shade_wisp" -> 0.45f
            "void_acolyte" -> 0.3f
            else -> 0f
        }
}

internal object TbColors {
    val Gold = Color(0xFFE8C872)
    val GoldDark = Color(0xFF8A6A2A)
    val Bronze = Color(0xFFB07A3A)
    val Stone = Color(0xFF2B2620)
    val StoneDark = Color(0xFF1A1612)
    val Marble = Color(0xFFD9D3C4)
    val Cyan = Color(0xFF4DE8FF)
    val Blood = Color(0xFFD62839)
    val Hp = Color(0xFFE5484D)
    val Favor = Color(0xFF4DA8FF)
    val Ink = Color(0xFF120E16)
}
