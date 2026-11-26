package org.sableos.games

import android.content.Context
import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sableos.games.GgArt.arrow
import org.sableos.games.GgArt.cross
import org.sableos.games.GgArt.prop
import org.sableos.games.GgArt.propWide
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

private const val GG_PREFS = "sable_gravelgears"
private const val GG_NANOS = 1_000_000_000f
private const val GG_COLUMNS = 9
private const val DOCK_SHARE = 0.14f

/** What the player has earned, kept between rides. */
private data class GgProgress(
    val spokes: Boolean = false,
    val knobby: Boolean = false,
    val night: Boolean = false,
    val bestDay: Int = 0,
    val bestNight: Int = 0,
    val farthest: Int = 0,
) {
    /** Every rider can be picked from the start (owner request 2026-11-26); the old unlock flags stay in saves. */
    fun riderOpen(r: GgRider) = r in GgRider.all

    fun encode() = listOf(spokes, knobby, night, bestDay, bestNight, farthest).joinToString(",")

    companion object {
        fun decode(s: String?): GgProgress {
            val p = s?.split(",") ?: return GgProgress()
            if (p.size < 6) return GgProgress()
            return GgProgress(p[0] == "true", p[1] == "true", p[2] == "true", p[3].toIntOrNull() ?: 0, p[4].toIntOrNull() ?: 0, p[5].toIntOrNull() ?: 0)
        }
    }
}

private fun loadProgress(c: Context) = GgProgress.decode(c.getSharedPreferences(GG_PREFS, Context.MODE_PRIVATE).getString("progress", null))

private fun saveProgress(
    c: Context,
    p: GgProgress,
) = c.getSharedPreferences(GG_PREFS, Context.MODE_PRIVATE).edit().putString("progress", p.encode()).apply()

private enum class GgScreen { Select, Ride }

private fun loadTiltOn(c: Context) = c.getSharedPreferences(GG_PREFS, Context.MODE_PRIVATE).getBoolean("tilt", true)

private fun saveTiltOn(
    c: Context,
    on: Boolean,
) = c.getSharedPreferences(GG_PREFS, Context.MODE_PRIVATE).edit().putBoolean("tilt", on).apply()

/** A steering key held down (J, L or an arrow), until its key-up or until it stops repeating. */
private class GgHold {
    var dir = 0
    var at = 0L

    fun press(
        d: Int,
        now: Long,
    ) {
        dir = d
        at = now
    }

    fun steer(now: Long): Float = if (dir != 0 && now - at < HOLD_MS) dir.toFloat() else 0f

    companion object {
        /** A held key repeats well inside this; a lost key-up stops steering after it. */
        const val HOLD_MS = 650L
    }
}

private val GG_STEER_KEYS = setOf(KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)

