package org.sableos.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TartarusRunTest {
    private val gate1 = TartarusData.gates[0]
    private val still = TbInput()

    private fun run(
        gate: TbGate = gate1,
        meta: TbMeta = TbMeta(),
    ) = TbRun(gate, meta, seed = 5L)

    private fun TbRun.wait(seconds: Float) {
        repeat((seconds / 0.02f).toInt()) { step(0.02f, still) }
    }

    /** Steps until the countdown ends. */
    private fun TbRun.begin() {
        var guard = 0
        while (phase == TbPhase.Ready && guard++ < 400) step(0.02f, still)
    }

    @Test
    fun theHandoffDataIsAllThere() {
        assertEquals(4, TartarusData.weapons.size)
        assertEquals(9, TartarusData.boons.size)
        assertEquals(14, TartarusData.enemies.size)
        assertEquals(4, TartarusData.gates.size)
        assertTrue(TartarusData.gates.all { it.stages.size == 5 && it.stages.last().boss })
        // Every enemy a stage names exists, every boss has a Guardian sprite entry.
        val ids = TartarusData.enemies.map { it.id }.toSet()
        TartarusData.gates.flatMap { it.stages }.forEach { s ->
            assertTrue(s.pool.all { it.id in ids })
            if (s.boss) assertTrue(s.bossId in ids && s.minionId in ids)
        }
    }

    @Test
    fun aRunStartsWithACountdownThenCombat() {
        val r = run()
        assertEquals(TbPhase.Ready, r.phase)
        r.begin()
        assertEquals(TbPhase.Combat, r.phase)
        assertEquals(r.maxHp, r.hp, 0f)
    }

    @Test
    fun foesSpawnOverTime() {
        val r = run()
        r.begin()
        // One foe is waiting in front of you; the next holds off a few seconds so you can settle in.
        assertEquals(1, r.aliveCount)
        repeat(100) { r.step(0.02f, still) }
        assertEquals(1, r.aliveCount)
        repeat(300) { r.step(0.02f, still) }
        assertTrue(r.aliveCount > 1)
    }

    @Test
    fun theOpeningFoeStartsStraightAheadAndEarlyFoesComeFromTheFront() {
        val r = run()
        r.begin()
        val e = r.enemies.first()
        assertTrue(kotlin.math.abs(e.x - r.px) < 0.5f)
        assertTrue(e.y > r.py + 5f)
        assertTrue(r.straightOnly)
    }

    @Test
    fun sunbowKillsAHoplitePlacedInFront() {
        val r = run()
        r.begin()
        r.spawn("skeletal_hoplite")
        val e = r.enemies.last()
        e.x = r.px
        e.y = r.py + 8f
        e.state = TbEState.Stun
        e.timer = 99f
        var shots = 0
        while (e.alive && shots < 20) {
            r.wait(0.25f)
            r.tryFire()
            shots++
        }
        assertFalse(e.alive)
        assertTrue(r.kills >= 1)
        assertTrue(r.obols > 0)
    }

    @Test
    fun theTridentSpendsFavorAndTheSunbowDoesNot() {
        val r = run()
        r.begin()
        r.enemies.clear()
        val before = r.favor
        r.tryFire()
        assertEquals(before, r.favor, 0.5f)
        r.selectWeapon(1)
        r.wait(0.4f)
        val f2 = r.favor
        assertTrue(r.tryFire())
        assertEquals(f2 - 4f, r.favor, 0.01f)
    }

    @Test
    fun aegisBoltWithNothingToStrikeCostsNothing() {
        val r = run()
        r.begin()
        r.enemies.clear()
        r.selectWeapon(3)
        r.wait(0.4f)
        val f = r.favor
        assertFalse(r.tryFire())
        assertEquals(f, r.favor, 0.01f)
    }

    @Test
    fun aWaveClearsIntoABoonDraftWithHealing() {
        val r = run()
        r.begin()
        r.hp = 40f
        r.skipTime(1000f)
        r.step(0.02f, still)
        assertEquals(TbPhase.Draft, r.phase)
        assertEquals(3, r.draftChoices.size)
        assertTrue(r.hp > 40f)
        r.pickBoon(0)
        assertEquals(TbPhase.Ready, r.phase)
        assertEquals(2, r.wave)
        assertEquals(1, r.boons.size)
    }

    @Test
    fun aresBloodlustSpeedsTheFireRate() {
        val r = run()
        r.begin()
        val base = r.fireMult
        r.skipTime(1000f)
        r.step(0.02f, still)
        val i = r.draftChoices.indexOfFirst { it.id == "ares_bloodlust" }
        if (i >= 0) {
            r.pickBoon(i)
            assertTrue(r.fireMult < base)
        }
    }

    @Test
    fun theBossStageSealsTheGateWhenTheGuardianFalls() {
        val gate = gate1
        val r = TbRun(gate, TbMeta(), seed = 3L)
        // Walk through the four survival waves.
        repeat(4) {
            r.begin()
            r.skipTime(1000f)
            r.step(0.02f, still)
            assertEquals(TbPhase.Draft, r.phase)
            r.pickBoon(0)
        }
        assertTrue(r.stage.boss)
        r.begin()
        val boss = r.boss
        assertNotNull(boss)
        r.hitEnemy(boss!!, 99999f, 0f, 0f, 0f, proc = true)
        // The kill's brief freeze-frame passes first.
        r.wait(0.3f)
        assertEquals(TbPhase.Sealed, r.phase)
    }

    @Test
    fun beingKilledEndsTheRun() {
        val r = run()
        r.begin()
        r.hurt(500f)
        assertEquals(TbPhase.Fallen, r.phase)
        assertTrue(r.wipeReason.startsWith("slain"))
    }

    @Test
    fun dashingMakesYouUntouchable() {
        val r = run()
        r.begin()
        r.dash(1f, 0f)
        val hp = r.hp
        r.hurt(30f)
        assertEquals(hp, r.hp, 0f)
        repeat(20) { r.step(0.02f, still) }
        r.hurt(30f)
        assertEquals(hp - 30f * TbRun.WAVE_EASE[0], r.hp, 0.01f)
    }

    @Test
    fun theArenaWallAndPillarsHoldThePlayerIn() {
        val r = run()
        r.begin()
        repeat(400) { r.step(0.02f, TbInput(forward = 1f)) }
        assertTrue(Math.hypot(r.px.toDouble(), r.py.toDouble()) <= r.radius.toDouble())
        r.pillars.forEach { assertTrue(TbWorld.dist(r.px, r.py, it.x, it.y) >= it.r + 0.5f) }
    }

    @Test
    fun everyGateAndStageSurvivesAMinuteOfPlayWithoutErrors() {
        for (gate in TartarusData.gates) {
            for (si in gate.stages.indices) {
                val r = TbRun(gate, TbMeta(), seed = 11L + si)
                repeat(si) {
                    r.begin()
                    r.skipTime(1000f)
                    r.step(0.02f, still)
                    if (r.phase == TbPhase.Draft) r.pickBoon(0)
                }
                r.begin()
                var guard = 0
                while (r.phase == TbPhase.Combat && guard++ < 3000) {
                    r.hp = r.maxHp
                    r.step(0.02f, TbInput(forward = if (guard % 90 < 45) 1f else -1f, strafe = 0.5f, turn = 0.2f, fire = true))
                    if (guard % 200 == 0) r.selectWeapon((guard / 200) % 4)
                }
                assertTrue("${gate.id} stage ${si + 1}", r.phase != TbPhase.Fallen)
            }
        }
    }

    @Test
    fun metaSavesAndShrineUpgradesCostObols() {
        var m = TbMeta(obols = 500, unlocked = 2, bestClear = listOf(252, 0, 0, 0))
        assertEquals(m, TbMeta.decode(m.encode()))
        assertTrue(m.canBuy(TbUpgrade.Vigor))
        m = m.buy(TbUpgrade.Vigor)
        assertEquals(380, m.obols)
        assertEquals(1, m.level(TbUpgrade.Vigor))
        assertEquals(TbMeta(), TbMeta.decode("junk"))
        assertFalse(TbMeta(obols = 1).canBuy(TbUpgrade.Vigor))
    }

    @Test
    fun vigorRaisesMaxHp() {
        val r = TbRun(gate1, TbMeta(levels = listOf(2, 0, 0, 0, 0)), seed = 1L)
        assertEquals(120f, r.maxHp, 0f)
    }

    @Test
    fun theCameraPutsThingsWhereTheyAre() {
        val r = run()
        val cam = TbCam()
        cam.set(r, 720f)
        // Straight ahead (the player looks along +y) lands in the middle, further away is smaller depth-wise higher.
        assertTrue(cam.project(r.px, r.py + 10f))
        assertEquals(360f, cam.sx, 0.5f)
        // To the player's right is +x when looking along +y.
        assertTrue(cam.project(r.px + 3f, r.py + 10f))
        assertTrue(cam.sx > 360f)
        assertTrue(cam.project(r.px - 3f, r.py + 10f))
        assertTrue(cam.sx < 360f)
        // Behind the player is not drawn.
        assertFalse(cam.project(r.px, r.py - 5f))
        // Turning right moves a point ahead to the left of the screen.
        r.turnBy(0.3f)
        r.begin()
        cam.set(r, 720f)
        cam.project(r.px, r.py + 10f)
        assertTrue(cam.sx < 360f)
        // Nearer things stand lower on the screen.
        cam.project(r.px, r.py + 20f)
        val far = cam.floorY(cam.z)
        cam.project(r.px, r.py + 5f)
        assertTrue(cam.floorY(cam.z) > far)
    }

    @Test
    fun aegisBoltStunsWhatItStrikes() {
        val r = run()
        r.begin()
        r.enemies.clear()
        r.spawn("skeletal_hoplite")
        val e = r.enemies.last()
        e.x = r.px
        e.y = r.py + 8f
        e.state = TbEState.Move
        r.selectWeapon(3)
        r.wait(0.4f)
        r.favor = 100f
        assertTrue(r.tryFire())
        assertTrue(!e.alive || e.state == TbEState.Stun)
    }

    @Test
    fun aKillFreezesTheFrameBriefly() {
        val r = run()
        r.begin()
        r.spawn("skeletal_hoplite")
        val e = r.enemies.last()
        r.hitEnemy(e, 99999f, 0f, 0f, 0f, proc = true)
        val t = r.runTime
        r.step(0.02f, still)
        assertEquals(t, r.runTime, 0f)
        repeat(10) { r.step(0.02f, still) }
        assertTrue(r.runTime > t)
    }

    @Test
    fun pairedBoonsAnnounceTheirBonus() {
        val r = run()
        r.begin()
        val zeus = TartarusData.boons.first { it.id == "zeus_chain_arc" }
        val pose = TartarusData.boons.first { it.id == "poseidon_undertow" }
        assertEquals(null, r.completes(pose))
        r.boons["zeus_chain_arc"] = 1
        assertNotNull(r.completes(pose))
        assertEquals(null, r.completes(zeus))
    }

    @Test
    fun aCursedBoonGivesTwoStacksAndCostsMaxHp() {
        var found = false
        for (seed in 1L..80L) {
            val r = TbRun(gate1, TbMeta(), seed = seed)
            r.begin()
            r.skipTime(1000f)
            r.step(0.02f, still)
            r.pickBoon(0)
            r.begin()
            r.skipTime(1000f)
            r.step(0.02f, still)
            val i = r.draftChoices.indexOfFirst { it.cursed }
            if (i >= 0) {
                val id = r.draftChoices[i].id
                r.pickBoon(i)
                assertEquals(2, r.boons[id] ?: 0)
                assertEquals(90f, r.maxHp, 0f)
                found = true
                break
            }
        }
        assertTrue(found)
    }

    @Test
    fun settingsSaveAndOldSavesStillLoad() {
        val m = TbMeta(obols = 5, difficulty = 2, aim = 0, sound = false, vibrate = false)
        assertEquals(m, TbMeta.decode(m.encode()))
        // A save from before the settings existed has five parts and gets the defaults.
        val old = TbMeta.decode("12,2,0;0;0;0,0;0;0;0,0;0;0;0;0")
        assertEquals(12, old.obols)
        assertEquals(1, old.difficulty)
        assertTrue(old.sound && old.vibrate)
    }

    @Test
    fun hardHitsHarderThanEasy() {
        fun lost(d: Int): Float {
            val r = TbRun(gate1, TbMeta(difficulty = d), seed = 5L)
            r.begin()
            val hp = r.hp
            r.hurt(40f, r.px + 5f, r.py)
            return hp - r.hp
        }
        assertTrue(lost(0) < lost(1))
        assertTrue(lost(1) < lost(2))
    }

    @Test
    fun aHitLeavesAnArrowAndSoundsEvents() {
        val r = run()
        r.begin()
        r.events.clear()
        r.hurt(10f, r.px + 6f, r.py)
        assertEquals(1, r.hitDirs.size)
        assertEquals(0f, r.hitDirs[0].angle, 0.01f)
        assertTrue(TbEvent.Hurt in r.events)
        repeat(100) { r.step(0.02f, still) }
        assertTrue(r.hitDirs.isEmpty())
    }

    @Test
    fun aimAssistOffLeavesTheShotWhereYouLook() {
        val r = TbRun(gate1, TbMeta(aim = 0), seed = 5L)
        r.begin()
        r.enemies.clear()
        r.spawn("skeletal_hoplite")
        val e = r.enemies.last()
        e.x = r.px + 1.5f
        e.y = r.py + 12f
        e.state = TbEState.Stun
        e.timer = 99f
        r.wait(0.3f)
        val hp = e.hp
        r.tryFire()
        assertEquals(hp, e.hp, 0.001f)
    }
}
