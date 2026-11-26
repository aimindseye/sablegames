package org.sableos.games

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.jbox2d.common.Vec2
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Test

class MelonsWorldTest {
    private fun level(id: String) = MelonsLevels.all.first { it.id == id }

    /** Fires each (angle, power) shot in turn, waiting for the world to settle between them. */
    private fun play(
        level: MelonLevel,
        shots: List<Pair<Float, Float>>,
    ): MelonsWorld {
        val world = MelonsWorld(level)
        for ((angle, power) in shots) {
            if (world.phase != MelonPhase.Aiming) break
            world.setAim(angle, power)
            assertTrue(world.launch())
            settle(world)
        }
        return world
    }

    private fun settle(world: MelonsWorld) {
        var steps = 0
        while (world.phase == MelonPhase.Flying && steps < MAX_STEPS) {
            world.tick()
            steps++
        }
    }

    @Test
    fun everyLevelStandsStillUntilItIsHit() {
        for (level in MelonsLevels.all) {
            val world = MelonsWorld(level)
            val pieces = world.pieces.size
            repeat(IDLE_STEPS) { world.tick() }
            assertEquals("${level.id} lost a piece on its own", pieces, world.pieces.size)
            assertEquals("${level.id} scored on its own", 0, world.score)
            assertEquals(level.slop.size, world.slopLeft)
        }
    }

    @Test
    fun everyLevelCanBeCleared() {
        // Shots found by searching every angle and power; the world is deterministic.
        val plans =
            mapOf(
                "1-1" to listOf(4f to 82f),
                "1-2" to listOf(7f to 100f),
                "1-3" to listOf(25f to 82f),
                "1-4" to listOf(4f to 100f),
                "1-5" to listOf(4f to 98f),
                "1-6" to listOf(16f to 100f),
                "1-7" to listOf(43f to 64f),
                "1-8" to listOf(25f to 88f, 10f to 100f, 16f to 100f),
                "1-9" to listOf(13f to 94f),
                "2-1" to listOf(10f to 96f),
                "2-2" to listOf(10f to 96f),
                "2-3" to listOf(0f to 84f),
                "2-4" to listOf(8f to 92f, 0f to 92f),
                "2-5" to listOf(14f to 80f),
                "2-6" to listOf(16f to 96f),
                "2-7" to listOf(24f to 76f, 22f to 72f, 16f to 100f),
                "2-8" to listOf(60f to 72f),
                "2-9" to listOf(20f to 80f),
                "3-1" to listOf(14f to 96f),
                "3-2" to listOf(60f to 72f),
                "3-3" to listOf(18f to 84f),
                "3-4" to listOf(46f to 64f),
                "3-5" to listOf(8f to 100f),
                "3-6" to listOf(70f to 88f),
                "3-7" to listOf(0f to 100f),
                "3-8" to listOf(0f to 80f),
                "3-9" to listOf(62f to 72f),
                "4-1" to listOf(22f to 88f),
                "4-2" to listOf(38f to 80f),
                "4-3" to listOf(30f to 80f),
                "4-4" to listOf(34f to 88f),
                "4-5" to listOf(36f to 72f),
                "4-6" to listOf(20f to 96f),
                "4-7" to listOf(40f to 84f),
                // The firewall turns while you aim, so the shots before the last set when it goes through the gap.
                "4-8" to listOf(0f to 20f, 72f to 100f, 64f to 84f, 50f to 68f),
                "4-9" to listOf(24f to 96f),
            )
        assertEquals(MelonsLevels.all.map { it.id }.toSet(), plans.keys)
        for (level in MelonsLevels.all) {
            val world = play(level, plans.getValue(level.id))
            assertEquals("${level.id} not cleared", MelonPhase.Cleared, world.phase)
            assertTrue(world.seeds >= 1)
            // Each melon left over is worth 500.
            assertTrue(world.score >= world.melonsLeft * MelonsWorld.UNUSED_MELON_BONUS)
        }
    }

    @Test
    fun aMissUsesAMelonAndTheLastMissFails() {
        val level = level("1-7")
        val world = play(level, List(level.melons) { 0f to 20f })
        assertEquals(0, world.melonsLeft)
        assertEquals(MelonPhase.Failed, world.phase)
        assertFalse(world.launch())
        assertEquals(0, world.seeds)
    }

