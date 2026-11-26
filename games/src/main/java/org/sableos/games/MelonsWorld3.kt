package org.sableos.games

private val HEAVY = MelonKind.Heavy
private val SPLIT = MelonKind.Split
private val BITTER = MelonKind.Bitter

/**
 * World 3, Server Foundry, from the owner's level data (docs/bitter-melons/levels.json, 2026-11-25): steel, the
 * orange Prompt Bot, cooling fans that lift a melon, steel beams hung on cables, and the Heavy Honeydew. As in World
 * 1, where a piece in the data would float or sit inside another, the fix is marked "Added" or "Changed".
 */
internal object MelonsWorld3 {
    val levels: List<MelonLevel> =
        listOf(
            MelonLevel(
                3,
                1,
                "Heavy Slam",
                "The Honeydew is heavy: press Space to slam it down.",
                melons = 3,
                maxSpeed = 35f,
                blocks = listOf(steel(62f, 3.5f, 1.5f, 7f), steel(74f, 3.5f, 1.5f, 7f), steel(68f, 7.6f, 14f, 1.2f)),
                // Changed: the AI Bot stands on the floor between the Prompt Bots; nothing held it 4.8 m up.
                slop = listOf(prompt(65.5f, 2.2f), prompt(70.5f, 2.2f), bot(68f, 2f)),
                seeds = listOf(650, 1400, 1850),
                queue = listOf(HEAVY, HEAVY, BITTER),
            ),
            MelonLevel(
                3,
                2,
                "Cooling Updraft",
                "Fly through the fan's updraft to clear the wall.",
                melons = 4,
                maxSpeed = 35f,
                blocks =
                    listOf(
                        steel(56f, 9f, 2.5f, 18f, fixed = true),
                        // Added: wood posts under the shelf, which had nothing under it.
                        wood(65.6f, 3.25f, 1.2f, 6.5f),
                        wood(78.4f, 3.25f, 1.2f, 6.5f),
                        wood(72f, 7f, 14f, 1f),
                    ),
                slop = listOf(prompt(68f, 2.2f), prompt(76f, 2.2f), bot(69f, 8.8f), bot(75f, 8.8f)),
                fans = listOf(MelonFan(46f, 1f)),
                seeds = listOf(750, 1650, 2250),
                queue = listOf(BITTER, BITTER, BITTER, HEAVY),
            ),
            MelonLevel(
                3,
                3,
                "Cable Snap",
                "Hit a hanging beam's cables to drop it.",
                melons = 3,
                maxSpeed = 35f,
                blocks = listOf(wood(66f, 3f, 1.2f, 6f), wood(78f, 3f, 1.2f, 6f)),
                slop = listOf(prompt(69f, 2.2f), prompt(75f, 2.2f), app(69f, 4.8f), app(75f, 4.8f)),
                batteries = listOf(MelonBattery(72f, 2f, radius = 6.5f, damage = 150f)),
                hanging = listOf(MelonHangingBeam(68f, 24f), MelonHangingBeam(76f, 24f)),
                seeds = listOf(700, 1550, 2100),
            ),
            MelonLevel(
                3,
                4,
                "Thermal Throttle",
                "Ride the fan, then slam down through the racks.",
                melons = 4,
                maxSpeed = 35f,
                blocks =
                    listOf(
                        // Added: steel posts under both racks, which had nothing under them.
                        steel(67.6f, 3.2f, 0.8f, 6.4f),
                        steel(78.4f, 3.2f, 0.8f, 6.4f),
                        steel(73f, 7f, 12f, 1.2f),
                        steel(67.6f, 10.5f, 0.8f, 5.8f),
                        steel(78.4f, 10.5f, 0.8f, 5.8f),
                        steel(73f, 14f, 12f, 1.2f),
                    ),
                slop = listOf(prompt(70f, 9f), prompt(76f, 9f), bot(76f, 2f)),
                batteries = listOf(MelonBattery(70f, 2f, radius = 6.5f, damage = 160f)),
                fans = listOf(MelonFan(50f, 1f)),
                seeds = listOf(800, 1750, 2350),
                queue = listOf(HEAVY, HEAVY, BITTER, BITTER),
            ),
            MelonLevel(
                3,
                5,
                "Overclocked Rack",
                "Drop the beam on the glass, then crack the rack.",
                melons = 5,
                maxSpeed = 35f,
                blocks =
                    listOf(
                        // Changed: the 8 x 7 m glass case is two walls and a roof, so the bots fit inside.
                        glass(54.2f, 3.3f, 0.4f, 6.6f),
                        glass(61.8f, 3.3f, 0.4f, 6.6f),
                        glass(58f, 6.8f, 8f, 0.4f),
                        steel(74f, 5f, 1.5f, 10f),
                        steel(86f, 5f, 1.5f, 10f),
                        steel(80f, 10.6f, 14f, 1.2f),
                    ),
                slop = listOf(bot(56f, 2f), bot(60f, 2f), prompt(77.5f, 2.2f), prompt(82.5f, 2.2f), ad(80f, 6.5f)),
                batteries = listOf(MelonBattery(80f, 2.2f, radius = 6.5f, damage = 160f)),
                hanging = listOf(MelonHangingBeam(58f, 22f)),
                seeds = listOf(1250, 2400, 3200),
                queue = listOf(HEAVY, HEAVY, SPLIT, BITTER, BITTER),
            ),
            MelonLevel(
                3,
                6,
                "Crosswind Corridor",
                "Two fans: ride them over both walls.",
                melons = 4,
                maxSpeed = 35f,
                blocks =
                    listOf(
                        steel(48f, 7f, 2f, 14f, fixed = true),
                        steel(66f, 7f, 2f, 14f, fixed = true),
                        // Added: steel posts under the rack, which had nothing under it.
                        steel(74.6f, 3.95f, 0.8f, 7.9f),
                        steel(85.4f, 3.95f, 0.8f, 7.9f),
                        steel(80f, 8.5f, 12f, 1.2f),
                    ),
                slop = listOf(prompt(77.5f, 2.2f), prompt(82.5f, 2.2f), bot(80f, 5.2f)),
                batteries = listOf(MelonBattery(80f, 2.2f, radius = 6.5f, damage = 150f)),
                fans = listOf(MelonFan(38f, 1f, high = 24f, lift = 15f), MelonFan(58f, 1f, high = 24f, lift = 15f)),
                seeds = listOf(800, 1750, 2350),
                queue = listOf(BITTER, BITTER, BITTER, HEAVY),
            ),
            MelonLevel(
                3,
                7,
                "Suspended Load",
                "Snap the heavy beam onto the stacked racks.",
                melons = 4,
                maxSpeed = 35f,
                blocks =
                    listOf(
                        // Added: steel posts under both racks, which had nothing under them.
                        steel(64.4f, 3.45f, 0.8f, 6.9f),
                        steel(77.6f, 3.45f, 0.8f, 6.9f),
                        steel(71f, 7.5f, 14f, 1.2f),
                        steel(64.4f, 9.25f, 0.8f, 2.3f),
                        steel(77.6f, 9.25f, 0.8f, 2.3f),
                        steel(71f, 11f, 14f, 1.2f),
                    ),
                slop =
                    listOf(
                        prompt(67f, 2.2f),
                        prompt(71f, 2.2f),
                        prompt(75f, 2.2f),
                        bot(68.5f, 5f),
                        bot(73.5f, 5f),
                    ),
                hanging = listOf(MelonHangingBeam(71f, 20f, cableHp = 15f, massFactor = 2.5f)),
                seeds = listOf(950, 1950, 2600),
                queue = listOf(HEAVY, HEAVY, BITTER, BITTER),
            ),
            MelonLevel(
                3,
                8,
                "Data Center Maze",
                "Ride the fan into the corridor and drop in at the end.",
                melons = 4,
                maxSpeed = 35f,
                blocks =
                    listOf(
                        // Changed: the roof rests on the wall's top (20.75 m, not 20.5 m inside it).
                        steel(70f, 20.75f, 28f, 1.5f, fixed = true),
                        steel(68f, 16.5f, 22f, 1.5f, fixed = true),
                        steel(83f, 10f, 2f, 20f, fixed = true),
                    ),
                slop = listOf(prompt(72f, 2.2f), prompt(80f, 2.2f), prompt(76f, 5f), app(73f, 5f), app(79f, 5f)),
                batteries = listOf(MelonBattery(76f, 2.2f, radius = 7f, damage = 170f)),
                fans = listOf(MelonFan(48f, 1f, high = 25f)),
                seeds = listOf(950, 1900, 2550),
                queue = listOf(BITTER, BITTER, BITTER, SPLIT),
            ),
            MelonLevel(
                3,
                9,
                "The Mainframe",
                "The foundry boss: fans, beams and a gold Ad-Slop.",
                melons = 5,
                maxSpeed = 36f,
                blocks =
                    listOf(
                        steel(56f, 7f, 2f, 14f, fixed = true),
                        steel(68f, 6f, 1.5f, 12f),
                        steel(84f, 6f, 1.5f, 12f),
                        steel(76f, 12.6f, 18f, 1.2f),
                    ),
                slop =
                    listOf(
                        prompt(64f, 2.2f),
                        prompt(72f, 2.2f),
                        prompt(80f, 2.2f),
                        bot(64f, 5f),
                        bot(88f, 2f),
                        ad(76f, 8f),
                    ),
                batteries =
                    listOf(
                        MelonBattery(72f, 5f, radius = 6.5f, damage = 160f),
                        MelonBattery(80f, 5f, radius = 6.5f, damage = 160f),
                    ),
                fans = listOf(MelonFan(48f, 1f)),
                hanging = listOf(MelonHangingBeam(66f, 25f), MelonHangingBeam(84f, 25f)),
                seeds = listOf(1600, 2900, 3800),
                queue = listOf(HEAVY, HEAVY, SPLIT, BITTER, BITTER),
            ),
        )
}
