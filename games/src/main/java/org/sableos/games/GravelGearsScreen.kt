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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import org.sableos.games.GgArt.riderProp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

private const val GG_PREFS = "sable_gravelgears"
private const val GG_NANOS = 1_000_000_000f
private const val GG_COLUMNS = 9
private const val RIDER_ROW = 2.4f
private const val MOVE_SECONDS = 0.45f
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
    fun riderOpen(r: GgRider) = r == GgRider.Gravel || (r == GgRider.Spokes && spokes) || (r == GgRider.Knobby && knobby)

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

/** Where the rider is drawn while a card plays: cell positions to glide through, and whether each leg is airborne. */
private class GgGlide(
    val points: List<Offset>,
    val air: List<Boolean>,
) {
    var t = 0f
}

@Composable
internal fun GravelGearsScreen() {
    val context = LocalContext.current
    val shell = LocalGameShell.current
    val sprites = rememberGgSprites()
    var progress by remember { mutableStateOf(loadProgress(context)) }
    var screen by remember { mutableStateOf(GgScreen.Select) }
    var riderIndex by remember { mutableIntStateOf(0) }
    var night by remember { mutableStateOf(false) }
    var run by remember { mutableStateOf<GgRun?>(null) }
    var selected by remember { mutableIntStateOf(-1) }
    var scout by remember { mutableFloatStateOf(0f) }
    var camY by remember { mutableFloatStateOf(0f) }
    var glide by remember { mutableStateOf<GgGlide?>(null) }
    var frame by remember { mutableIntStateOf(0) }
    var clock by remember { mutableFloatStateOf(0f) }
    var hud by remember { mutableStateOf(0) }
    var seedCounter by remember { mutableIntStateOf(1) }
    var saved by remember { mutableStateOf(false) }

    fun startRun() {
        val rider = GgRider.all[riderIndex]
        if (!progress.riderOpen(rider)) return
        if (night && !progress.night) return
        run = GgRun(GgCourse(night, seed = System.nanoTime()), rider, seed = System.nanoTime() + seedCounter++)
        selected = -1
        scout = 0f
        camY = 0f
        glide = null
        saved = false
        screen = GgScreen.Ride
        hud++
    }

    fun record(r: GgRun) {
        if (saved) return
        saved = true
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
    }

    fun play(slot: Int) {
        val r = run ?: return
        if (r.phase != GgPhase.Riding || glide != null) return
        val o = r.preview(slot) ?: return
        val start = Offset(r.x.toFloat(), r.y.toFloat())
        val pts = listOf(start) + o.path.map { Offset(it.x.toFloat(), it.y.toFloat()) }
        val airs = o.path.map { it.air }
        r.play(slot)
        selected = -1
        scout = 0f
        glide = GgGlide(pts, airs)
        hud++
        if (r.phase != GgPhase.Riding || r.gates > 0) record(r)
    }

    fun pressSlot(slot: Int) {
        val r = run ?: return
        if (r.phase != GgPhase.Riding || glide != null || slot !in r.hand.indices) return
        if (selected == slot) play(slot) else selected = slot
        hud++
    }

    fun confirm() {
        val r = run
        when {
            screen == GgScreen.Select -> startRun()
            r == null -> Unit
            glide != null -> glide = null
            r.phase == GgPhase.Riding -> if (selected >= 0) play(selected) else if (r.hand.isNotEmpty()) selected = 0
            r.phase == GgPhase.StageClear -> r.continueRide()
            r.phase == GgPhase.Wiped -> r.retry()
            r.phase == GgPhase.Drafting -> if (selected in r.draftChoices.indices) r.pick(selected) else selected = 0
        }
        hud++
    }

    // The run's frame loop: the rider glides through the cells of a card, the camera follows.
    val held = shell?.suspended == true
    LaunchedEffect(run, held) {
        var last = 0L
        while (!held) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / GG_NANOS).coerceAtMost(0.05f)
                last = now
                clock += dt
                val g = glide
                if (g != null) {
                    g.t += dt / MOVE_SECONDS
                    if (g.t >= 1f) glide = null
                }
                val r = run
                if (r != null) {
                    val shown = riderAt(r, glide).second
                    val target = shown - RIDER_ROW + scout
                    camY += (target - camY) * (6f * dt).coerceAtMost(1f)
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
                        pressSlot(command.value - 1)
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

                    command.dx != 0 && r.phase == GgPhase.Riding -> {
                        if (r.hand.isNotEmpty()) selected = (if (selected < 0) 0 else selected + command.dx).mod(r.hand.size)
                    }

                    command.dy != 0 -> scout = (scout + command.dy).coerceIn(0f, 4f)
                }
                hud++
            }

            GameCommand.Primary -> confirm()

            GameCommand.Draw -> {
                if (r != null && r.phase == GgPhase.Wiped) r.retry() else if (r?.redraw() == true) selected = -1
                hud++
            }

            GameCommand.Undo -> {
                if (r?.undoLast() == true) glide = null
                hud++
            }

            GameCommand.Erase -> {
                if (r != null && r.phase == GgPhase.Drafting) r.skipDraft() else if (screen == GgScreen.Ride && r?.phase == GgPhase.Riding) selected = -1
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
            else -> "${"%,d".format(r.score)}  x${r.combo}  deck ${r.drawPile}"
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
        subtitle = "Draw cards, carve the trail, hit the gates.",
        statusLabel = label,
        status = status,
        hint = "A S D F pick a card, press again to ride. R redraws.",
        actions =
            listOf(
                GameAction(tab = true, label = "Pause", key = "P") { shell?.onPause?.invoke() },
                GameAction(tab = true, label = "Undo", key = "U") { if (run?.undoLast() == true) glide = null },
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
                    GgBoard(r, sprites, camY, clock, glide, selected, frame, onScout = { scout = (scout + it).coerceIn(0f, 4f) })
                    GgToasts(r)
                    GgDock(r, selected, onSlot = ::pressSlot, onRedraw = { if (r.redraw()) selected = -1; hud++ })
                    when (r.phase) {
                        GgPhase.Drafting -> GgDraft(r, selected, onPick = { r.pick(it); selected = -1; hud++ }, onSkip = { r.skipDraft(); hud++ })
                        GgPhase.StageClear -> GgStagePanel(r, onContinue = { r.continueRide(); hud++ })
                        GgPhase.Wiped -> GgWipePanel(r, progress, onRetry = { r.retry(); hud++ }, onNew = { screen = GgScreen.Select; run = null })
                        else -> Unit
                    }
                }
            }
        }
    }
}