    @Test
    fun slopIsSetDownOnWhatIsUnderIt() {
        val world = MelonsWorld(level("1-1"))
        val apps = world.pieces.filter { it.kind == PieceKind.Slop }.sortedBy { it.y }
        // The beam's top is at 9 m; the apps stand on it, one on the other.
        assertEquals(9f + SlopKind.App.h / 2, apps[0].y, 0.1f)
        assertEquals(9f + SlopKind.App.h * 1.5f, apps[1].y, 0.15f)
    }

    @Test
    fun theSeedBurstFiresThreeSeedsOnce() {
        val world = MelonsWorld(level("1-2"))
        assertFalse(world.ability())
        world.setAim(40f, 60f)
        world.launch()
        repeat(BURST_STEPS) { world.tick() }
        assertTrue(world.ability())
        assertEquals(3, world.pieces.count { it.kind == PieceKind.Seed })
        assertFalse(world.ability())
    }

    @Test
    fun theAimedLandingIsOnTheStructureOrTheGround() {
        // 1-7's planned lob comes down on the bunker.
        val world = MelonsWorld(level("1-7"))
        world.setAim(43f, 64f)
        val (time, x, _) = world.landing()
        assertTrue(time > 0f)
        assertTrue("lands at $x", x >= world.level.fortX - MelonsWorld.MELON_R && x <= world.level.fortEnd)
        // A weak shot comes down on the ground well short of the structure.
        world.setAim(0f, 20f)
        val (_, shortX, shortY) = world.landing()
        assertTrue(shortX < world.level.fortX)
        assertEquals(MelonsWorld.MELON_R, shortY, 0.01f)
    }

    @Test
    fun theSplitCantaloupeSplitsIntoThreeOnce() {
        val world = MelonsWorld(level("2-2"))
        assertEquals(MelonKind.Split, world.loaded)
        world.setAim(40f, 60f)
        world.launch()
        repeat(BURST_STEPS) { world.tick() }
        assertTrue(world.ability())
        // The melon is gone and three smaller ones fly on, the camera following the middle one.
        assertEquals(0, world.pieces.count { it.kind == PieceKind.Melon })
        val parts = world.pieces.filter { it.kind == PieceKind.Seed && it.melonKind == MelonKind.Split }
        assertEquals(3, parts.size)
        assertTrue(world.melon in parts)
        assertFalse(world.ability())
        // The queue moves on: another cantaloupe, then a Bitter Melon.
        settle(world)
        assertEquals(MelonKind.Split, world.loaded)
        assertEquals(listOf(MelonKind.Bitter), world.waiting)
    }

    @Test
    fun aBannerThrowsTheMelonBackHarder() {
        // A melon dropped straight onto 2-1's banner comes back up faster than a stone floor would send it.
        val world = MelonsWorld(level("2-1"))
        val banner = world.pieces.single { it.kind == PieceKind.Banner }
        assertTrue(banner.fixed)
        world.setAim(0f, 20f)
        world.launch()
        val melon = world.melon!!
        melon.body.setTransform(Vec2(banner.x, banner.y + 4f), 0f)
        melon.body.linearVelocity = Vec2(0f, -12f)
        var up = 0f
        repeat(BOUNCE_STEPS) {
            world.tick()
            up = maxOf(up, melon.body.linearVelocity.y)
        }
        assertTrue("came off at $up m/s", up > 12f)
        assertTrue(world.pieces.any { it.kind == PieceKind.Banner })
    }

    @Test
    fun theAimedLandingMeetsATurnedBanner() {
        // 2-1's banner lies at 20°, 48 m out: some aims come down on the turned plate, above the ground.
        val world = MelonsWorld(level("2-1"))
        val onPlate =
            (0..40).any { angle ->
                (20..100).any { power ->
                    world.setAim(angle.toFloat(), power.toFloat())
                    val (_, x, y) = world.landing()
                    x in 45f..51f && y > MelonsWorld.MELON_R + 1f
                }
            }
        assertTrue("no shot meets the banner", onPlate)
    }

    @Test
    fun eachWorldHasNineLevelsAndItsNewMelon() {
        assertEquals(4, MelonsLevels.worlds.size)
        for (n in 1..4) assertEquals(MelonsLevels.PER_WORLD, MelonsLevels.world(n).size)
        assertTrue(MelonsLevels.world(1).all { level -> level.queue.all { it == MelonKind.Bitter } })
        assertTrue(MelonsLevels.world(2).any { level -> MelonKind.Split in level.queue })
        assertTrue(MelonsLevels.world(3).any { level -> MelonKind.Heavy in level.queue })
        assertTrue(MelonsLevels.world(4).any { level -> MelonKind.Overripe in level.queue })
    }