@Composable
internal fun GravelGearsScreen() {
    val context = LocalContext.current
    val shell = LocalGameShell.current
    val host = LocalGameKeyHost.current
    val sprites = rememberGgSprites()
    var progress by remember { mutableStateOf(loadProgress(context)) }
    var screen by remember { mutableStateOf(GgScreen.Select) }
    var riderIndex by remember { mutableIntStateOf(0) }
    var night by remember { mutableStateOf(false) }
    var run by remember { mutableStateOf<GgRun?>(null) }
    var selected by remember { mutableIntStateOf(-1) }
    var frame by remember { mutableIntStateOf(0) }
    var hud by remember { mutableStateOf(0) }
    var seedCounter by remember { mutableIntStateOf(1) }
    var saved by remember { mutableStateOf(false) }
    val tilt = remember { GgTilt(context) }
    var tiltOn by remember { mutableStateOf(loadTiltOn(context)) }
    val hold = remember { GgHold() }

    fun startRun() {
        val rider = GgRider.all[riderIndex]
        if (!progress.riderOpen(rider)) return
        if (night && !progress.night) return
        run = GgRun(GgCourse(night, seed = System.nanoTime(), fresh = true), rider, seed = System.nanoTime() + seedCounter++)
        selected = -1
        saved = false
        screen = GgScreen.Ride
        tilt.level()
        hud++
    }

    fun record(r: GgRun) {
        if (saved) return
        var p = progress
        if (r.unlocks.contains("rider_spokes") || r.gates >= 15) p = p.copy(spokes = true)
        if (r.unlocks.contains("biome_night_ride_oled") || (!r.isNight && r.gates >= 18)) p = p.copy(night = true)
        if (r.unlocks.contains("rider_knobby") || (r.isNight && r.gates >= 9)) p = p.copy(knobby = true)
        p = if (r.isNight) p.copy(bestNight = maxOf(p.bestNight, r.score)) else p.copy(bestDay = maxOf(p.bestDay, r.score))
        p = p.copy(farthest = maxOf(p.farthest, r.gates))
        if (p != progress) {
            progress = p
            saveProgress(context, p)
        }
        if (r.phase == GgPhase.Wiped) saved = true
    }

    /** Space: a quick hop with the first air card in the hand, if there is one. */
    fun hop() {
        val r = run ?: return
        val slot = r.hand.indexOfFirst { c -> c != null && c.steps.any { it.air } }
        if (slot >= 0) r.fire(slot)
    }

    fun confirm(space: Boolean) {
        val r = run
        when {
            screen == GgScreen.Select -> startRun()
            r == null -> Unit
            r.phase == GgPhase.Riding -> if (space) hop()
            r.phase == GgPhase.StageClear -> r.continueRide()
            r.phase == GgPhase.Wiped -> {
                r.retry()
                saved = false
                tilt.level()
            }
            r.phase == GgPhase.Drafting -> if (selected in r.draftChoices.indices) r.pick(selected).also { selected = -1 } else selected = 0
            else -> Unit
        }
        hud++
    }

    fun toggleTilt() {
        tiltOn = !tiltOn
        saveTiltOn(context, tiltOn)
        if (tiltOn) tilt.level()
        run?.let { it.say(if (tiltOn) "Tilt steering on" else "Tilt steering off") }
        hud++
    }

    // The accelerometer is read only while a ride is on screen and the game is not paused.
    val held = shell?.suspended == true
    val tilting = tiltOn && screen == GgScreen.Ride && !held && tilt.available
    DisposableEffect(tilting) {
        if (tilting) tilt.start()
        onDispose { tilt.stop() }
    }
    DisposableEffect(host) {
        host.upHandler = { press -> if (press.keyCode in GG_STEER_KEYS) hold.dir = 0 }
        onDispose { host.upHandler = null }
    }

    // The ride's frame loop: the world rolls on by itself; steering comes from tilt and held keys.
    LaunchedEffect(run, held) {
        var last = 0L
        while (!held) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / GG_NANOS).coerceAtMost(0.05f)
                last = now
                val r = run
                if (r != null && dt > 0f) {
                    val before = r.phase
                    val steer = hold.steer(now / 1_000_000L) + (if (tilting) tilt.steer else 0f)
                    r.step(dt, steer)
                    if (r.phase != before || r.gates > progress.farthest) {
                        record(r)
                        hud++
                    }
                }
                frame++
            }
        }
    }

    GameKeyHandler(GameKind.GravelGears) { command ->
        val r = run
        when (command) {
            is GameCommand.Digit -> {
                if (screen == GgScreen.Ride && r != null) {
                    if (r.phase == GgPhase.Drafting) {
                        if (command.value - 1 in r.draftChoices.indices) {
                            if (selected == command.value - 1) r.pick(selected).also { selected = -1 } else selected = command.value - 1
                        }
                    } else {
                        r.fire(command.value - 1)
                    }
                    hud++
                }
            }

            is GameCommand.Move -> {
                when {
                    screen == GgScreen.Select -> {
                        if (command.dx != 0) riderIndex = (riderIndex + command.dx).mod(GgRider.all.size)
                        if (command.dy != 0 && progress.night) night = !night
                    }

                    r == null -> Unit

                    r.phase == GgPhase.Drafting -> {
                        if (command.dx != 0 && r.draftChoices.isNotEmpty()) selected = (selected.coerceAtLeast(0) + command.dx).mod(r.draftChoices.size)
                    }

                    command.dx != 0 -> {
                        r.nudge(command.dx)
                        hold.press(command.dx, System.nanoTime() / 1_000_000L)
                    }

                    command.dy != 0 -> r.pedal(command.dy)
                }
                hud++
            }

            GameCommand.Primary -> confirm(space = false)

            GameCommand.Select -> confirm(space = true)

            GameCommand.Draw -> {
                if (r != null && r.phase == GgPhase.Wiped) confirm(space = false) else r?.redraw()
                hud++
            }

            GameCommand.Hint -> toggleTilt()

            GameCommand.Erase -> {
                if (r != null && r.phase == GgPhase.Drafting) r.skipDraft()
                hud++
            }

            GameCommand.NewGame -> {
                screen = GgScreen.Select
                run = null
                hud++
            }

            GameCommand.Pause -> shell?.onPause?.invoke()

            else -> return@GameKeyHandler false
        }
        true
    }

    val r = run
    if (hud < 0) return
    val status =
        when {
            screen == GgScreen.Select || r == null -> ""
            else -> "${"%,d".format(r.score)}  x${r.combo}"
        }
    val label =
        when {
            screen == GgScreen.Select || r == null -> "Pick your rider"
            r.phase == GgPhase.Wiped -> "Wipeout · ${r.wipeReason}"
            r.phase == GgPhase.StageClear -> "Stage cleared"
            else -> "${r.stage.name.substringAfter(": ")} · gate ${min(r.gates + 1, 999)}"
        }
    GameFrame(
        title = "Gravel & Gears",
        subtitle = "Ride the trail, fire your cards, hit the gates.",
        statusLabel = label,
        status = status,
        hint = "Steer with the trackpad, J L or tilt. A S D F fire a card, Space hops.",
        actions =
            listOf(
                GameAction(tab = true, label = "Pause", key = "P") { shell?.onPause?.invoke() },
                GameAction(tab = true, label = if (tiltOn) "Tilt on" else "Tilt off", key = "T") { toggleTilt() },
                GameAction(tab = true, label = "New", key = "N") {
                    screen = GgScreen.Select
                    run = null
                },
            ),
    ) { side ->
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val boardModifier = if (constraints.hasBoundedHeight) Modifier.fillMaxSize() else Modifier.size(side)
            Box(boardModifier.clipToBounds().background(Color.Black)) {
                if (screen == GgScreen.Select || r == null) {
                    GgSelect(sprites, riderIndex, night, progress, onPick = { riderIndex = it }, onNight = { if (progress.night) night = it }, onRide = ::startRun)
                } else {
                    GgBoard(r, sprites, frame)
                    GgToasts(r)
                    GgDock(r, frame, onSlot = { r.fire(it); hud++ }, onRedraw = { r.redraw(); hud++ })
                    when (r.phase) {
                        GgPhase.Ready -> GgCountdown(r)
                        GgPhase.Drafting -> GgDraft(r, selected, onPick = { r.pick(it); selected = -1; hud++ }, onSkip = { r.skipDraft(); hud++ })
                        GgPhase.StageClear -> GgStagePanel(r, onContinue = { r.continueRide(); hud++ })
                        GgPhase.Wiped -> GgWipePanel(r, progress, onRetry = { confirm(space = false) }, onNew = { screen = GgScreen.Select; run = null })
                        else -> Unit
                    }
                }
            }
        }
    }
}

/** How many rows of trail sit above the rider: few, so most of the screen shows what is coming. */
private const val RIDER_ROW = 1.6f