/** The rider's drawn position (column, row) and whether it is in the air, part-way through a [glide]. */
private fun riderAt(
    r: GgRun,
    glide: GgGlide?,
): Triple<Float, Float, Boolean> {
    if (glide == null) return Triple(r.x.toFloat(), r.y.toFloat(), false)
    val pts = glide.points
    val segs = pts.size - 1
    if (segs <= 0) return Triple(r.x.toFloat(), r.y.toFloat(), false)
    val f = (glide.t.coerceIn(0f, 1f)) * segs
    val i = min(f.toInt(), segs - 1)
    val u = f - i
    val a = pts[i]
    val b = pts[i + 1]
    return Triple(a.x + (b.x - a.x) * u, a.y + (b.y - a.y) * u, glide.air[i])
}

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
    camY: Float,
    clock: Float,
    glide: GgGlide?,
    selected: Int,
    frame: Int,
    onScout: (Float) -> Unit,
) {
    val night = run.isNight
    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectDragGestures { _, d -> onScout(-d.y / 60f) } },
    ) {
        if (frame < 0) return@Canvas
        val tile = size.width / GG_COLUMNS
        val rows = size.height / tile
        val course = run.course
        val view = GgView(tile, camY)
        fun sx(x: Float) = view.sx(x)
        fun sy(y: Float) = view.sy(y)
        val y0 = camY.toInt() - 2
        val y1 = (camY + rows).toInt() + 3
        course.ensure(y1)
        val dirt = if (night) GgArt.NightDirt else GgArt.DayDirt
        val slope = if (night) GgArt.NightSlope else GgArt.DaySlope
        val contour = if (night) GgArt.NightContour else GgArt.DayContour
        drawRect(slope)
        // Contour lines: slow waves that scroll with the trail, the topographic look.
        for (k in -1..(rows.toInt() / 2 + 3)) {
            val baseY = (k * 2.1f - (camY * 0.9f) % 2.1f) * tile
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
        // The trail band between the pines.
        val left = Path()
        val right = Path()
        var started = false
        for (yy in y0..y1) {
            val t = course.trail(yy)
            val lx = t.first * tile
            val rx = (t.last + 1) * tile
            val py = sy(yy.toFloat())
            if (!started) {
                left.moveTo(lx, py)
                right.moveTo(rx, py)
                started = true
            } else {
                left.lineTo(lx, py)
                right.lineTo(rx, py)
            }
        }
        val band = Path()
        started = false
        for (yy in y0..y1) {
            val t = course.trail(yy)
            val py = sy(yy.toFloat())
            if (!started) band.moveTo(t.first * tile, py) else band.lineTo(t.first * tile, py)
            started = true
        }
        for (yy in y1 downTo y0) {
            val t = course.trail(yy)
            band.lineTo((t.last + 1) * tile, sy(yy.toFloat()))
        }
        band.close()
        drawPath(band, dirt)
        drawPath(left, if (night) GgArt.NightEdge else GgArt.DayEdge, style = Stroke(tile * 0.07f, cap = StrokeCap.Round))
        drawPath(right, if (night) GgArt.NightEdge else GgArt.DayEdge, style = Stroke(tile * 0.07f, cap = StrokeCap.Round))

        // Everything standing on the slope, drawn from the top row down so lower things overlap higher ones.
        val shown = riderAt(run, glide)
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
            for (x in 0 until GG_COLUMNS) {
                val o = course.obstacleAt(x, yy) ?: continue
                if (o.x != x) continue
                things += yy.toFloat() to { drawObstacle(o, sprites, night, view) }
            }
        }
        for (i in 0 until 4) {
            val g = course.nextGate(camY.toInt() - 4 + i * 5)
            if (g.row in y0..y1) {
                things += g.row.toFloat() - 0.001f to {
                    val span = (g.right - g.left + 1)
                    propWide(sprites.flags(night), Offset(sx((g.left + g.right) / 2f), sy(g.row.toFloat()) + tile * 0.4f), tile * (span + 0.35f), anchor = 0.85f)
                }
            }
        }
        run.animals.forEach { a ->
            if (a.y !in y0..y1) return@forEach
            things += a.y.toFloat() to {
                val frameIdx = ((clock * 4f).toInt() + a.x) % 2
                val foot = Offset(sx(a.x.toFloat()), sy(a.y.toFloat()) + tile * 0.4f)
                when (a.kind) {
                    GgAnimalKind.MARMOT -> {
                        val img = sprites.marmot(if (frameIdx == 0) 0 else 1)
                        propWide(img, foot, tile * 0.95f, anchor = 0.9f)
                    }

                    GgAnimalKind.DEER -> {
                        val img = sprites.deer(if (a.state == GgDeerState.ALERT) 1 else 0)
                        prop(img, foot, tile * 1.15f, anchor = 0.92f)
                    }
                }
            }
        }
        val rider = GgRider.byId(run.rider.id)
        things += shown.second to {
            val pose = poseFor(glide, shown.third)
            val foot = Offset(sx(shown.first), sy(shown.second) + tile * 0.4f - (if (shown.third) tile * 0.35f * sin(PI.toFloat() * ((glide?.t ?: 0f) * 4f).mod(1f)) else 0f))
            drawOval(Color.Black.copy(alpha = if (night) 0.4f else 0.22f), Offset(sx(shown.first) - tile * 0.32f, sy(shown.second) + tile * 0.25f), Size(tile * 0.64f, tile * 0.2f))
            riderProp(sprites.rider(rider.id, pose, night), foot, tile)
        }
        things.sortedBy { it.first }.forEach { it.second(this) }

        // The ghost: where the selected card ends, its path, and what it would hit.
        if (selected >= 0 && glide == null && run.phase == GgPhase.Riding) {
            run.preview(selected)?.let { o -> drawGhost(o, run, sprites, view, night, clock) }
        }
        // A deer about to bolt: the whole row is a danger row for one turn.
        run.animals.filter { it.kind == GgAnimalKind.DEER && it.state == GgDeerState.ALERT && it.y in y0..y1 }.forEach { a ->
            val t = course.trail(a.y)
            val pulse = 0.25f + 0.2f * sin(clock * 6f)
            drawRect(GgArt.Lethal.copy(alpha = pulse), Offset(t.first * tile, sy(a.y.toFloat()) - tile * 0.1f), Size((t.last - t.first + 1) * tile, tile * 0.6f))
        }
        if (night) drawHeadlight(run, shown, view, sprites)
    }
}

