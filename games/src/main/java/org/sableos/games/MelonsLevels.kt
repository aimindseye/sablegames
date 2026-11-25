package org.sableos.games

/**
 * Bitter Melons' levels, from the owner's level data (docs/bitter-melons/levels.json, 2026-11-25). The
 * stage is 100 m wide and 40 m tall with the ground at y = 0; every position is the centre of a thing, in
 * metres, y up. Slop and batteries are set down on whatever is under them when a level starts
 * ([MelonsWorld]), so their heights in the data only need to say which floor they are on.
 */
internal enum class MelonMaterial(
    val hp: Float,
    val density: Float,
    val label: String,
) {
    Glass(15f, 0.6f, "glass"),
    Wood(40f, 0.5f, "wood"),
    Stone(120f, 2f, "stone"),
}

/** The AI Slop: [w] by [h] metres; the Premium Ad-Slop is round, [w] across. */
internal enum class SlopKind(
    val points: Int,
    val hp: Float,
    val w: Float,
    val h: Float,
    val round: Boolean = false,
) {
    App(50, 18f, 2f, 2f),
    Bot(100, 40f, 2.4f, 2.4f),
    Ad(300, 60f, 2.4f, 2.4f, round = true),
}

internal data class MelonBlock(
    val material: MelonMaterial,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    /** Bolted in place: terraces and the bunker roof never move or break. */
    val fixed: Boolean = false,
)

internal data class MelonSlop(
    val kind: SlopKind,
    val x: Float,
    val y: Float,
)

/** A Red Overheated Battery at ground level or on a floor; it blows up when hit hard enough. */
internal data class MelonBattery(
    val x: Float,
    val y: Float,
    val radius: Float = 6f,
    val damage: Float = 140f,
)

internal data class MelonLevel(
    val world: Int,
    val number: Int,
    val name: String,
    /** One line on what the level teaches, shown on the level select. */
    val lesson: String,
    val melons: Int,
    /** The slingshot's launch speed at full power, in m/s. */
    val maxSpeed: Float,
    val blocks: List<MelonBlock>,
    val slop: List<MelonSlop>,
    val batteries: List<MelonBattery> = emptyList(),
    /** The score for one, two and three seeds. */
    val seeds: List<Int>,
) {
    val id: String get() = "$world-$number"

    /** Where the structure starts, for the arrow that points at it while aiming. */
    val fortX: Float get() = (blocks.map { it.x - it.w / 2 } + slop.map { it.x }).min()

    /** Where the structure ends, so looking ahead can centre on it. */
    val fortEnd: Float get() = (blocks.map { it.x + it.w / 2 } + slop.map { it.x }).max()
}

private fun stone(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    fixed: Boolean = false,
) = MelonBlock(MelonMaterial.Stone, x, y, w, h, fixed)

private fun wood(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) = MelonBlock(MelonMaterial.Wood, x, y, w, h)

private fun glass(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) = MelonBlock(MelonMaterial.Glass, x, y, w, h)

private fun app(
    x: Float,
    y: Float,
) = MelonSlop(SlopKind.App, x, y)

private fun bot(
    x: Float,
    y: Float,
) = MelonSlop(SlopKind.Bot, x, y)

private fun ad(
    x: Float,
    y: Float,
) = MelonSlop(SlopKind.Ad, x, y)

internal object MelonsLevels {
    const val PER_WORLD = 9

    val worlds = listOf("Prompt Valley" to "Sunny hills, wood, glass and stone. Learn to aim.")

