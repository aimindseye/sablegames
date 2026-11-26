package org.sableos.games

private val SPLIT = MelonKind.Split
private val BITTER = MelonKind.Bitter

/**
 * World 2, Slop Mall, from the owner's level data (docs/bitter-melons/levels.json, 2026-11-25): gold AD banners
 * that throw a melon back harder than it came, and the Split Cantaloupe. As in World 1, where a piece in the data
 * would float or sit inside another, the fix is marked "Added" or "Changed" with the reason.
 */
internal object MelonsWorld2 {
    val levels: List<MelonLevel> =
        listOf(
            MelonLevel(
                2,
                1,
                "Pop-Up Ads",
                "Bounce off the gold AD banner to vault in.",
                melons = 3,
                maxSpeed = 34f,
                blocks =
                    listOf(
                        // Changed: the glass posts stand on the ground (y 5, not 6) and the roof sits on them.
                        glass(67f, 5f, 1f, 10f),
                        glass(75f, 5f, 1f, 10f),
                        glass(71f, 10.4f, 9f, 0.8f),
                    ),
                slop = listOf(app(71f, 3f), app(71f, 6f), bot(71f, 9f)),
                banners = listOf(MelonBanner(48f, 2f, 20f)),
                seeds = listOf(450, 1150, 1550),
            ),
            MelonLevel(
                2,
                2,
                "Triple Split",
                "Press Space in the air to split the Cantaloupe in three.",
                melons = 3,
                maxSpeed = 34f,
                blocks =
                    // Changed: each 7 x 5 m glass case is two walls and a roof, so the slop fits inside.
                    listOf(58f, 68f, 78f).flatMap { x ->
                        listOf(
                            glass(x - 3.3f, 2.3f, 0.4f, 4.6f),
                            glass(x + 3.3f, 2.3f, 0.4f, 4.6f),
                            glass(x, 4.8f, 7f, 0.4f),
                        )
                    },
                slop = listOf(app(58f, 2f), app(78f, 2f), bot(68f, 2f)),
                seeds = listOf(500, 1200, 1600),
                queue = listOf(SPLIT, SPLIT, BITTER),
            ),
            MelonLevel(
                2,
                3,
                "Paywall Atrium",
                "Bank off the wall banner, or set off the battery.",
                melons = 4,
                maxSpeed = 35f,
                blocks = listOf(stone(69f, 12f, 16f, 2f, fixed = true), glass(63f, 3f, 1f, 6f)),
                slop = listOf(bot(66.5f, 2f), bot(72.5f, 2f)),
                batteries = listOf(MelonBattery(69.5f, 2f)),
                banners = listOf(MelonBanner(82f, 6f, 90f, w = 8f)),
                seeds = listOf(550, 1400, 1950),
                queue = listOf(BITTER, BITTER, BITTER, SPLIT),
            ),
            MelonLevel(
                2,
                4,
                "Review Bombed",
                "Topple the tall tower onto the glass case.",
                melons = 4,
                maxSpeed = 35f,
                blocks =
                    listOf(
                        wood(64f, 8f, 1.2f, 16f),
                        glass(68.5f, 8f, 1f, 16f),
                        // Changed: the 5 x 5 m glass case is two walls and a roof, so the bot fits inside.
                        glass(75.7f, 2.3f, 0.4f, 4.6f),
                        glass(80.3f, 2.3f, 0.4f, 4.6f),
                        glass(78f, 4.8f, 5f, 0.4f),
                    ),
                slop =
                    listOf(
                        app(66.2f, 14f),
                        app(66.2f, 11f),
                        bot(66.2f, 8f),
                        bot(66.2f, 5f),
                        bot(78f, 2f),
                    ),
                batteries = listOf(MelonBattery(66.2f, 2f, radius = 6.5f, damage = 150f)),
                seeds = listOf(650, 1600, 2200),
                queue = listOf(SPLIT, SPLIT, BITTER, BITTER),
            ),
            MelonLevel(
                2,
                5,
                "Flash Sale",
                "Break the gold Ad-Slop: blocks then score double.",
                melons = 4,
                maxSpeed = 35f,
                blocks =
                    listOf(
                        // Changed: the post reaches the 18 m glass shelf, which had only an 8 m post under it.
                        stone(62f, 6.75f, 2f, 13.5f, fixed = true),
                        // Added: a glass post under the shelf's far end.
                        glass(78f, 6.75f, 1f, 13.5f),
                        glass(70f, 14f, 18f, 1f),
                    ),
                slop =
                    listOf(
                        app(65f, 15.5f),
                        app(75f, 15.5f),
                        bot(68f, 2f),
                        bot(76f, 2f),
                        ad(70f, 16f),
                    ),
                batteries = listOf(MelonBattery(72f, 2f)),
                banners = listOf(MelonBanner(52f, 2f, 25f)),
                seeds = listOf(1000, 1950, 2600),
                queue = listOf(SPLIT, SPLIT, BITTER, BITTER),
            ),
            MelonLevel(
                2,
                6,
                "Pinball Promo",
                "Ricochet off the banners down onto the battery.",
                melons = 3,
                maxSpeed = 35f,
                blocks =
                    listOf(
                        // Changed: the roof rests on the wall's top (13.75 m, not 13 m inside it).
                        stone(75f, 13.75f, 14f, 1.5f, fixed = true),
                        stone(82f, 6.5f, 1.5f, 13f, fixed = true),
                        glass(68.5f, 4f, 1f, 8f),
                    ),
                slop = listOf(bot(74f, 2f), bot(79f, 2f)),
                batteries = listOf(MelonBattery(76.5f, 2f, radius = 6.5f, damage = 150f)),
                banners = listOf(MelonBanner(48f, 18f, -35f), MelonBanner(56f, 4f, 40f)),
                seeds = listOf(550, 1250, 1650),
            ),
            MelonLevel(
                2,
                7,
                "Free Trial Trap",
                "Shred the glass skylights from above.",
                melons = 4,
                maxSpeed = 35f,
                blocks =
                    listOf(
                        // Added: posts at 50 and 86 m, and the skylights are 12.6 m wide to rest on the posts'
                        // tops (7.4 m up); the data's 8 m skylights did not reach the posts.
                        stone(50f, 3.5f, 2f, 7f, fixed = true),
                        stone(62f, 3.5f, 2f, 7f, fixed = true),
                        stone(74f, 3.5f, 2f, 7f, fixed = true),
                        stone(86f, 3.5f, 2f, 7f, fixed = true),
                        glass(56f, 7.4f, 12.6f, 0.8f),
                        glass(68f, 7.4f, 12.6f, 0.8f),
                        glass(80f, 7.4f, 12.6f, 0.8f),
                    ),
                slop = listOf(app(56f, 2f), app(68f, 2f), app(80f, 2f), bot(58f, 2f), bot(82f, 2f)),
                seeds = listOf(700, 1550, 2150),
                queue = listOf(SPLIT, SPLIT, BITTER, BITTER),
            ),
            MelonLevel(
                2,
                8,
                "Subscription Wall",
                "Dive off the ceiling banner behind the wall.",
                melons = 4,
                maxSpeed = 35f,
                blocks =
                    listOf(
                        stone(56f, 8f, 2.5f, 16f, fixed = true),
                        // Added: glass posts under the 7 m shelf, which had nothing under it; it is 16 m wide to
                        // reach them.
                        glass(64.4f, 3.3f, 0.8f, 6.6f),
                        glass(79.6f, 3.3f, 0.8f, 6.6f),
                        glass(72f, 7f, 16f, 0.8f),
                    ),
                slop = listOf(app(76f, 2f), bot(66.5f, 2f), bot(73.5f, 2f)),
                batteries = listOf(MelonBattery(70f, 2f, radius = 6.5f, damage = 150f)),
                // Changed: the ceiling banner hangs 24 m up, not 28, so it shows on screen (the view reaches 26 m).
                banners = listOf(MelonBanner(60f, 24f, 180f, w = 9f)),
                seeds = listOf(650, 1500, 2100),
                queue = listOf(BITTER, BITTER, BITTER, SPLIT),
            ),
            MelonLevel(
                2,
                9,
                "The Mega-Mall",
                "The mall boss: blast the ground floor, then the top.",
                melons = 5,
                maxSpeed = 35f,
                blocks =
                    listOf(
                        stone(60f, 3.5f, 1.5f, 7f),
                        stone(72f, 3.5f, 1.5f, 7f),
                        stone(84f, 3.5f, 1.5f, 7f),
                        glass(72f, 7.5f, 26f, 1f),
                        // Added: glass posts under the top floor, which had nothing under it.
                        glass(65.4f, 11.25f, 0.6f, 6.5f),
                        glass(78.6f, 11.25f, 0.6f, 6.5f),
                        glass(72f, 15f, 14f, 1f),
                    ),
                slop =
                    listOf(
                        app(64f, 9.2f),
                        app(80f, 9.2f),
                        bot(67f, 9.2f),
                        bot(72f, 9.2f),
                        bot(77f, 9.2f),
                        ad(72f, 12.2f),
                    ),
                batteries =
                    listOf(
                        MelonBattery(66f, 2f, radius = 6.5f, damage = 150f),
                        MelonBattery(78f, 2f, radius = 6.5f, damage = 150f),
                    ),
                banners = listOf(MelonBanner(50f, 2f, 20f)),
                seeds = listOf(1400, 2600, 3400),
                queue = listOf(SPLIT, SPLIT, BITTER, BITTER, BITTER),
            ),
        )
}