private fun poseFor(
    glide: GgGlide?,
    air: Boolean,
): Int {
    if (air) return 4
    if (glide != null) {
        val a = glide.points.first()
        val b = glide.points.last()
        val dx = b.x - a.x
        return when {
            dx <= -2f -> 0
            dx < 0f -> 1
            dx >= 2f -> 3
            dx > 0f -> 3
            else -> 2
        }
    }
    return 2
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
    val cx = sx(o.x + (o.span - 1) / 2f)
    val foot = Offset(cx, sy(o.y.toFloat()) + tile * 0.42f)
    when (o.kind) {
        GgKind.PINE_TREE -> prop(img, foot, tile * 1.6f)
        GgKind.BOULDER -> prop(img, foot, tile * 0.95f, anchor = 0.85f)
        GgKind.SMALL_ROCK -> prop(img, foot, tile * 0.5f, anchor = 0.85f)
        GgKind.LOG -> propWide(img, foot, tile * o.span * 0.98f, anchor = 0.8f)
        GgKind.STUMP -> prop(img, foot, tile * 0.82f, anchor = 0.85f)
        GgKind.RAMP -> prop(img, foot, tile * 0.95f, anchor = 0.8f)
        GgKind.BERM_LEFT, GgKind.BERM_RIGHT -> propWide(img, Offset(sx(o.x + 0.5f), foot.y), tile * 1.5f, anchor = 0.8f)
        GgKind.MUD -> propWide(img, Offset(foot.x, foot.y - tile * 0.1f), tile * 1.1f, anchor = 0.5f)
        GgKind.GRAVEL -> propWide(img, Offset(foot.x, foot.y - tile * 0.1f), tile * 1.0f, anchor = 0.5f)
    }
}