    @Test
    fun theOverripeMelonBurstsInAShockwave() {
        val world = MelonsWorld(level("4-1"))
        assertEquals(MelonKind.Overripe, world.loaded)
        world.setAim(45f, 60f)
        world.launch()
        repeat(BURST_STEPS) { world.tick() }
        assertTrue(world.ability())
        // The melon is gone, a blast goes off where it was, and it can't burst twice.
        assertEquals(0, world.pieces.count { it.kind == PieceKind.Melon })
        assertEquals(1, world.blasts.size)
        assertFalse(world.ability())
    }

    @Test
    fun anOverripeMelonBurstsWhenItHitsHard() {
        // Fired flat into the ground in front of the slingshot, it bursts on landing without a key press.
        val world = MelonsWorld(level("4-1"))
        world.setAim(0f, 100f)
        world.launch()
        val melon = world.melon!!
        melon.body.linearVelocity = Vec2(5f, -30f)
        repeat(BOUNCE_STEPS) { world.tick() }
        assertTrue(melon.dead)
        assertTrue(world.burstUsed)
    }

    @Test
    fun aShieldStopsMelonsButNotTheStructure() {
        val world = MelonsWorld(level("4-1"))
        val shield = world.pieces.single { it.kind == PieceKind.Shield }
        // Nothing inside touches the ring: the structure stood still while the level settled.
        assertTrue(world.pieces.filter { it.kind == PieceKind.Slop }.all { hypot(it.x - shield.x, it.y - shield.y) < shield.w / 2 })
        // A melon flung at the ring from outside bounces off it rather than passing through.
        world.setAim(0f, 20f)
        world.launch()
        val melon = world.melon!!
        melon.body.setTransform(Vec2(shield.x - shield.w / 2 - 3f, shield.y + 2f), 0f)
        melon.body.linearVelocity = Vec2(20f, 0f)
        var minX = Float.MAX_VALUE
        repeat(BOUNCE_STEPS) {
            world.tick()
            if (!melon.dead) minX = minOf(minX, abs(melon.x - shield.x))
        }
        assertTrue("got within $minX m of the middle", melon.dead || minX > shield.w / 2 - 2f)
    }

    @Test
    fun aDroneHoldsItsSlopUntilItsThrustersBreak() {
        val world = MelonsWorld(level("4-2"))
        val drones = world.pieces.filter { it.drone }
        assertEquals(2, drones.size)
        // Its Prompt Bot stands on the deck, and both hover still.
        val riders = world.pieces.filter { it.slop == SlopKind.Prompt }
        assertTrue(riders.all { it.y > 18f })
        repeat(IDLE_STEPS) { world.tick() }
        assertTrue(drones.all { it.hanging && abs(it.y - 18f) < 0.01f })
        // A hard hit from below breaks one's thrusters, and it falls.
        world.setAim(0f, 20f)
        world.launch()
        val melon = world.melon!!
        val drone = drones[0]
        melon.body.setTransform(Vec2(drone.x, drone.y - 4f), 0f)
        melon.body.linearVelocity = Vec2(0f, 28f)
        repeat(BOUNCE_STEPS) { world.tick() }
        assertFalse(drone.hanging)
        assertTrue(drone.y < 17.5f)
    }

    @Test
    fun aFanLiftsTheMelonAndTheAimFollowsIt() {
        // 3-2's fan blows from 42 to 50 m. Past it, the aimed flight is well above the plain arc.
        val world = MelonsWorld(level("3-2"))
        world.setAim(45f, 60f)
        val rad = Math.toRadians(45.0)
        val v = world.launchSpeed
        var t = 0f
        while (world.preview(t).first < 51f) t += 0.01f
        val aimedY = world.preview(t).second
        val plainY = (MelonsWorld.SLING_Y + v * Math.sin(rad) * t - MelonsWorld.GRAVITY * t * t / 2).toFloat()
        assertTrue("aimed $aimedY vs plain $plainY", aimedY > plainY + 1f)
        // The real flight passes 51 m where the aim said, within a metre.
        world.launch()
        var flownY = Float.NaN
        repeat(600) {
            world.tick()
            val m = world.melon
            if (flownY.isNaN() && m != null && !m.dead && m.x >= 51f) flownY = m.y
        }
        assertEquals(aimedY, flownY, 1f)
    }

