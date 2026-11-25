package org.sableos.games

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sableos.games.MelonsArt.background
import org.sableos.games.MelonsArt.band
import org.sableos.games.MelonsArt.block
import org.sableos.games.MelonsArt.enemy
import org.sableos.games.MelonsArt.melon
import org.sableos.games.MelonsArt.popup
import org.sableos.games.MelonsArt.seed
import org.sableos.games.MelonsArt.slingTies
import org.sableos.games.MelonsArt.slingshot
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val MELONS_PREFS = "sable_melons"
private const val NANOS = 1_000_000_000f
private const val MAX_FRAME = 0.05f

/**
 * The camera shows a square [VIEW] metres across, from [VIEW_BOTTOM] up; the ground is at 0. 30 m keeps
 * the melon and the Slop about 45 px tall on the Q25's 720 px screen.
 */
private const val VIEW = 30f
private const val VIEW_BOTTOM = -4f

/** While aiming, the slingshot sits this far into the view, so the aim dots stay on screen. */
private const val AIM_CAMERA_X = MelonsWorld.SLING_X - VIEW * 0.3f
private const val CAMERA_EASE = 4f
private const val AIM_STEP = 1f
private const val POWER_STEP = 2f
private const val DRAG_DEGREES_PER_PX = 0.15f
private const val DRAG_POWER_PER_PX = 0.25f
private const val PREVIEW_TIME = 1.1f
private const val PREVIEW_DOTS = 11
private const val PULL_BACK = 1.6f

private val SelectField = Color(0xFF1C2331)
private val SelectCell = Color(0xFF141A26)
private val SelectEdge = Color(0xFF33415C)
private val SelectMuted = Color(0xFFC3CAD9)

private fun loadProgress(context: Context): MelonsProgress =
    MelonsProgress.decode(context.getSharedPreferences(MELONS_PREFS, Context.MODE_PRIVATE).getString("progress", null))

private fun saveProgress(
    context: Context,
    progress: MelonsProgress,
) = context
    .getSharedPreferences(MELONS_PREFS, Context.MODE_PRIVATE)
    .edit()
    .putString("progress", progress.encode())
    .apply()

private fun seedMarks(n: Int) = "●".repeat(n) + "○".repeat(3 - n)