/** Where the board is looking: the size of a tile and the row at the top of the screen. */
private class GgView(
    val tile: Float,
    val camY: Float,
) {
    fun sx(x: Float) = x * tile + tile / 2

    fun sy(y: Float) = (y - camY) * tile + tile / 2
}

private fun ggHash(
    x: Int,
    y: Int,
): Int {
    var h = x * 374761393 + y * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    return h xor (h ushr 16)
}

@Composable
private fun GgBoard(
    run: GgRun,
    sprites: GgSprites,
    frame: Int,
) {
    val night = run.isNight
    var width by remember { mutableIntStateOf(1) }
    Canvas(
        Modifier
            .fillMaxSize()
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(run) { detectDragGestures { _, d -> run.slide(d.x / (width / GG_COLUMNS.toFloat())) } },
    ) {
        if (frame < 0) return@Canvas
        val tile = size.width / GG_COLUMNS
        val rows = size.height / tile
        val camY = run.y - RIDER_ROW
        val clock = run.clock
        val course = run.course
        val view = GgView(tile, camY)
        fun sx(x: Float) = view.sx(x)
        fun sy(y: Float) = view.sy(y)
        val y0 = floor(camY).toInt() - 2
        val y1 = (camY + rows).toInt() + 3
        course.ensure(y1)
        // The camera: a shake on landing, and a slight bank with the rider's lean, zoomed in a touch so no corner shows.
        val shake = run.landing / GgRun.LAND_TIME * tile * 0.07f
        drawContext.transform.translate(sin(clock * 83f) * shake, cos(clock * 71f) * shake)
        val pivot = Offset(sx(run.x), sy(run.y))
        drawContext.transform.rotate(-run.lean * BANK_DEGREES, pivot)
        drawContext.transform.scale(1.05f, 1.05f, pivot)
        val dirt = if (night) GgArt.NightDirt else GgArt.DayDirt
        val slope = if (night) GgArt.NightSlope else GgArt.DaySlope
        val contour = if (night) GgArt.NightContour else GgArt.DayContour
        drawRect(slope)
        // Contour lines: slow waves that scroll with the trail, the topographic look.
        for (k in -1..(rows.toInt() / 2 + 3)) {
            val baseY = (k * 2.1f - (camY * 0.6f).mod(2.1f)) * tile
            val p = Path()
            var first = true
            var px = 0f
            while (px <= size.width + 20f) {
                val yy = baseY + sin((px / size.width * 2.2f + k * 0.7f) * PI.toFloat()) * tile * 0.35f
                if (first) p.moveTo(px, yy) else p.lineTo(px, yy)
                first = false
                px += 24f
            }
            drawPath(p, contour.copy(alpha = if (night) 0.6f else 0.35f), style = Stroke(tile * 0.03f))
        }
        // The trail band between the pines, its edges smoothed so the trail bends instead of stepping.
        val leftPts = (y0..y1).map { Offset(course.trail(it).first * tile, sy(it.toFloat())) }
        val rightPts = (y0..y1).map { Offset((course.trail(it).last + 1) * tile, sy(it.toFloat())) }
        val left = Path().apply { smoothThrough(leftPts, start = true) }
        val right = Path().apply { smoothThrough(rightPts, start = true) }
        val band =
            Path().apply {
                smoothThrough(leftPts, start = true)
                smoothThrough(rightPts.reversed(), start = false)
                close()
            }
        drawPath(band, dirt)
        // Speed streaks in the dirt, so the ride reads as moving even on a bare stretch.
        val streak = (if (night) Color.White else Color(0xFF8A4A2A)).copy(alpha = if (night) 0.08f else 0.18f)
        for (yy in y0..y1) {
            val h = ggHash(yy, 7)
            if (h % 3 != 0) continue
            val t = course.trail(yy)
            val sxp = (t.first + ((h ushr 4) % 100) / 100f * (t.last - t.first)) * tile + tile / 2
            val syp = sy(yy + ((h ushr 12) % 100) / 100f)
            drawLine(streak, Offset(sxp, syp), Offset(sxp, syp + tile * 0.5f), tile * 0.04f, StrokeCap.Round)
        }
        // Grass tufts and pebbles on the slope and the trail, so the ground itself is seen to rush past.
        val tuft = (if (night) Color(0xFF5E7C8C) else Color(0xFF5E7A3A)).copy(alpha = if (night) 0.35f else 0.55f)
        val pebble = (if (night) Color(0xFF8FA4B4) else Color(0xFF7A4A2E)).copy(alpha = 0.4f)
        for (yy in y0..y1) {
            val t = course.trail(yy)
            for (k in 0 until 4) {
                val h = ggHash(yy * 4 + k, 11)
                val fx = ((h ushr 3) % 1000) / 1000f * GG_COLUMNS - 0.5f
                val fy = yy + ((h ushr 13) % 100) / 100f
                val onTrail = fx > t.first - 0.4f && fx < t.last + 0.4f
                val p = Offset(sx(fx), sy(fy))
                if (onTrail) {
                    drawCircle(pebble, tile * (0.025f + (h and 3) * 0.008f), p)
                } else {
                    val s = tile * (0.1f + (h and 3) * 0.025f)
                    drawLine(tuft, p, Offset(p.x - s * 0.5f, p.y - s), tile * 0.03f, StrokeCap.Round)
                    drawLine(tuft, p, Offset(p.x, p.y - s * 1.2f), tile * 0.03f, StrokeCap.Round)
                    drawLine(tuft, p, Offset(p.x + s * 0.5f, p.y - s), tile * 0.03f, StrokeCap.Round)
                }
            }
        }
        drawPath(left, if (night) GgArt.NightEdge else GgArt.DayEdge, style = Stroke(tile * 0.07f, cap = StrokeCap.Round))
        drawPath(right, if (night) GgArt.NightEdge else GgArt.DayEdge, style = Stroke(tile * 0.07f, cap = StrokeCap.Round))

        drawTracks(run, view, night)

        // The rest of the card being ridden: a short dashed line so the player sees where the move takes them.
        run.move?.let { m -> drawMovePath(run, m, view, night, clock) }

        // Everything standing on the slope, drawn from the top row down so lower things overlap higher ones.
        val things = ArrayList<Pair<Float, DrawScope.() -> Unit>>()
        for (yy in y0..y1) {
            val t = course.trail(yy)
            // pines along both edges, tighter near the trail and sparse further out
            for (side in 0..1) {
                for (depth in 0..2) {
                    val h = ggHash(yy * 2 + side, depth)
                    if (h % (depth + 2) != 0) continue
                    val col = if (side == 0) t.first - 1 - depth else t.last + 1 + depth
                    if (col < -1 || col > GG_COLUMNS) continue
                    val jitter = ((h ushr 8) % 100) / 100f - 0.5f
                    val fx = col + jitter * 0.5f
                    val fy = yy + ((h ushr 16) % 100) / 100f * 0.5f
                    things += fy to {
                        prop(sprites.prop(GgKind.PINE_TREE, night), Offset(sx(fx), sy(fy) + tile * 0.45f), tile * (1.5f + (h % 3) * 0.12f))
                    }
                }
            }
            for (x in -1..GG_COLUMNS) {
                val o = course.obstacleAt(x, yy) ?: continue
                if (o.x != x) continue
                things += yy.toFloat() to { drawObstacle(o, sprites, night, view) }
            }
        }
        course.known().filter { it.row in y0..y1 }.forEach { g ->
            things += g.row.toFloat() - 0.001f to {
                val span = (g.right - g.left + 1)
                propWide(sprites.flags(night), Offset(sx((g.left + g.right) / 2f), sy(g.row.toFloat()) + tile * 0.4f), tile * (span + 0.35f), anchor = 0.85f)
            }
        }
        run.critters.forEach { a ->
            if (a.y !in y0..y1) return@forEach
            things += a.y.toFloat() to {
                val frameIdx = ((clock * 6f).toInt() + a.y) % 2
                val foot = Offset(sx(a.x), sy(a.y.toFloat()) + tile * 0.4f)
                when (a.kind) {
                    GgAnimalKind.MARMOT -> {
                        drawOval(Color.Black.copy(alpha = 0.18f), Offset(foot.x - tile * 0.22f, foot.y - tile * 0.07f), Size(tile * 0.44f, tile * 0.12f))
                        prop(sprites.marmot(if (frameIdx == 0) 0 else 1), foot, tile * MARMOT_TILES, anchor = 0.9f)
                    }

                    GgAnimalKind.DEER -> {
                        val pose = if (a.bolting) 2 else if (a.state == GgDeerState.ALERT) 1 else 0
                        drawOval(Color.Black.copy(alpha = 0.18f), Offset(foot.x - tile * 0.3f, foot.y - tile * 0.08f), Size(tile * 0.6f, tile * 0.14f))
                        prop(sprites.deer(pose), foot, tile * DEER_TILES, anchor = 0.92f)
                    }
                }
            }
        }
        val look = GgLook.of(run.rider.id)
        things += run.y to { drawRider(run, look, view, night) }
        things.sortedBy { it.first }.forEach { it.second(this) }

        // A deer that has seen the rider: its row pulses red until it has run across.
        run.critters.filter { it.kind == GgAnimalKind.DEER && it.state == GgDeerState.ALERT && !it.met && it.y in y0..y1 }.forEach { a ->
            val t = course.trail(a.y)
            val pulse = 0.22f + 0.18f * sin(clock * 9f)
            drawRect(GgArt.Lethal.copy(alpha = pulse), Offset(t.first * tile, sy(a.y.toFloat()) - tile * 0.1f), Size((t.last - t.first + 1) * tile, tile * 0.6f))
        }
        if (night) drawHeadlight(run, view, sprites)
        drawSpeedLines(run, tile, size)
    }
}

