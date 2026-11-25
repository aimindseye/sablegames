package org.sableos.games

/**
 * The Bricks walls, as approved (owner, 2026-11-25): four worlds of five levels. Each wall is a text map,
 * up to [BricksGame.ROWS] rows of [BricksGame.COLUMNS] cells:
 * `.` empty, `n` a plain brick, `2` and `3` bricks that take that many hits, `s` steel (never breaks),
 * `x` explosive (clears its neighbours).
 */
internal data class BricksLevel(
    val world: Int,
    val number: Int,
    val name: String,
    val rows: List<String>,
    /** World 4: odd rows slide side to side. */
    val sliding: Boolean = false,
    /** World 4: the wall drops one row this often, in seconds; 0 never. */
    val ceilingSeconds: Float = 0f,
) {
    /** The level's place in [BricksLevels.all], from 0. */
    val index: Int get() = (world - 1) * BricksLevels.PER_WORLD + number - 1
}

internal object BricksLevels {
    const val PER_WORLD = 5

    val worlds =
        listOf(
            "Shapes" to "Plain bricks in shaped walls. Learn the angles.",
            "Tough bricks" to "Bricks that take 2 or 3 hits.",
            "Steel" to "Unbreakable steel and explosive bricks.",
            "Moving walls" to "Rows slide and the ceiling drops.",
        )

    val all: List<BricksLevel> =
        listOf(
            BricksLevel(
                1,
                1,
                "Wall",
                listOf("nnnnnnnnnn", "nnnnnnnnnn", "nnnnnnnnnn", "nnnnnnnnnn", "nnnnnnnnnn", "nnnnnnnnnn"),
            ),
            BricksLevel(
                1,
                2,
                "Arch",
                listOf("nnnnnnnnnn", "nnn....nnn", "nn......nn", "nn......nn", "nn......nn", "nnnnnnnnnn"),
            ),
            BricksLevel(
                1,
                3,
                "Steps",
                listOf("n.........", "nn........", "nnn.......", "nnnn......", "nnnnn.....", "nnnnnn....", "nnnnnnn...", "nnnnnnnn.."),
            ),
            BricksLevel(
                1,
                4,
                "Diamond",
                listOf("....nn....", "...nnnn...", "..nnnnnn..", ".nnnnnnnn.", ".nnnnnnnn.", "..nnnnnn..", "...nnnn...", "....nn...."),
            ),
            BricksLevel(
                1,
                5,
                "Heart",
                listOf(".nn....nn.", "nnnn..nnnn", "nnnnnnnnnn", "nnnnnnnnnn", ".nnnnnnnn.", "..nnnnnn..", "...nnnn...", "....nn...."),
            ),
            BricksLevel(
                2,
                1,
                "Layers",
                listOf("2222222222", "nnnnnnnnnn", "2222222222", "nnnnnnnnnn", "2222222222", "nnnnnnnnnn"),
            ),
            BricksLevel(
                2,
                2,
                "Chequer",
                listOf("2.2.2.2.2.", ".n.n.n.n.n", "2.2.2.2.2.", ".n.n.n.n.n", "2.2.2.2.2.", ".n.n.n.n.n"),
            ),
            BricksLevel(
                2,
                3,
                "Crown",
                listOf("3...33...3", "33.3333.33", "3333333333", "2222222222", "nnnnnnnnnn", "nnnnnnnnnn"),
            ),
            BricksLevel(
                2,
                4,
                "Fortress",
                listOf("2222222222", "2nnnnnnnn2", "2n333333n2", "2n333333n2", "2nnnnnnnn2", "2222..2222"),
            ),
            BricksLevel(
                2,
                5,
                "Hive",
                listOf(".3.3.3.3.3", "2222222222", "3.3.3.3.3.", "2222222222", ".3.3.3.3.3", "nnnnnnnnnn", "nnnnnnnnnn"),
            ),
            BricksLevel(
                3,
                1,
                "Bars",
                listOf("nnnnnnnnnn", "s.s.s.s.s.", "nnnnnnnnnn", ".s.s.s.s.s", "nnnnnnnnnn", "2222222222"),
            ),
            BricksLevel(
                3,
                2,
                "Maze",
                listOf("ssssssssss", "snnnnsnnns", "snssnsnsns", "snnsnnnsns", "sssnsssnns", "nnnnnnnnnn", "2222222222"),
            ),
            BricksLevel(
                3,
                3,
                "Cage",
                listOf("ssss..ssss", "s3333333s.", "s3xnnnx3s.", "s3nnnnn3s.", "s3333333s.", "ssssssss.."),
            ),
            BricksLevel(
                3,
                4,
                "Chain",
                listOf("nnnnnnnnnn", "nxnnxnnxnn", "nnnnnnnnnn", "ssnnssnnss", "2222222222", "nnxnnnnxnn"),
            ),
            BricksLevel(
                3,
                5,
                "Vault",
                listOf("ssssssssss", "s33xx33x3s", "s3333333.s", "s222222222", "s.nnnnnnns", "ss......ss"),
            ),
            BricksLevel(
                4,
                1,
                "Drift",
                listOf("nnnnnnnnnn", "nnnnnnnnnn", "2222222222", "nnnnnnnnnn", "nnnnnnnnnn"),
                sliding = true,
            ),
            BricksLevel(
                4,
                2,
                "Tide",
                listOf("nnnnnnnnnn", "2222222222", "nnnnnnnnnn", "3333333333", "nnnnnnnnnn"),
                ceilingSeconds = 30f,
            ),
            BricksLevel(
                4,
                3,
                "Zipper",
                listOf("nnnnn.....", ".....nnnnn", "22222.....", ".....22222", "nnnnn.....", ".....nnnnn"),
                sliding = true,
            ),
            BricksLevel(
                4,
                4,
                "Pistons",
                listOf("s.s.s.s.s.", "nnnnnnnnnn", "2x22222x22", "nnnnnnnnnn", "3333333333"),
                sliding = true,
                ceilingSeconds = 35f,
            ),
            BricksLevel(
                4,
                5,
                "Finale",
                listOf("3333333333", "3xx3333xx3", "2222222222", "snnnnnnnns", "2222222222", "nnnnnnnnnn", "nnnnnnnnnn"),
                sliding = true,
                ceilingSeconds = 30f,
            ),
        )
}