@Composable
internal fun MelonsScreen() {
    val context = LocalContext.current
    val shell = LocalGameShell.current
    var progress by remember { mutableStateOf(loadProgress(context)) }
    var levelIndex by remember { mutableIntStateOf(0) }
    var world by remember { mutableStateOf(MelonsWorld(MelonsLevels.all[0])) }
    var selecting by remember { mutableStateOf(true) }
    var cursor by remember { mutableIntStateOf(progress.seeds.indexOfFirst { it == 0 }.coerceAtLeast(0)) }
    var paused by remember { mutableStateOf(false) }
    var peek by remember { mutableStateOf(false) }
    var cameraX by remember { mutableFloatStateOf(AIM_CAMERA_X) }
    var recorded by remember { mutableStateOf(false) }
    // Bumped every frame, so the canvas redraws the world as it moves.
    var frame by remember { mutableIntStateOf(0) }
    val sprites = rememberMelonsSprites()

    fun play(index: Int) {
        if (!progress.open(index)) return
        levelIndex = index
        world = MelonsWorld(MelonsLevels.all[index])
        cameraX = AIM_CAMERA_X
        peek = false
        paused = false
        recorded = false
        selecting = false
    }

    fun select() {
        cursor = levelIndex
        selecting = true
    }

    fun primary() {
        when {
            selecting -> {
                play(cursor)
            }

            world.phase == MelonPhase.Aiming -> {
                peek = false
                world.launch()
            }

            world.phase == MelonPhase.Flying -> {
                world.ability()
            }

            world.phase == MelonPhase.Cleared -> {
                if (levelIndex + 1 <
                    MelonsLevels.all.size
                ) {
                    play(levelIndex + 1)
                } else {
                    select()
                }
            }

            else -> {
                play(levelIndex)
            }
        }
        frame++
    }

    fun aim(
        dAngle: Float,
        dPower: Float,
    ) {
        if (world.phase != MelonPhase.Aiming || paused) return
        world.aim(dAngle, dPower)
        frame++
    }

    val held = paused || shell?.suspended == true || selecting
    LaunchedEffect(world, held) {
        var last = 0L
        while (!held) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / NANOS).coerceAtMost(MAX_FRAME)
                last = now
                world.step(dt)
                val target =
                    when {
                        world.phase == MelonPhase.Flying -> {
                            (
                                world.melon?.takeIf { !it.dead }?.x
                                    ?: (cameraX + VIEW * 0.35f)
                            ) -
                                VIEW * 0.35f
                        }

                        peek -> {
                            (world.level.fortX + world.level.fortEnd - VIEW) / 2
                        }

                        world.phase == MelonPhase.Aiming -> {
                            AIM_CAMERA_X
                        }

                        else -> {
                            cameraX
                        }
                    }.coerceIn(0f, MelonsWorld.STAGE_W - VIEW)
                cameraX += (target - cameraX) * (CAMERA_EASE * dt).coerceAtMost(1f)
                if (world.phase == MelonPhase.Cleared && !recorded) {
                    recorded = true
                    progress = progress.record(levelIndex, world.seeds, world.score)
                    saveProgress(context, progress)
                }
                frame++
            }
        }
    }

    GameKeyHandler(GameKind.Melons) { command ->
        when (command) {
            is GameCommand.Move -> {
                if (selecting) {
                    val next = (cursor + command.dx + command.dy * 3).coerceIn(MelonsLevels.all.indices)
                    if (progress.open(next)) cursor = next
                } else {
                    aim(-command.dy * AIM_STEP, command.dx * POWER_STEP)
                }
            }

            GameCommand.Primary -> {
                primary()
            }

            GameCommand.Hint -> {
                if (!selecting && world.phase == MelonPhase.Aiming) peek = !peek
            }

            GameCommand.Undo -> {
                if (!selecting) play(levelIndex)
            }

            GameCommand.Pause -> {
                paused = !paused && !selecting
            }

            GameCommand.NewGame -> {
                select()
            }

            else -> {
                return@GameKeyHandler false
            }
        }
        true
    }

    val level = world.level
    GameFrame(
        title = "Bitter Melons",
        subtitle = "Fling angry melons. Topple the AI Slop.",
        statusLabel =
            if (selecting) {
                "World 1 · ${progress.seeds.sum()}/${MelonsLevels.all.size * 3} seeds"
            } else {
                "${level.id} ${level.name} · melons ${world.melonsLeft}"
            },
        status =
            when {
                selecting -> "Pick a level · Enter plays"
                paused -> "Paused · P resumes"
                world.phase == MelonPhase.Cleared -> "Cleared ${seedMarks(world.seeds)} ${world.score} · Space"
                world.phase == MelonPhase.Failed -> "Out of melons · ${world.score} · Space retries"
                world.phase == MelonPhase.Flying && !world.burstUsed -> "${world.score} · Space bursts"
                world.phase == MelonPhase.Flying -> world.score.toString()
                else -> "${world.score} · ${world.angle.toInt()}° ${world.power.toInt()}% · Space fires"
            },
        hint = "Up and down aim, left and right set the power",
        actions =
            listOf(
                GameAction(tab = true, label = if (paused) "Resume" else "Pause", key = "P", active = paused) {
                    paused = !paused && !selecting
                },
                GameAction(tab = true, label = "Look", key = "V", active = peek) {
                    if (!selecting && world.phase == MelonPhase.Aiming) peek = !peek
                },
                GameAction(tab = true, label = "Levels", key = "N", onClick = ::select),
            ),
    ) { side ->
        if (selecting) {
            MelonsLevelSelect(side.value, progress, cursor) { index ->
                cursor = index
                play(index)
            }
            return@GameFrame
        }
        ArcadeBoard(
            side = side,
            modifier =
                Modifier
                    .pointerInput(Unit) { detectTapGestures { primary() } }
                    .pointerInput(Unit) {
                        detectDragGestures { _, drag -> aim(-drag.y * DRAG_DEGREES_PER_PX, drag.x * DRAG_POWER_PER_PX) }
                    },
        ) {
            // Reading the frame count makes the canvas redraw as the world moves.
            if (frame >= 0) drawWorld(world, cameraX, sprites)
        }
    }
}