private fun DrawScope.drawGhost(
    o: GgOutcome,
    run: GgRun,
    sprites: GgSprites,
    view: GgView,
    night: Boolean,
    clock: Float,
) {
    val tile = view.tile
    fun sx(x: Float) = view.sx(x)

    fun sy(y: Float) = view.sy(y)
    val safe = if (night) GgArt.SafeNight else GgArt.Safe
    val outline = if (night) Color(0xFF001018) else Color(0xFF5A2A1A)
    var from = Offset(sx(run.x.toFloat()), sy(run.y.toFloat()) + tile * 0.2f)
    val dash = PathEffect.dashPathEffect(floatArrayOf(tile * 0.22f, tile * 0.16f), clock * tile)
    val width = tile * 0.075f
    for (c in o.path) {
        val to = Offset(sx(c.x.toFloat()), sy(c.y.toFloat()) + tile * 0.2f)
        val isBump = o.bump != null && c.x == o.bump.x && c.y == o.bump.y
        val color = if (isBump) GgArt.Bounce else safe
        if (c.air) {
            // Airborne stretches arc over the ground.
            val mid = Offset((from.x + to.x) / 2, (from.y + to.y) / 2 - tile * 0.35f)
            val p = Path().apply { moveTo(from.x, from.y); quadraticTo(mid.x, mid.y, to.x, to.y) }
            drawPath(p, outline, style = Stroke(width * 2.1f, pathEffect = dash, cap = StrokeCap.Round))
            drawPath(p, color, style = Stroke(width, pathEffect = dash, cap = StrokeCap.Round))
        } else {
            drawLine(outline, from, to, width * 2.1f, StrokeCap.Round, pathEffect = dash)
            drawLine(color, from, to, width, StrokeCap.Round, pathEffect = dash)
        }
        from = to
    }
    val end = Offset(sx(o.endX.toFloat()), sy(o.endY.toFloat()) + tile * 0.4f)
    if (o.crash != null) {
        val c = Offset(sx(o.crash.x.toFloat()), sy(o.crash.y.toFloat()) + tile * 0.2f)
        cross(c, tile * (0.2f + 0.03f * sin(clock * 9f)), GgArt.Lethal, tile * 0.1f)
    } else {
        if (o.bump != null) {
            val b = Offset(sx(o.bump.x.toFloat()), sy(o.bump.y.toFloat()) + tile * 0.2f)
            val before = o.path.dropLast(1).lastOrNull()
            val a = Offset(sx((before?.x ?: run.x).toFloat()), sy((before?.y ?: run.y).toFloat()) + tile * 0.2f)
            arrow(a, b, GgArt.Bounce, tile * 0.08f)
        }
        // The ghost rider where the card ends.
        riderProp(sprites.rider(run.rider.id, 2, night), end, tile, alpha = 0.45f)
        if (o.bankShot) drawCircle(GgArt.Bounce, tile * 0.5f, Offset(sx(o.endX.toFloat()), sy(o.endY.toFloat()) + tile * 0.2f), style = Stroke(tile * 0.05f))
    }
}