    /**
     * World 1 as in the level data. Where a piece there would float or sit inside another, the fix is
     * marked "Added" or "Changed" and the reason given.
     */
    val all: List<MelonLevel> =
        listOf(
            MelonLevel(
                1,
                1,
                "First Prompt",
                "Up and down aim, left and right set the power.",
                melons = 3,
                maxSpeed = 32f,
                blocks = listOf(wood(63f, 4f, 1.2f, 8f), wood(69f, 4f, 1.2f, 8f), wood(66f, 8.5f, 8f, 1f)),
                slop = listOf(app(66f, 10.2f), app(66f, 12.6f)),
                seeds = listOf(200, 800, 1200),
            ),
            MelonLevel(
                1,
                2,
                "Glass House",
                "Press Space in the air for a Seed Burst.",
                melons = 3,
                maxSpeed = 32f,
                blocks =
                    listOf(
                        glass(63f, 2.5f, 0.8f, 5f),
                        glass(70f, 2.5f, 0.8f, 5f),
                        glass(77f, 2.5f, 0.8f, 5f),
                        glass(70f, 5.4f, 16f, 0.8f),
                    ),
                slop = listOf(app(60.5f, 1.5f), app(66.5f, 1.5f), app(73.5f, 1.5f)),
                seeds = listOf(300, 1000, 1450),
            ),
            MelonLevel(
                1,
                3,
                "Domino Stack",
                "Lob high over the wall. Tab looks ahead.",
                melons = 4,
                maxSpeed = 34f,
                blocks =
                    listOf(
                        stone(52f, 5f, 2.5f, 10f, fixed = true),
                        wood(62f, 4.5f, 1.2f, 9f),
                        wood(68f, 4.5f, 1.2f, 9f),
                        wood(74f, 4.5f, 1.2f, 9f),
                    ),
                slop = listOf(app(62f, 10.2f), app(68f, 10.2f), app(74f, 10.2f)),
                seeds = listOf(400, 1300, 1900),
            ),
            MelonLevel(
                1,
                4,
                "Overheated",
                "Hit the red battery to set off a blast.",
                melons = 3,
                maxSpeed = 34f,
                blocks = listOf(stone(62f, 3f, 1.5f, 6f), stone(74f, 3f, 1.5f, 6f), wood(68f, 6.5f, 15f, 1f)),
                slop = listOf(app(64.5f, 8.5f), bot(68f, 8.5f), app(71.5f, 8.5f)),
                batteries = listOf(MelonBattery(68f, 2.5f)),
                seeds = listOf(500, 1250, 1650),
            ),
            MelonLevel(
                1,
                5,
                "The Prompt Tower",
                "Weaken the tower, then crush the gold Ad-Slop.",
                melons = 4,
                maxSpeed = 34f,
                blocks =
                    listOf(
                        // Changed: the 1 x 5 m glass guard post is a booth of two walls and a roof, so the
                        // battery fits underneath and the Slop App stands on top.
                        glass(56.6f, 1.6f, 0.4f, 3.2f),
                        glass(59.4f, 1.6f, 0.4f, 3.2f),
                        glass(58f, 3.4f, 3.6f, 0.4f),
                        stone(72f, 4f, 1.5f, 8f),
                        stone(82f, 4f, 1.5f, 8f),
                        wood(77f, 8.5f, 12f, 1f),
                        // Added: two posts, as the roof had nothing under it.
                        wood(72.6f, 11.25f, 0.8f, 4.5f),
                        wood(81.4f, 11.25f, 0.8f, 4.5f),
                        wood(77f, 14f, 10f, 1f),
                    ),
                slop =
                    listOf(
                        app(58f, 6.2f),
                        app(74.5f, 10.2f),
                        app(79.5f, 10.2f),
                        bot(74.5f, 2f),
                        bot(79.5f, 2f),
                        ad(77f, 11.2f),
                    ),
                batteries = listOf(MelonBattery(58f, 1.5f)),
                seeds = listOf(900, 1800, 2400),
            ),
            MelonLevel(
                1,
                6,
                "Hallucination Bridge",
                "Drop the bridge on the bot below it.",
                melons = 3,
                maxSpeed = 34f,
                blocks =
                    listOf(
                        stone(58f, 4f, 2f, 8f, fixed = true),
                        stone(80f, 4f, 2f, 8f, fixed = true),
                        wood(69f, 8.5f, 22f, 1f),
                        glass(69f, 9.5f, 18f, 0.8f),
                    ),
                slop = listOf(app(63f, 11f), app(75f, 11f), bot(69f, 2f)),
                seeds = listOf(450, 1150, 1550),
            ),
            MelonLevel(
                1,
                7,
                "Token Limit",
                "Two melons. Thread the window slit.",
                melons = 2,
                maxSpeed = 35f,
                blocks =
                    listOf(
                        stone(62f, 2.8f, 2f, 5.6f, fixed = true),
                        stone(62f, 11.5f, 2f, 7f, fixed = true),
                        stone(70f, 15.5f, 18f, 1.5f, fixed = true),
                        stone(78f, 8f, 2f, 16f, fixed = true),
                    ),
                slop = listOf(bot(74.5f, 3f), bot(74.5f, 7f)),
                batteries = listOf(MelonBattery(69.5f, 7f, radius = 7.5f, damage = 170f)),
                seeds = listOf(400, 850, 1100),
            ),
            MelonLevel(
                1,
                8,
                "The Clone Farm",
                "Time your Seed Burst over the terraces.",
                melons = 4,
                maxSpeed = 35f,
                blocks =
                    listOf(
                        // Added: the three terraces (2, 6 and 10 m up) the greenhouses stand on.
                        stone(56f, 1f, 8f, 2f, fixed = true),
                        stone(68f, 3f, 8f, 6f, fixed = true),
                        stone(80.5f, 5f, 9f, 10f, fixed = true),
                        // Changed: each 6 x 4 m glass greenhouse is two walls and a roof, so the slop fits inside.
                        glass(53.2f, 4f, 0.4f, 4f),
                        glass(58.8f, 4f, 0.4f, 4f),
                        glass(56f, 6.2f, 6f, 0.4f),
                        glass(65.2f, 8f, 0.4f, 4f),
                        glass(70.8f, 8f, 0.4f, 4f),
                        glass(68f, 10.2f, 6f, 0.4f),
                        glass(77.2f, 12f, 0.4f, 4f),
                        glass(84.4f, 12f, 0.4f, 4f),
                        glass(80.8f, 14.2f, 7.6f, 0.4f),
                    ),
                slop = listOf(app(56f, 3.5f), app(68f, 7.5f), app(79.4f, 11.5f), bot(82.2f, 11.5f)),
                seeds = listOf(700, 1500, 2050),
            ),
            MelonLevel(
                1,
                9,
                "The Vibe-Coder's Shed",
                "The boss barn: blast the floor, then the attic.",
                melons = 5,
                maxSpeed = 35f,
                blocks =
                    listOf(
                        stone(58f, 3.5f, 1.5f, 7f),
                        stone(71f, 3.5f, 1.5f, 7f),
                        stone(84f, 3.5f, 1.5f, 7f),
                        wood(71f, 7.5f, 28f, 1.2f),
                        // Added: glass posts under the attic roof, which had nothing under it.
                        glass(68f, 11.05f, 0.6f, 5.9f),
                        glass(74f, 11.05f, 0.6f, 5.9f),
                        glass(71f, 14.5f, 14f, 1f),
                    ),
                slop =
                    listOf(
                        app(61.5f, 9.2f),
                        app(71f, 9.2f),
                        app(80.5f, 9.2f),
                        bot(65f, 9.2f),
                        bot(77f, 9.2f),
                        ad(71f, 12.2f),
                    ),
                batteries =
                    listOf(
                        MelonBattery(64.5f, 2f, radius = 6.5f, damage = 150f),
                        MelonBattery(77.5f, 2f, radius = 6.5f, damage = 150f),
                    ),
                seeds = listOf(1200, 2300, 3100),
            ),
        )
}