/** The stage as the camera sees it, [cameraX] metres from the left. */
private fun DrawScope.drawWorld(
    world: MelonsWorld,
    cameraX: Float,
    sprites: MelonsSprites,
) {
    val m = size.width / VIEW
    val top = VIEW_BOTTOM + VIEW

    fun at(
        x: Float,
        y: Float,
    ) = Offset((x - cameraX) * m, (top - y) * m)

    val degreesOf = { radians: Float -> -radians * 180f / PI.toFloat() }
    val groundY = at(0f, 0f).y
    background(sprites.world1, cameraX, MelonsWorld.STAGE_W - VIEW, groundY)

    // The melons fired so far set the next one's rage: the last melons of a level are the angriest.
    val fired = world.level.melons - world.melonsLeft
    val flyingRage = (fired - 1).coerceAtLeast(0)

    // The slingshot, with the melon in its pouch while aiming.
    val ties = at(MelonsWorld.SLING_X, MelonsWorld.SLING_Y + 0.2f)
    val (left, right) = slingTies(sprites.slingshot, ties, groundY)
    slingshot(sprites.slingshot, ties, groundY)
    val rad = world.angle * PI.toFloat() / 180f
    if (world.phase == MelonPhase.Aiming && world.melonsLeft > 0) {
        val pull = PULL_BACK * world.power / 100f
        val pouch = at(MelonsWorld.SLING_X - cos(rad) * pull, MelonsWorld.SLING_Y - sin(rad) * pull)
        for (i in 1..PREVIEW_DOTS) {
            val (px, py) = world.preview(PREVIEW_TIME * i / PREVIEW_DOTS)
            drawCircle(MelonsArt.Ink.copy(alpha = 0.6f), m * 0.16f, at(px, py))
        }
        melon(sprites, pouch, MelonsWorld.MELON_R * m, 0f, MelonPose.Ready, fired)
        band(left, right, pouch, m)
    }

    for (piece in world.pieces) {
        val c = at(piece.x, piece.y)
        val deg = degreesOf(piece.angle)
        when (piece.kind) {
            PieceKind.Block -> {
                block(sprites, c, piece.w * m, piece.h * m, deg, piece.material, piece.hp, piece.maxHp, piece.fixed)
            }

            PieceKind.Slop, PieceKind.Battery -> {
                enemy(sprites, c, piece.w * m, piece.h * m, deg, piece.kind, piece.slop, piece.cracked)
            }

            PieceKind.Melon -> {
                val current = piece === world.melon
                val landed = !current || world.phase != MelonPhase.Flying || piece.speed < 1f
                // Spent melons lie knocked out; the one in play shows its rage.
                val pose = if (landed) MelonPose.Landed else MelonPose.Flying
                melon(sprites, c, MelonsWorld.MELON_R * m, deg, pose, if (current) flyingRage else 0)
            }

            PieceKind.Seed -> {
                seed(c, MelonsWorld.SEED_R * m, deg)
            }
        }
    }

    for (blast in world.blasts) {
        val t = blast.age / MelonsWorld.BLAST_TIME
        val c = at(blast.x, blast.y)
        drawCircle(MelonsArt.Yellow.copy(alpha = (1f - t) * 0.5f), blast.radius * m * t, c)
        drawCircle(MelonsArt.Red.copy(alpha = 1f - t), blast.radius * m * t, c, style = Stroke(m * 0.3f))
    }
    for (p in world.popups) {
        val t = p.age / MelonsWorld.POPUP_TIME
        popup(p.text, at(p.x, p.y + t * 2f), m * 1.3f, 1f - t * t)
    }

    // A melon above the top edge shows as a marker at its x.
    world.melon?.takeIf { !it.dead && it.y > top }?.let { high ->
        val x = at(high.x, top).x
        val path =
            Path().apply {
                moveTo(x, m * 0.3f)
                lineTo(x - m * 0.6f, m * 1.3f)
                lineTo(x + m * 0.6f, m * 1.3f)
                close()
            }
        drawPath(path, MelonsArt.Red)
    }

    // While aiming, an arrow at the right edge points at the structure and says how far it is.
    if (world.phase == MelonPhase.Aiming && world.level.fortX > cameraX + VIEW) {
        val y = size.height * 0.45f
        val x = size.width - m * 0.6f
        val path =
            Path().apply {
                moveTo(x, y)
                lineTo(x - m * 1.2f, y - m * 0.9f)
                lineTo(x - m * 1.2f, y + m * 0.9f)
                close()
            }
        drawPath(path, MelonsArt.Yellow)
        drawPath(path, MelonsArt.Ink, style = Stroke(m * 0.12f))
        popup(
            "${(world.level.fortX - MelonsWorld.SLING_X).toInt()} m",
            Offset(x - m * 3.4f, y + m * 0.45f),
            m * 1.1f,
            1f,
        )
    }

    // The melons still to fire, bottom left.
    val inPouch = if (world.phase == MelonPhase.Aiming) 1 else 0
    repeat((world.melonsLeft - inPouch).coerceAtLeast(0)) { i ->
        val rage = fired + inPouch + i
        melon(sprites, Offset(m * (1.2f + i * 1.6f), size.height - m * 1.2f), m * 0.65f, 0f, MelonPose.Ready, rage)
    }

    if (world.comboTime > 0f) {
        popup("2× DEBRIS", Offset(size.width / 2, m * 3f), m * 1.6f, 1f)
    }
}