    @Test
    fun aHangingBeamHoldsUntilItsCablesSnap() {
        val world = MelonsWorld(level("3-3"))
        val beams = world.pieces.filter { it.hanging }
        assertEquals(2, beams.size)
        // It hangs still, however long.
        repeat(IDLE_STEPS) { world.tick() }
        assertTrue(beams.all { it.hanging && abs(it.y - 24f) < 0.01f })
        // A melon thrown up into one snaps its cables, and it falls.
        world.setAim(0f, 20f)
        world.launch()
        val melon = world.melon!!
        val beam = beams[0]
        melon.body.setTransform(Vec2(beam.x, beam.y - 4f), 0f)
        melon.body.linearVelocity = Vec2(0f, 25f)
        repeat(BOUNCE_STEPS) { world.tick() }
        assertFalse(beam.hanging)
        assertTrue(beam.y < 23f)
    }

    @Test
    fun theHoneydewSlamsStraightDown() {
        val world = MelonsWorld(level("3-1"))
        assertEquals(MelonKind.Heavy, world.loaded)
        world.setAim(45f, 80f)
        world.launch()
        val melon = world.melon!!
        assertEquals(1f, melon.w / 2, 0.001f)
        repeat(BURST_STEPS) { world.tick() }
        assertTrue(world.ability())
        assertTrue(melon.body.linearVelocity.y < -20f)
        assertTrue(abs(melon.body.linearVelocity.x) < 6f)
        assertFalse(world.ability())
    }

    @Test
    fun aimStaysInRange() {
        val world = MelonsWorld(level("1-1"))
        world.aim(500f, 500f)
        assertEquals(MelonsWorld.MAX_ANGLE, world.angle)
        assertEquals(100f, world.power)
        world.aim(-500f, -500f)
        assertEquals(MelonsWorld.MIN_ANGLE, world.angle)
        assertEquals(MelonsWorld.MIN_POWER, world.power)
    }

    @Test
    fun aBatteryBlastBreaksWhatIsNearIt() {
        // 1-4's shot goes in under the beam and sets off the battery.
        val world = play(level("1-4"), listOf(4f to 100f))
        assertTrue(world.pieces.none { it.kind == PieceKind.Battery })
        assertEquals(MelonPhase.Cleared, world.phase)
    }

    @Test
    fun progressOpensTheNextLevelAndReadsBack() {
        var progress = MelonsProgress()
        assertTrue(progress.open(0))
        assertFalse(progress.open(1))
        progress = progress.record(0, 2, 900)
        assertTrue(progress.open(1))
        progress = progress.record(0, 1, 500)
        assertEquals(2, progress.seeds[0])
        assertEquals(900, progress.best[0])
        assertEquals(progress, MelonsProgress.decode(progress.encode()))
        assertEquals(MelonsProgress(), MelonsProgress.decode("bad"))
        // A save from 1.0.0, with World 1 only, keeps its seeds and scores, and World 2 starts locked after them.
        val old = MelonsProgress.decode("332100000|1500,900,800,400,0,0,0,0,0")
        assertEquals(MelonsLevels.all.size, old.seeds.size)
        assertEquals(listOf(3, 3, 2, 1), old.seeds.take(4))
        assertEquals(1500, old.best[0])
        assertEquals(0, old.seeds[MelonsLevels.PER_WORLD])
        assertFalse(old.open(MelonsLevels.PER_WORLD))
        assertEquals(MelonsProgress(), MelonsProgress.decode(null))
    }

    @Test
    fun theKeysFireLookAheadAndRestart() {
        fun command(code: Int) = GameKeymap.command(GameKind.Melons, GameKeyPress(code))
        assertEquals(GameCommand.Primary, command(KeyEvent.KEYCODE_SPACE))
        assertEquals(GameCommand.Hint, command(KeyEvent.KEYCODE_V))
        assertEquals(GameCommand.Undo, command(KeyEvent.KEYCODE_R))
        assertEquals(GameCommand.Pause, command(KeyEvent.KEYCODE_P))
        assertEquals(GameCommand.Move(0, -1), command(KeyEvent.KEYCODE_DPAD_UP))
    }

    private companion object {
        const val MAX_STEPS = 1200
        const val IDLE_STEPS = 300
        const val BURST_STEPS = 30
        const val BOUNCE_STEPS = 40
    }
}