/** Seeds won and the best score on each level, saved on the phone. */
internal data class MelonsProgress(
    val seeds: List<Int> = List(MelonsLevels.all.size) { 0 },
    val best: List<Int> = List(MelonsLevels.all.size) { 0 },
) {
    /** A level opens once the one before it is cleared; the first is always open. */
    fun open(index: Int): Boolean = index == 0 || seeds.getOrElse(index - 1) { 0 } > 0

    fun record(
        index: Int,
        won: Int,
        score: Int,
    ): MelonsProgress =
        copy(
            seeds = seeds.mapIndexed { i, s -> if (i == index) maxOf(s, won) else s },
            best = best.mapIndexed { i, b -> if (i == index) maxOf(b, score) else b },
        )

    fun encode(): String = seeds.joinToString("") + "|" + best.joinToString(",")

    companion object {
        fun decode(text: String?): MelonsProgress {
            val parts = text?.split('|') ?: return MelonsProgress()
            val seeds = parts[0].map { (it - '0').coerceIn(0, 3) }
            val best =
                parts
                    .getOrNull(1)
                    ?.split(',')
                    ?.map { it.toIntOrNull()?.coerceAtLeast(0) ?: 0 }
                    .orEmpty()
            if (seeds.size != MelonsLevels.all.size || best.size != seeds.size) return MelonsProgress()
            return MelonsProgress(seeds, best)
        }
    }
}