/** World 1's nine levels in a 3 x 3 grid, with the seeds won on each; locked levels are dimmed. */
@Composable
private fun MelonsLevelSelect(
    side: Float,
    progress: MelonsProgress,
    cursor: Int,
    onPick: (Int) -> Unit,
) {
    Column(
        Modifier
            .size(side.dp)
            .background(SelectField, RoundedCornerShape(12.dp))
            .border(1.dp, SelectEdge, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val (name, twist) = MelonsLevels.worlds.first()
        Text("World 1 · $name", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(twist, color = SelectMuted, fontSize = 12.sp, maxLines = 1)
        repeat(3) { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) { col ->
                    val index = row * 3 + col
                    val level = MelonsLevels.all[index]
                    val open = progress.open(index)
                    val here = index == cursor
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(SelectCell, RoundedCornerShape(8.dp))
                            .border(
                                if (here) 2.dp else 1.dp,
                                if (here) MelonsArt.Yellow else SelectEdge,
                                RoundedCornerShape(8.dp),
                            ).clickable(enabled = open) { onPick(index) }
                            .padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            level.id,
                            color = if (open) MelonsArt.Yellow else SelectMuted.copy(alpha = 0.6f),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                        Text(
                            level.name,
                            color = if (open) Color.White else SelectMuted.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            maxLines = 2,
                        )
                        Text(
                            if (open) seedMarks(progress.seeds[index]) else "locked",
                            color = if (open) MelonsArt.Yellow else SelectMuted.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
        if (cursor in MelonsLevels.all.indices) {
            val level = MelonsLevels.all[cursor]
            Text(
                "${level.lesson} Best ${progress.best[cursor]}",
                color = SelectMuted,
                fontSize = 12.sp,
                maxLines = 2,
            )
        }
    }
}