/** How far, in degrees, an angled ramp is turned toward the way it kicks. */
private const val RAMP_TURN = 28f

/** How far, in degrees, the camera banks with a full lean. */
private const val BANK_DEGREES = 2.5f

/** Streaks down the sides of the screen once the ride is fast, faster and more of them the quicker it goes. */
private fun DrawScope.drawSpeedLines(
    run: GgRun,
    tile: Float,
    size: Size,
) {
    if (run.phase != GgPhase.Riding) return
    val fast = ((run.speed - 3.0f) / 1.2f).coerceIn(0f, 1f)
    if (fast <= 0f) return
    val ink = Color.White.copy(alpha = 0.35f * fast)
    val count = 6
    for (i in 0 until count) {
        val h = ggHash(i, 29)
        val left = i % 2 == 0
        val x = if (left) (h % 100) / 100f * tile * 0.9f else size.width - (h % 100) / 100f * tile * 0.9f
        val len = tile * (0.8f + fast * 1.2f)
        val span = size.height + len
        val y = (((h ushr 8) % 1000) / 1000f * span - run.y * tile * 1.6f).mod(span) - len
        drawLine(ink, Offset(x, y), Offset(x, y + len), tile * 0.03f, StrokeCap.Round)
    }
}

private val GG_SHADOWED = setOf(GgKind.BOULDER, GgKind.STUMP, GgKind.SMALL_ROCK, GgKind.LOG, GgKind.RAMP)

/** Animal heights in tiles, kept under the rider's so the rider stays the biggest thing on the trail. */
private const val MARMOT_TILES = 0.5f
private const val DEER_TILES = 0.95f

