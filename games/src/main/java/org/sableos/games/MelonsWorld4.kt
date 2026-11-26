package org.sableos.games

private val OVERRIPE = MelonKind.Overripe
private val HEAVY = MelonKind.Heavy
private val SPLIT = MelonKind.Split
private val BITTER = MelonKind.Bitter

/**
 * World 4, Storm Citadel, from the owner's level data (docs/bitter-melons/levels.json, 2026-11-25): firewall shields
 * round the structures, drone platforms that hold Slop up until their thrusters break, and the Overripe Melon. As in
 * World 1, where a piece in the data would float or sit inside another, the fix is marked "Added" or "Changed".
 */
internal object MelonsWorld4 {
    val levels: List<MelonLevel> =
        listOf(
            MelonLevel(
                4,
                1,
                "Juice Shockwave",
                "Break the firewall, then pop the Overripe Melon inside.",
                melons = 3,
                maxSpeed = 35f,
                blocks = listOf(steel(64f, 4f, 1.5f, 8f), steel(78f, 4f, 1.5f, 8f), steel(71f, 8.6f, 15f, 1.2f)),
                slop = listOf(prompt(68f, 2.2f), prompt(74f, 2.2f), ad(71f, 5.5f)),
                shields = listOf(MelonShield(71f, 4.5f, 8.5f, hp = 80f)),
                seeds = listOf(900, 1850, 2450),
                queue = listOf(OVERRIPE, OVERRIPE, BITTER),
            ),
            MelonLevel(
                4,
                2,
                "Drone Drop",
                "Shoot out a drone's thrusters to bring it down.",
                melons = 4,
                maxSpeed = 35f,
                blocks =
                    listOf(
                        // Added: steel posts under the beam, which had nothing under it.
                        steel(58.6f, 2.95f, 0.8f, 5.9f),
                        steel(79.4f, 2.95f, 0.8f, 5.9f),
                        steel(69f, 6.5f, 22f, 1.2f),
                    ),
                slop = listOf(prompt(62f, 19.5f), prompt(76f, 19.5f), bot(65f, 2f), bot(73f, 2f)),
                batteries = listOf(MelonBattery(69f, 2f, radius = 6.5f, damage = 150f)),
                drones = listOf(MelonDrone(62f, 18f), MelonDrone(76f, 18f)),
                seeds = listOf(950, 1950, 2600),
                queue = listOf(BITTER, BITTER, BITTER, SPLIT),
            ),
            MelonLevel(
                4,
                3,
                "Firewall Gap",
                "Time your shot through the turning gap.",
                melons = 4,
                maxSpeed = 35f,
                blocks = listOf(stone(68f, 5f, 1.5f, 10f), stone(78f, 5f, 1.5f, 10f)),
                slop = listOf(prompt(71f, 2.2f), prompt(75.5f, 2.2f), ad(73f, 6.5f)),
                batteries = listOf(MelonBattery(73f, 2.2f, radius = 6.5f, damage = 160f)),
                shields = listOf(MelonShield(73f, 5.5f, 9f, gap = 90f, turn = 45f)),
                seeds = listOf(1000, 2050, 2700),
                queue = listOf(BITTER, BITTER, BITTER, OVERRIPE),
            ),
            MelonLevel(
                4,
                4,
                "Sky-High Slop",
                "Ride the fan up to the drone's tower.",
                melons = 4,
                maxSpeed = 35f,
                blocks =
                    listOf(
                        steel(68f, 22f, 1f, 5f),
                        steel(78f, 22f, 1f, 5f),
                        // Changed: the roof rests on the posts' tops (25 m, not 24.8 m inside them).
                        steel(73f, 25f, 11f, 1f),
                    ),
                slop =
                    listOf(
                        prompt(70.5f, 21f),
                        prompt(75.5f, 21f),
                        bot(70f, 2f),
                        bot(76f, 2f),
                        ad(73f, 21.5f),
                    ),
                fans = listOf(MelonFan(46f, 1f, high = 28f, lift = 16.5f)),
                drones = listOf(MelonDrone(73f, 19f, w = 12f)),
                seeds = listOf(1150, 2250, 2950),
                queue = listOf(OVERRIPE, OVERRIPE, HEAVY, HEAVY),
            ),
            MelonLevel(
                4,
                5,
                "The Token Gate",
                "Bounce past the drones and through the gate's gap.",
                melons = 5,
                maxSpeed = 35f,
                blocks = listOf(steel(72f, 5f, 1.5f, 10f), steel(84f, 5f, 1.5f, 10f), steel(78f, 10.6f, 14f, 1.2f)),
                slop = listOf(prompt(75f, 2.2f), prompt(81f, 2.2f), prompt(78f, 5.2f), ad(78f, 8f)),
                batteries = listOf(MelonBattery(78f, 2.2f, radius = 6.5f, damage = 160f)),
                banners = listOf(MelonBanner(52f, 2f, 20f)),
                drones = listOf(MelonDrone(58f, 16f, w = 6f, thrusterHp = 20f), MelonDrone(58f, 9f, w = 6f, thrusterHp = 20f)),
                shields = listOf(MelonShield(78f, 5.5f, 9.5f, gap = 90f, turn = 45f)),
                seeds = listOf(1400, 2650, 3500),
                queue = listOf(SPLIT, OVERRIPE, OVERRIPE, BITTER, BITTER),
            ),
            MelonLevel(
                4,
                6,
                "Ricochet Citadel",
                "Bank off the banners into the gap.",
                melons = 4,
                maxSpeed = 35f,
                blocks = listOf(steel(70f, 4.5f, 1.5f, 9f), steel(84f, 4.5f, 1.5f, 9f), steel(77f, 9.6f, 15f, 1.2f)),
                slop = listOf(prompt(74f, 2.2f), prompt(80f, 2.2f), ad(77f, 6.5f)),
                batteries = listOf(MelonBattery(77f, 2.2f, radius = 6.5f, damage = 160f)),
                banners = listOf(MelonBanner(52f, 3f, 35f, w = 7.5f), MelonBanner(88f, 10f, 90f, w = 8f)),
                shields = listOf(MelonShield(77f, 5f, 9f, gap = 90f, turn = 45f)),
                seeds = listOf(1150, 2200, 2950),
                queue = listOf(BITTER, BITTER, OVERRIPE, OVERRIPE),
            ),
            MelonLevel(
                4,
                7,
                "Drone Fleet",
                "Bring the stacked drones down on the gold Ad-Slop.",
                melons = 4,
                maxSpeed = 35f,
                blocks = listOf(steel(66f, 4f, 1.5f, 8f), steel(78f, 4f, 1.5f, 8f), steel(72f, 8.6f, 14f, 1.2f)),
                slop = listOf(prompt(72f, 25.5f), prompt(72f, 18.5f), prompt(72f, 11.5f), ad(72f, 5.5f)),
                batteries = listOf(MelonBattery(72f, 2.2f, radius = 6.5f, damage = 160f)),
                // Changed: the top drone flies at 23 m, not 24, so its Prompt Bot shows on screen.
                drones = listOf(MelonDrone(72f, 23f), MelonDrone(72f, 17f), MelonDrone(72f, 10.5f)),
                seeds = listOf(1250, 2450, 3250),
                queue = listOf(HEAVY, HEAVY, BITTER, BITTER),
            ),
            MelonLevel(
                4,
                8,
                "The Prompt Gauntlet",
                "Over the wall on the fan, then through the gap.",
                melons = 5,
                maxSpeed = 35f,
                blocks =
                    listOf(
                        steel(56f, 8f, 2.5f, 16f, fixed = true),
                        steel(72f, 5f, 1.5f, 10f),
                        steel(86f, 5f, 1.5f, 10f),
                        steel(79f, 10.6f, 15f, 1.2f),
                    ),
                slop = listOf(prompt(75f, 2.2f), prompt(83f, 2.2f), prompt(79f, 5.2f), ad(79f, 8f)),
                batteries =
                    listOf(
                        MelonBattery(76.5f, 2.2f, radius = 6.5f, damage = 160f),
                        MelonBattery(81.5f, 2.2f, radius = 6.5f, damage = 160f),
                    ),
                fans = listOf(MelonFan(46f, 1f, high = 28f, lift = 16.5f)),
                shields = listOf(MelonShield(79f, 5.5f, 9.5f, gap = 90f, turn = 45f)),
                seeds = listOf(1500, 2750, 3650),
                queue = listOf(OVERRIPE, OVERRIPE, HEAVY, SPLIT, BITTER),
            ),
            MelonLevel(
                4,
                9,
                "The Vibe-Code Overlord",
                "The last boss: every trick you have learned.",
                melons = 6,
                maxSpeed = 36f,
                blocks =
                    listOf(
                        // Changed: the data's posts hung 9 m up and the roof did not reach them. The citadel is two
                        // floors on posts from the ground: a floor at 9 m for the Prompt Bots and the roof at 20 m.
                        steel(68f, 3.9f, 1.5f, 7.8f),
                        steel(84f, 3.9f, 1.5f, 7.8f),
                        steel(76f, 8.4f, 17.5f, 1.2f),
                        steel(68f, 14.125f, 1.5f, 10.25f),
                        steel(84f, 14.125f, 1.5f, 10.25f),
                        steel(76f, 20f, 17.5f, 1.5f),
                    ),
                slop =
                    listOf(
                        prompt(62f, 8f),
                        prompt(71.5f, 11f),
                        prompt(80.5f, 11f),
                        bot(62f, 2f),
                        bot(88f, 2f),
                        ad(76f, 12f),
                    ),
                batteries = listOf(MelonBattery(76f, 6f, radius = 7f, damage = 180f)),
                fans = listOf(MelonFan(46f, 1f, high = 28f)),
                // Changed: the drones fly at 23 m and the beams hang at 25 m, not 24 and 28, so they show on screen
                // (the view reaches 26 m). The stage stays 100 x 40 m like the others, not 105 x 42.
                drones = listOf(MelonDrone(64f, 23f, w = 9f), MelonDrone(86f, 23f, w = 9f)),
                hanging = listOf(MelonHangingBeam(70f, 25f), MelonHangingBeam(82f, 25f)),
                shields = listOf(MelonShield(76f, 12f, 10f, gap = 90f, turn = 45f)),
                seeds = listOf(2200, 3800, 5000),
                queue = listOf(SPLIT, HEAVY, OVERRIPE, OVERRIPE, BITTER, BITTER),
            ),
        )
}