/** Night Ride: the world is dark except a cone ahead of the rider; gates glow above the dark. */
private fun DrawScope.drawHeadlight(
    run: GgRun,
    shown: Triple<Float, Float, Boolean>,
    view: GgView,
    sprites: GgSprites,
) {
    val tile = view.tile
    fun sx(x: Float) = view.sx(x)

    fun sy(y: Float) = view.sy(y)
    val cx = sx(shown.first)
    val cy = sy(shown.second) + tile * 0.2f
    val full = run.beam > 0
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
    val lines = run.events.takeLast(3)
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
    selected: Int,
    onSlot: (Int) -> Unit,
    onRedraw: () -> Unit,
) {
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
                val on = i == selected && card != null
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
                if (!progress.riderOpen(GgRider.Spokes)) Text("Spokes unlocks at 15 gates in one run (${progress.farthest}/15)", color = Color(0xFFD7ECEA), fontSize = 11.sp)
                else if (!progress.night) Text("Night Ride unlocks at gate 18 (${progress.farthest}/18)", color = Color(0xFFD7ECEA), fontSize = 11.sp)
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
                        Column(Modifier.padding(8.dp).size(width = 104.dp, height = 140.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Canvas(Modifier.size(70.dp).graphicsLayer { alpha = if (open) 1f else 0.3f }) {
                                prop(sprites.head(r.id), Offset(size.width / 2, size.height), size.height, anchor = 1f)
                            }
                            Text(r.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(r.species, color = Color(0xFFB8D4D2), fontSize = 10.sp)
                            Text(
                                if (open) r.perkText else if (r == GgRider.Spokes) "Locked: 15 gates in one run" else "Locked: finish Night Stage 1",
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