/** A smooth line through [pts]: each corner becomes a curve through the midpoints, so an edge bends instead of stepping. */
private fun Path.smoothThrough(
    pts: List<Offset>,
    start: Boolean,
) {
    if (pts.isEmpty()) return
    if (start) moveTo(pts[0].x, pts[0].y) else lineTo(pts[0].x, pts[0].y)
    for (i in 1 until pts.size - 1) {
        quadraticTo(pts[i].x, pts[i].y, (pts[i].x + pts[i + 1].x) / 2, (pts[i].y + pts[i + 1].y) / 2)
    }
    lineTo(pts.last().x, pts.last().y)
}

/** Pedal strokes per row of trail: sets how fast the rider bobs. */
private const val PEDAL_PER_ROW = 0.9f

/**
 * The rider, built from parts and posed from the ride: the cranks and wheels turn with the distance ridden, the bike
 * banks with the lean and swings round to face the way it is steered, the rider stands to sprint and in the air,
 * tucks at speed, a hop lifts and grows the rider, a landing squashes, and a crash tumbles.
 */
private fun DrawScope.drawRider(
    run: GgRun,
    look: GgLook,
    view: GgView,
    night: Boolean,
) {
    val tile = view.tile
    val wiped = run.phase == GgPhase.Wiped
    val air = run.airborne
    val pose = ggRigPose(run)
    val lift = run.lift * tile * 0.55f
    val tumble = if (wiped) (run.sinceWipe / 0.5f).coerceIn(0f, 1f) else 0f
    val cx = view.sx(run.x) + tumble * tile * 0.35f
    val groundY = view.sy(run.y) + tile * 0.4f
    val foot = Offset(cx, groundY - lift)
    val shadow = 1f - run.lift * 0.5f
    drawOval(
        Color.Black.copy(alpha = (if (night) 0.4f else 0.24f) * shadow),
        Offset(cx - tile * 0.5f * shadow, groundY - tile * 0.13f),
        Size(tile * 1.0f * shadow, tile * 0.24f * shadow),
    )
    val squash = run.landing / GgRun.LAND_TIME
    val grow = 1f + run.lift * 0.18f
    withTransform({
        rotate(tumble * 200f, pivot = Offset(foot.x, foot.y - tile * 0.5f))
        scale(grow * (1f + squash * 0.1f), grow * (1f - squash * 0.14f), pivot = foot)
    }) {
        drawRig(look, pose, foot, tile * RIG_UNIT, night, alpha = 1f - tumble * 0.3f)
    }
    if (wiped) cross(Offset(cx, groundY - tile * 0.3f), tile * 0.25f, GgArt.Lethal, tile * 0.1f)
}

/** The rider's pose this frame, from the ride. */
private fun ggRigPose(run: GgRun): GgRigPose {
    val air = run.airborne
    val riding = run.phase == GgPhase.Riding
    val turns = run.y / RIG_UNIT
    val crank = if (air || !riding) 0f else -run.y * 2f * PI.toFloat() * PEDAL_PER_ROW
    val sprint = ((run.speed - 2.9f) / 0.8f).coerceIn(0f, 1f)
    val stand = if (air) 0.8f else sprint * 0.7f
    val tuck = ((run.speed - 3.3f) / 1.0f).coerceIn(0f, 1f)
    return GgRigPose(
        yaw = (run.facing * 50f + run.lean * 15f) * DEG,
        roll = run.lean * 0.42f + sin(crank) * 0.05f * stand,
        pitch = run.lift * 0.28f,
        crank = crank,
        wheel = turns / GgRigMath.WHEEL,
        stand = stand,
        tuck = tuck,
    )
}

private const val DEG = (PI / 180.0).toFloat()

/** Pixels per rig unit, in tiles: sets the rider's size on the trail. */
private const val RIG_UNIT = 0.78f

/** Tyre tracks behind the rider, and dust kicked up in a hard turn, at speed or on landing. */
private fun DrawScope.drawTracks(
    run: GgRun,
    view: GgView,
    night: Boolean,
) {
    val tile = view.tile
    val pts = run.tracks.toList() + GgTrack(run.x, run.y, run.airborne)
    val ink = if (night) Color(0xFF3A5068) else Color(0xFF9C5A36)
    for (i in 1 until pts.size) {
        val a = pts[i - 1]
        val b = pts[i]
        if (a.air || b.air) continue
        val fade = i / pts.size.toFloat()
        val from = Offset(view.sx(a.x), view.sy(a.y) + tile * 0.3f)
        val to = Offset(view.sx(b.x), view.sy(b.y) + tile * 0.3f)
        drawLine(ink.copy(alpha = 0.45f * fade), from, to, tile * 0.05f, StrokeCap.Round)
    }
    val kicking = run.phase == GgPhase.Riding && !run.airborne && (abs(run.lean) > 0.35f || run.landing > 0f || run.speed > 2.9f)
    if (!kicking) return
    val dust = if (night) Color(0xFF9FB4C8) else Color(0xFFF6D7B0)
    val recent = pts.takeLast(6)
    recent.forEachIndexed { i, p ->
        val age = (recent.size - 1 - i).toFloat()
        val h = ggHash((p.y * 10).toInt(), 3)
        val jitter = ((h ushr 6) % 100) / 100f - 0.5f
        val c = Offset(view.sx(p.x) + jitter * tile * 0.3f - run.lean * age * tile * 0.05f, view.sy(p.y) + tile * 0.32f)
        drawCircle(dust.copy(alpha = (0.4f * (1f - age / recent.size)).coerceAtLeast(0f)), tile * (0.07f + age * 0.035f), c)
    }
}

/** The rest of the card being ridden, as a dashed line ahead of the rider. */
private fun DrawScope.drawMovePath(
    run: GgRun,
    m: GgMove,
    view: GgView,
    night: Boolean,
    clock: Float,
) {
    val tile = view.tile
    val p0 = run.y - m.y0
    if (p0 >= m.rows) return
    val baseX = run.x - m.offset(p0)
    val colour = (if (night) GgArt.SafeNight else GgArt.Safe).copy(alpha = 0.8f)
    val outline = (if (night) Color(0xFF001018) else Color(0xFF5A2A1A)).copy(alpha = 0.6f)
    val path = Path()
    var p = p0
    path.moveTo(view.sx(run.x), view.sy(run.y) + tile * 0.2f)
    while (p < m.rows) {
        p = min(m.rows, p + 0.1f)
        val lift = if (m.air(p)) m.lift(p) * tile * 0.35f else 0f
        path.lineTo(view.sx(baseX + m.offset(p)), view.sy(m.y0 + p) + tile * 0.2f - lift)
    }
    val dash = PathEffect.dashPathEffect(floatArrayOf(tile * 0.2f, tile * 0.14f), -clock * tile * 2f)
    drawPath(path, outline, style = Stroke(tile * 0.13f, cap = StrokeCap.Round, pathEffect = dash))
    drawPath(path, colour, style = Stroke(tile * 0.06f, cap = StrokeCap.Round, pathEffect = dash))
}

private fun DrawScope.drawObstacle(
    o: GgObstacle,
    sprites: GgSprites,
    night: Boolean,
    view: GgView,
) {
    val tile = view.tile
    fun sx(x: Float) = view.sx(x)

    fun sy(y: Float) = view.sy(y)
    val img = sprites.prop(o.kind, night)
    val cx = sx(o.x + (o.span - 1) / 2f + o.shift)
    val foot = Offset(cx, sy(o.y.toFloat()) + tile * 0.42f)
    // A soft shadow under anything standing on the trail, so it sits on the ground in its own tile.
    if (o.kind in GG_SHADOWED) {
        val w = tile * (0.72f * o.span)
        drawOval(Color.Black.copy(alpha = if (night) 0.35f else 0.16f), Offset(cx - w / 2, foot.y - tile * 0.12f), Size(w, tile * 0.2f))
    }
    when (o.kind) {
        GgKind.PINE_TREE -> prop(img, foot, tile * 1.6f)
        GgKind.BOULDER -> prop(img, foot, tile * 0.95f, anchor = 0.85f)
        GgKind.SMALL_ROCK -> prop(img, foot, tile * 0.5f, anchor = 0.85f)
        GgKind.LOG -> propWide(img, foot, tile * o.span * 0.98f, anchor = 0.8f)
        GgKind.STUMP -> prop(img, foot, tile * 0.82f, anchor = 0.85f)
        GgKind.RAMP -> {
            // An angled kicker is turned the way it throws the rider, with a chevron to show it.
            withTransform({ rotate(o.dir * RAMP_TURN, pivot = Offset(foot.x, foot.y - tile * 0.35f)) }) {
                prop(img, foot, tile * 0.95f, anchor = 0.8f)
            }
            if (o.dir != 0) {
                val c = Offset(foot.x + o.dir * tile * 0.42f, foot.y - tile * 0.32f)
                val ink = if (night) GgArt.SafeNight else Color.White
                val d = tile * 0.13f * o.dir
                drawLine(Color.Black.copy(alpha = 0.35f), Offset(c.x - d, c.y - tile * 0.13f), Offset(c.x, c.y), tile * 0.09f, StrokeCap.Round)
                drawLine(Color.Black.copy(alpha = 0.35f), Offset(c.x - d, c.y + tile * 0.13f), Offset(c.x, c.y), tile * 0.09f, StrokeCap.Round)
                drawLine(ink, Offset(c.x - d, c.y - tile * 0.13f), Offset(c.x, c.y), tile * 0.05f, StrokeCap.Round)
                drawLine(ink, Offset(c.x - d, c.y + tile * 0.13f), Offset(c.x, c.y), tile * 0.05f, StrokeCap.Round)
            }
        }
        GgKind.BERM_LEFT, GgKind.BERM_RIGHT -> propWide(img, Offset(sx(o.x + 0.5f), foot.y), tile * 1.5f, anchor = 0.8f)
        GgKind.MUD -> propWide(img, Offset(foot.x, foot.y - tile * 0.1f), tile * 1.1f, anchor = 0.5f)
        GgKind.GRAVEL -> propWide(img, Offset(foot.x, foot.y - tile * 0.1f), tile * 1.0f, anchor = 0.5f)
    }
}

/** Night Ride: the world is dark except a cone ahead of the rider; gates glow above the dark. */
private fun DrawScope.drawHeadlight(
    run: GgRun,
    view: GgView,
    sprites: GgSprites,
) {
    val tile = view.tile
    fun sx(x: Float) = view.sx(x)

    fun sy(y: Float) = view.sy(y)
    val cx = sx(run.x)
    val cy = sy(run.y) + tile * 0.2f
    val full = run.beam > 0f
    val depth = (if (full) 12f else 4f) * tile
    val widthAtEnd = (if (full) 12f else 5f) * tile
    val nearW = tile * 1.2f
    val layer = androidx.compose.ui.graphics.Paint()
    drawIntoCanvas2(layer) {
        drawRect(Color.Black.copy(alpha = 0.94f))
        val cone = Path().apply {
            moveTo(cx - nearW / 2, cy - tile * 0.8f)
            lineTo(cx + nearW / 2, cy - tile * 0.8f)
            lineTo(cx + widthAtEnd / 2, cy + depth)
            lineTo(cx - widthAtEnd / 2, cy + depth)
            close()
        }
        drawPath(cone, Brush.verticalGradient(listOf(Color.White, Color.White.copy(alpha = 0.75f), Color.White.copy(alpha = 0f)), startY = cy - tile * 0.8f, endY = cy + depth), blendMode = BlendMode.DstOut)
        drawCircle(Color.White, tile * 1.0f, Offset(cx, cy), blendMode = BlendMode.DstOut)
    }
    // A warm bloom inside the cone.
    val bloom = Path().apply {
        moveTo(cx - nearW / 2, cy)
        lineTo(cx + nearW / 2, cy)
        lineTo(cx + widthAtEnd / 2, cy + depth)
        lineTo(cx - widthAtEnd / 2, cy + depth)
        close()
    }
    drawPath(bloom, Brush.verticalGradient(listOf(Color(0x40F9D423), Color(0x00F9D423)), startY = cy, endY = cy + depth))
    // The gates stay on top of the dark so the way down is always readable.
    val g = run.nextGate
    if (sy(g.row.toFloat()) in -tile..size.height + tile) {
        val span = g.right - g.left + 1
        propWide(sprites.flags(true), Offset(sx((g.left + g.right) / 2f), sy(g.row.toFloat()) + tile * 0.4f), tile * (span + 0.35f), alpha = 0.9f, anchor = 0.85f)
    }
}

private inline fun DrawScope.drawIntoCanvas2(
    paint: androidx.compose.ui.graphics.Paint,
    block: DrawScope.() -> Unit,
) {
    drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(Offset.Zero, size), paint)
    block()
    drawContext.canvas.restore()
}

@Composable
private fun GgToasts(run: GgRun) {
    val lines = run.events.filter { run.clock - it.at < TOAST_SECONDS || run.phase != GgPhase.Riding }.takeLast(3)
    if (lines.isEmpty()) return
    Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lines.forEach { e ->
            Surface(shape = RoundedCornerShape(6.dp), color = Color(0xB3000000)) {
                Text(
                    e.text,
                    color = if (e.good) Color(0xFFFFE08A) else Color(0xFFFF8A80),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** The four cards along the bottom, kept small so the trail stays the main thing on screen. */
@Composable
private fun GgDock(
    run: GgRun,
    frame: Int,
    onSlot: (Int) -> Unit,
    onRedraw: () -> Unit,
) {
    if (frame < 0) return
    val keys = LocalKeyBindings.current(GameCatalog.gravelGears.controls.first())
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        val h = maxHeight * DOCK_SHARE
        Row(
            Modifier.fillMaxWidth().height(h).padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (i in 0 until GgRun.HAND) {
                val card = run.hand.getOrNull(i)
                val key = keys.keysFor("slot${i + 1}").firstOrNull()?.let { KeyEvent.keyCodeToString(it).removePrefix("KEYCODE_") } ?: "${i + 1}"
                val on = card != null && run.phase == GgPhase.Riding && !run.airborne
                Surface(
                    onClick = { onSlot(i) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    shape = RoundedCornerShape(7.dp),
                    color = Color(0xE60E3A3F),
                    border = BorderStroke(if (on) 2.dp else 1.dp, if (on) Color(0xFF3FFFEF) else Color(0xFF3C6A6E)),
                ) {
                    if (card != null) {
                        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(key, color = Color(0xFFFFE08A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text(card.name, color = Color.White, fontSize = 9.sp, lineHeight = 10.sp, maxLines = 2)
                            }
                            Canvas(Modifier.size(h * 0.62f)) { drawGlyph(card, size, on) }
                        }
                    } else {
                        // An empty slot: the bar fills as the next card comes.
                        val filled = 1f - (run.cooldown[i] / GgRun.COOLDOWN).coerceIn(0f, 1f)
                        Box(Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.BottomStart) {
                            Text(key, color = Color(0x80FFE08A), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopStart))
                            Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0x33FFFFFF), RoundedCornerShape(2.dp)))
                            Box(Modifier.fillMaxWidth(filled).height(4.dp).background(Color(0xFF3FFFEF), RoundedCornerShape(2.dp)))
                        }
                    }
                }
            }
            Surface(
                onClick = onRedraw,
                modifier = Modifier.fillMaxHeight().height(h).padding(0.dp).size(h),
                shape = RoundedCornerShape(7.dp),
                color = if (run.redraws > 0) Color(0xE6403014) else Color(0x66000000),
                border = BorderStroke(1.dp, if (run.redraws > 0) GgArt.Amber else Color(0xFF555555)),
            ) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("R", color = if (run.redraws > 0) GgArt.Amber else Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("${run.redraws}", color = Color.White, fontSize = 9.sp)
                }
            }
        }
    }
}

private const val TOAST_SECONDS = 2.2f

/** "Ready" before the ride rolls, then "Go!". */
@Composable
private fun GgCountdown(run: GgRun) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(12.dp), color = Color(0xB3000000)) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (run.countdown > 0.5f) "Ready" else "Go!", color = GgArt.Amber, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Steer: trackpad, J L or tilt  ·  A S D F fire a card  ·  Space hops", color = Color(0xFFE3F3F1), fontSize = 10.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

/** A card's path as a bold arrow: a thick stroke on a small square, so the shape reads at a glance. */
private fun DrawScope.drawGlyph(
    card: GgCard,
    box: Size,
    on: Boolean,
) {
    val maxDx = maxOf(1, card.steps.maxOf { abs(it.dx) })
    val maxDy = maxOf(2, card.steps.maxOf { it.dy })
    val unit = min(box.width * 0.78f / (2 * maxDx), box.height * 0.8f / maxDy)
    val ox = box.width / 2
    val oy = box.height * 0.1f
    val colour = if (on) Color(0xFF3FFFEF) else Color(0xFFEAF6F5)
    var from = Offset(ox, oy)
    val stroke = box.width * 0.17f
    for ((i, s) in card.steps.withIndex()) {
        val to = Offset(ox + s.dx * unit, oy + s.dy * unit)
        val last = i == card.steps.lastIndex
        if (last) {
            arrow(from, to, colour, stroke * 0.8f)
        } else if (s.air) {
            drawLine(colour, from, to, stroke * 0.8f, StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(stroke * 0.7f, stroke * 0.9f)))
        } else {
            drawLine(colour, from, to, stroke * 0.8f, StrokeCap.Round)
        }
        from = to
    }
}

@Composable
private fun GgDraft(
    run: GgRun,
    selected: Int,
    onPick: (Int) -> Unit,
    onSkip: () -> Unit,
) {
    // Bottom half only, so the trail ahead stays visible.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.46f), color = GgArt.Panel, shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Pick a card  ·  1 2 3 or left and right, Enter takes it, Esc skips", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    run.draftChoices.forEachIndexed { i, c ->
                        Surface(
                            onClick = { onPick(i) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF0E3A3F),
                            border = BorderStroke(if (i == selected) 2.dp else 1.dp, if (i == selected) Color(0xFF3FFFEF) else Color(0xFF3C6A6E)),
                        ) {
                            Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("${i + 1}  ${c.name}", color = Color(0xFFFFE08A), fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                Canvas(Modifier.size(34.dp)) { drawGlyph(c, size, i == selected) }
                                Text(c.description, color = Color(0xFFD7ECEA), fontSize = 9.sp, lineHeight = 11.sp, textAlign = TextAlign.Center, maxLines = 4)
                            }
                        }
                    }
                }
                Text("Skip (Esc)", color = Color(0xFFB8D4D2), fontSize = 11.sp, modifier = Modifier.clickable(onClick = onSkip).padding(2.dp))
            }
        }
    }
}

@Composable
private fun GgStagePanel(
    run: GgRun,
    onContinue: () -> Unit,
) {
    val cleared = run.clearedStage
    val idx = run.course.stages.indexOf(cleared)
    val next = run.course.stages.getOrNull(idx + 1)
    Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
        Surface(color = GgArt.Panel, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, GgArt.Amber)) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(cleared?.name?.substringAfter(": ")?.let { "$it cleared" } ?: "Stage cleared", color = GgArt.Amber, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Score ${"%,d".format(run.score)}  ·  gate ${run.gates}  ·  best combo x${GgRun.MAX_COMBO.coerceAtMost(run.combo)}", color = Color.White, fontSize = 13.sp)
                run.events.filter { it.text.contains("unlocked") }.forEach { Text(it.text, color = Color(0xFF8CFFB0), fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                Text(
                    if (next != null) "Next: ${next.name.substringAfter(": ")}" else "The trail goes on, with no end. How far can you ride?",
                    color = Color(0xFFD7ECEA),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
                GgButton("Continue  (Enter)", onContinue)
                Text("Pause (P) for the menu", color = Color(0xFFB8D4D2), fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun GgWipePanel(
    run: GgRun,
    progress: GgProgress,
    onRetry: () -> Unit,
    onNew: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
        Surface(color = GgArt.Panel, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, GgArt.Lethal)) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Wipeout!  ${run.wipeReason}", color = Color(0xFFFF8A80), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                val bestSoFar = if (run.isNight) progress.bestNight else progress.bestDay
                Text("Score ${"%,d".format(run.score)}  ·  gates ${run.gates}  ·  best ${"%,d".format(maxOf(bestSoFar, run.score))}", color = Color.White, fontSize = 13.sp)
                if (!progress.night) Text("Night Ride unlocks at gate 18 (${progress.farthest}/18)", color = Color(0xFFD7ECEA), fontSize = 11.sp)
                GgButton(if (run.gates == 0) "Retry from the start  (Enter or R)" else "Retry from gate ${run.gates}  (Enter or R)", onRetry)
                GgButton("New run  (N)", onNew)
            }
        }
    }
}

@Composable
private fun GgButton(
    text: String,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, shape = RoundedCornerShape(8.dp), color = Color(0xFF16A0A0)) {
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
    }
}

/** The rider carousel: left and right pick a rider, up and down switch Day and Night, Enter rides. */
@Composable
private fun GgSelect(
    sprites: GgSprites,
    index: Int,
    night: Boolean,
    progress: GgProgress,
    onPick: (Int) -> Unit,
    onNight: (Boolean) -> Unit,
    onRide: () -> Unit,
) {
    val bg = if (night) Color(0xFF05080D) else Color(0xFFD9744A)
    Box(Modifier.fillMaxSize().background(bg), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pick your rider", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GgRider.all.forEachIndexed { i, r ->
                    val open = progress.riderOpen(r)
                    Surface(
                        onClick = { onPick(i) },
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xCC0E3A3F),
                        border = BorderStroke(if (i == index) 3.dp else 1.dp, if (i == index) Color(0xFF3FFFEF) else Color(0xFF3C6A6E)),
                    ) {
                        Column(Modifier.padding(8.dp).size(width = 104.dp, height = 150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Canvas(Modifier.size(58.dp).graphicsLayer { alpha = if (open) 1f else 0.3f }) {
                                prop(sprites.head(r.id), Offset(size.width / 2, size.height), size.height, anchor = 1f)
                            }
                            Text(r.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(r.species, color = Color(0xFFB8D4D2), fontSize = 10.sp)
                            Text(
                                r.perkText,
                                color = Color(0xFFD7ECEA),
                                fontSize = 9.sp,
                                lineHeight = 11.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(onClick = { onNight(false) }, shape = RoundedCornerShape(8.dp), color = if (!night) Color(0xFF16A0A0) else Color(0x66000000)) {
                    Text("Day trail", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
                }
                Surface(onClick = { onNight(true) }, shape = RoundedCornerShape(8.dp), color = if (night) Color(0xFF16A0A0) else Color(0x66000000)) {
                    Text(if (progress.night) "Night Ride  x1.5" else "Night Ride (locked: gate 18)", color = Color.White.copy(alpha = if (progress.night) 1f else 0.5f), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
                }
            }
            GgButton("Ride  (Enter)", onRide)
            Text("Left and right pick a rider · up and down switch day and night", color = Color(0xFFE3F3F1), fontSize = 10.sp, textAlign = TextAlign.Center)
            Text("Best  day ${"%,d".format(progress.bestDay)}  ·  night ${"%,d".format(progress.bestNight)}", color = Color(0xFFE3F3F1), fontSize = 10.sp)
        }
    }
}
