package org.sableos.games

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sableos.games.MelonsArt.background
import org.sableos.games.MelonsArt.backgroundIn
import org.sableos.games.MelonsArt.band
import org.sableos.games.MelonsArt.banner
import org.sableos.games.MelonsArt.block
import org.sableos.games.MelonsArt.buttonLabel
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

/** In flight the camera keeps the melon this share of the view from the left. */
private const val FOLLOW_AT = 0.35f
private val SeedWon = Color(0xFFFFF1C1)
private val SeedEmpty = Color(0x33FFFFFF)

/** Seconds the opening shot holds on the structure before panning back. */
private const val INTRO_HOLD = 1.6f

/** The target view: its share of the screen, metres of margin round the structure, and ground shown. */
private const val TARGET_WIDTH = 0.4f
private const val TARGET_HEIGHT = 0.3f
private const val TARGET_MARGIN = 1.5f
private const val TARGET_GROUND = 1f
private const val TARGET_MIN_HIGH = 6f
private const val TARGET_DOT_TIME = 0.04f

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

/**
 * A Bitter Melon's rage, 0-3: it grows with the melons fired before it in a level (up to two) and with each world
 * after the first, so World 1 runs 0-2 and World 2 starts a step angrier.
 */
private fun rage(
    level: MelonLevel,
    firedBefore: Int,
) = (firedBefore.coerceIn(0, 2) + level.world - 1).coerceIn(0, MelonsSprites.MAX_RAGE)

/** The world the level at [index] is in. */
private fun worldOf(index: Int) = MelonsLevels.all.getOrNull(index)?.world ?: 1

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

    // Each level opens on the structure, then the camera pans back to the slingshot.
    var intro by remember { mutableFloatStateOf(0f) }
    var recorded by remember { mutableStateOf(false) }
    // Bumped every frame, so the canvas redraws the world as it moves.
    var frame by remember { mutableIntStateOf(0) }

    // The board fills the space it is given; a wide one shows more of the stage across.
    var viewW by remember { mutableFloatStateOf(VIEW) }

    // The world is not Compose state, so the status line is refreshed from this when what it shows changes.
    var hud by remember { mutableStateOf(listOf<Any>()) }
    val sprites = rememberMelonsSprites()

    fun play(index: Int) {
        if (!progress.open(index)) return
        levelIndex = index
        world = MelonsWorld(MelonsLevels.all[index])
        cameraX = fortCamera(world.level, viewW)
        intro = INTRO_HOLD
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

            // The first press during the opening pan skips it rather than firing blind.
            world.phase == MelonPhase.Aiming && intro > 0f -> {
                intro = 0f
            }

            world.phase == MelonPhase.Aiming -> {
                peek = false
                world.launch()
            }

            world.phase == MelonPhase.Flying -> {
                world.ability()
            }

            world.phase == MelonPhase.Cleared -> {
                if (levelIndex + 1 < MelonsLevels.all.size) {
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
        intro = 0f
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
                intro = (intro - dt).coerceAtLeast(0f)
                val target =
                    when {
                        world.phase == MelonPhase.Flying -> {
                            (
                                world.melon?.takeIf { !it.dead }?.x
                                    ?: (cameraX + viewW * FOLLOW_AT)
                            ) -
                                viewW * FOLLOW_AT
                        }

                        peek || intro > 0f -> {
                            fortCamera(world.level, viewW)
                        }

                        world.phase == MelonPhase.Aiming -> {
                            AIM_CAMERA_X
                        }

                        else -> {
                            cameraX
                        }
                    }.coerceIn(0f, MelonsWorld.STAGE_W - viewW)
                cameraX += (target - cameraX) * (CAMERA_EASE * dt).coerceAtMost(1f)
                if (world.phase == MelonPhase.Cleared && !recorded) {
                    recorded = true
                    progress = progress.record(levelIndex, world.seeds, world.score)
                    saveProgress(context, progress)
                }
                hud = listOf(world.phase, world.score, world.angle, world.power, world.burstUsed, world.melonsLeft)
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
    // Reading the HUD key recomposes the status line when the phase, score or aim changes.
    if (hud.isEmpty()) hud = listOf(world.phase)
    GameFrame(
        title = "Bitter Melons",
        subtitle = "Fling angry melons. Topple the AI Slop.",
        statusLabel =
            if (selecting) {
                val n = worldOf(cursor)
                val won =
                    MelonsLevels.all.indices
                        .filter { MelonsLevels.all[it].world == n }
                        .sumOf { progress.seeds[it] }
                "World $n · $won/${MelonsLevels.PER_WORLD * 3} seeds"
            } else {
                "${level.id} ${level.name} · melons ${world.melonsLeft}"
            },
        status =
            when {
                hud.isEmpty() -> {
                    ""
                }

                selecting -> {
                    "Pick a level · Enter plays"
                }

                paused -> {
                    "Paused · P resumes"
                }

                world.phase == MelonPhase.Cleared -> {
                    "Cleared ${seedMarks(world.seeds)} ${world.score} · Space"
                }

                world.phase == MelonPhase.Failed -> {
                    "Out of melons · ${world.score} · Space retries"
                }

                world.phase == MelonPhase.Flying && !world.burstUsed && world.melon?.melonKind == MelonKind.Split -> {
                    "${world.score} · Space splits"
                }

                world.phase == MelonPhase.Flying && !world.burstUsed -> {
                    "${world.score} · Space bursts"
                }

                world.phase == MelonPhase.Flying -> {
                    world.score.toString()
                }

                else -> {
                    "${world.score} · ${world.angle.toInt()}° ${world.power.toInt()}% · Space fires"
                }
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
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // Where the frame gives a bounded box (the square layout), use all of it, so a screen wider or taller
            // than square is filled; in the scrolling tall layout keep the square side.
            val boardModifier =
                if (constraints.hasBoundedHeight) Modifier.fillMaxSize() else Modifier.size(side)
            Surface(
                modifier =
                    boardModifier
                        .onSizeChanged { px ->
                            viewW = VIEW * maxOf(1f, px.width.toFloat() / px.height.coerceAtLeast(1))
                        }.pointerInput(Unit) { detectTapGestures { primary() } }
                        .pointerInput(Unit) {
                            detectDragGestures { _, drag ->
                                aim(
                                    -drag.y * DRAG_DEGREES_PER_PX,
                                    drag.x * DRAG_POWER_PER_PX,
                                )
                            }
                        },
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    // Reading the frame count makes the canvas redraw as the world moves.
                    if (frame >= 0) drawWorld(world, cameraX, sprites)
                }
            }
        }
    }
}

/** The stage as the camera sees it, [cameraX] metres from the left. */
private fun DrawScope.drawWorld(
    world: MelonsWorld,
    cameraX: Float,
    sprites: MelonsSprites,
) {
    // VIEW metres fit the shorter side; a wider board shows more of the stage, a taller one more sky.
    val m = minOf(size.width, size.height) / VIEW
    val viewW = size.width / m
    val top = VIEW_BOTTOM + size.height / m

    fun at(
        x: Float,
        y: Float,
    ) = Offset((x - cameraX) * m, (top - y) * m)

    val degreesOf = { radians: Float -> -radians * 180f / PI.toFloat() }
    val groundY = at(0f, 0f).y
    val level = world.level
    background(sprites, level.world, cameraX, MelonsWorld.STAGE_W - viewW, groundY)

    // The melons fired so far set the next one's rage: the last melons of a level are the angriest.
    val fired = level.melons - world.melonsLeft
    val flyingRage = rage(level, fired - 1)

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
        val kind = world.loaded ?: MelonKind.Bitter
        melon(sprites, pouch, kind.r * m, 0f, MelonPose.Ready, rage(level, fired), kind)
        band(left, right, pouch, m)
    }

    drawPieces(world, sprites, m, flyingRage, ::at)

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

    // While aiming with the structure off screen, a live view of it sits top right, with its distance.
    if (world.phase == MelonPhase.Aiming && world.level.fortX > cameraX + viewW) {
        targetView(world, sprites, m, viewW)
    }

    // The melons still to fire, bottom left.
    val inPouch = if (world.phase == MelonPhase.Aiming) 1 else 0
    level.queue.drop(fired + inPouch).forEachIndexed { i, kind ->
        val at = Offset(m * (1.2f + i * 1.6f), size.height - m * 1.2f)
        melon(sprites, at, m * 0.65f, 0f, MelonPose.Ready, rage(level, fired + inPouch + i), kind)
    }

    if (world.comboTime > 0f) {
        popup("2× DEBRIS", Offset(size.width / 2, m * 3f), m * 1.6f, 1f)
    }

    if (world.phase == MelonPhase.Cleared || world.phase == MelonPhase.Failed) resultPanel(world, m)
}

/**
 * The end of a level, over the middle of the board: cleared with its seeds and score and a Next level button,
 * or out of melons with a Try again button. A tap anywhere or Space does what the button says.
 */
private fun DrawScope.resultPanel(
    world: MelonsWorld,
    m: Float,
) {
    val cleared = world.phase == MelonPhase.Cleared
    val w = minOf(size.width * 0.8f, m * 22f)
    val h = m * 12f
    val left = (size.width - w) / 2
    val top = (size.height - h) / 2
    drawRect(MelonsArt.Ink.copy(alpha = 0.35f))
    drawRoundRect(MelonsArt.Ink.copy(alpha = 0.92f), Offset(left, top), Size(w, h), CornerRadius(m * 0.8f))
    drawRoundRect(MelonsArt.Yellow, Offset(left, top), Size(w, h), CornerRadius(m * 0.8f), style = Stroke(m * 0.15f))
    val cx = size.width / 2
    popup(if (cleared) "Level cleared!" else "Out of melons", Offset(cx, top + m * 2.6f), m * 1.8f, 1f)
    if (cleared) {
        // Three seeds, filled for each one won.
        for (i in 0 until 3) {
            val c = Offset(cx + (i - 1) * m * 2.4f, top + m * 4.8f)
            val won = i < world.seeds
            drawOval(if (won) SeedWon else SeedEmpty, Offset(c.x - m * 0.6f, c.y - m * 0.85f), Size(m * 1.2f, m * 1.7f))
            drawOval(
                MelonsArt.Ink,
                Offset(c.x - m * 0.6f, c.y - m * 0.85f),
                Size(m * 1.2f, m * 1.7f),
                style =
                    Stroke(m * 0.12f),
            )
        }
    }
    popup("Score ${world.score}", Offset(cx, top + m * 7.4f), m * 1.1f, 1f)
    // The button.
    val bw = minOf(w * 0.7f, m * 13f)
    val bh = m * 2.4f
    val bTop = top + h - bh - m * 1f
    drawRoundRect(MelonsArt.Yellow, Offset(cx - bw / 2, bTop), Size(bw, bh), CornerRadius(m * 0.5f))
    drawRoundRect(
        MelonsArt.Ink,
        Offset(cx - bw / 2, bTop),
        Size(bw, bh),
        CornerRadius(m * 0.5f),
        style =
            Stroke(m * 0.12f),
    )
    val last = world.level == MelonsLevels.all.last()
    val label =
        if (!cleared) {
            "Try again · Space"
        } else if (last) {
            "Levels · Space"
        } else {
            "Next level · Space"
        }
    buttonLabel(label, Offset(cx, bTop + bh * 0.68f), m * 1f)
}

/** Every block, enemy, melon and seed, [m] px per metre, placed by [at]. */
private fun DrawScope.drawPieces(
    world: MelonsWorld,
    sprites: MelonsSprites,
    m: Float,
    flyingRage: Int,
    at: (Float, Float) -> Offset,
) {
    val degreesOf = { radians: Float -> -radians * 180f / PI.toFloat() }
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
                val kind = piece.melonKind ?: MelonKind.Bitter
                melon(sprites, c, kind.r * m, deg, pose, if (current) flyingRage else 0, kind)
            }

            PieceKind.Seed -> {
                val kind = piece.melonKind
                if (kind == null) {
                    seed(c, MelonsWorld.SEED_R * m, deg)
                } else {
                    // A piece of a Split Cantaloupe: a small cantaloupe, knocked out once it stops.
                    val pose = if (piece.speed < 1f) MelonPose.Landed else MelonPose.Flying
                    melon(sprites, c, MelonsWorld.SPLIT_R * m, deg, pose, 0, kind)
                }
            }

            PieceKind.Banner -> {
                banner(sprites, c, piece.w * m, deg)
            }
        }
    }
}

/**
 * The structure in a framed box at the top right, drawn small from the same pieces, so the player sees what
 * they are aiming at; the label says how far it is from the slingshot.
 */
private fun DrawScope.targetView(
    world: MelonsWorld,
    sprites: MelonsSprites,
    m: Float,
    viewW: Float,
) {
    val level = world.level
    val standing = world.pieces.filter { it.kind != PieceKind.Melon && it.kind != PieceKind.Seed }
    val high = standing.maxOfOrNull { it.y + maxOf(it.w, it.h) / 2 } ?: TARGET_MIN_HIGH
    val x0 = level.fortX - TARGET_MARGIN
    val x1 = level.fortEnd + TARGET_MARGIN
    val y1 = maxOf(high, TARGET_MIN_HIGH) + TARGET_MARGIN
    val boxW = size.width * TARGET_WIDTH
    val boxH = size.height * TARGET_HEIGHT
    val k = minOf(boxW / (x1 - x0), boxH / (y1 + TARGET_GROUND))
    val left = size.width - boxW - m * 0.4f
    val top = m * 0.4f
    val ground = top + boxH - TARGET_GROUND * k
    // Centred across, with the ground near the bottom of the box.
    val originX = left + (boxW - (x1 - x0) * k) / 2 - x0 * k
    val box = Size(boxW, boxH)
    clipRect(left, top, left + boxW, top + boxH) {
        backgroundIn(
            sprites,
            level.world,
            left,
            top,
            boxW,
            boxH,
            ground,
            fortCamera(level, viewW) / (MelonsWorld.STAGE_W - viewW),
        )

        fun inBox(
            x: Float,
            y: Float,
        ) = Offset(originX + x * k, ground - y * k)
        drawPieces(world, sprites, k, 0, ::inBox)
        // The aimed arc across the window, and a ring where the melon first meets the structure or the ground.
        val (time, landX, landY) = world.landing()
        val windowX = (left - originX) / k
        var t = 0f
        while (t < time) {
            val (px, py) = world.preview(t)
            if (px >= windowX) drawCircle(MelonsArt.Ink.copy(alpha = 0.75f), m * 0.1f, inBox(px, py))
            t += TARGET_DOT_TIME
        }
        val ring = inBox(landX, landY)
        val radius = maxOf(MelonsWorld.MELON_R * k, m * 0.5f)
        drawCircle(MelonsArt.Ink, radius, ring, style = Stroke(m * 0.24f))
        drawCircle(MelonsArt.Red, radius, ring, style = Stroke(m * 0.14f))
        drawLine(MelonsArt.Red, ring - Offset(radius * 1.5f, 0f), ring + Offset(radius * 1.5f, 0f), m * 0.08f)
        drawLine(MelonsArt.Red, ring - Offset(0f, radius * 1.5f), ring + Offset(0f, radius * 1.5f), m * 0.08f)
        // A shot that comes down before the structure or past it shows a red chevron at that edge.
        if (landX < x0 || landX > x1) {
            val pointsLeft = landX < x0
            val x = if (pointsLeft) left + m * 0.3f else left + boxW - m * 0.3f
            val y = top + boxH / 2
            val dx = if (pointsLeft) m * 0.7f else -m * 0.7f
            val chevron =
                Path().apply {
                    moveTo(x, y)
                    lineTo(x + dx, y - m * 0.6f)
                    lineTo(x + dx, y + m * 0.6f)
                    close()
                }
            drawPath(chevron, MelonsArt.Red)
        }
    }
    drawRect(MelonsArt.Ink, Offset(left, top), box, style = Stroke(m * 0.12f))
    drawRect(MelonsArt.Yellow, Offset(left, top), box, style = Stroke(m * 0.05f))
    popup(
        "Target · ${(level.fortX - MelonsWorld.SLING_X).toInt()} m",
        Offset(left + boxW / 2, top + boxH + m * 1.2f),
        m * 0.9f,
        1f,
    )
}

/** The camera that frames a level's structure, for a view [viewW] metres across. */
private fun fortCamera(
    level: MelonLevel,
    viewW: Float,
) = ((level.fortX + level.fortEnd - viewW) / 2).coerceIn(0f, MelonsWorld.STAGE_W - viewW)

/** The cursor's world's nine levels in a 3 x 3 grid, with the seeds won on each; locked levels are dimmed. */
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
        val n = worldOf(cursor)
        val first = MelonsLevels.all.indexOfFirst { it.world == n }
        val (name, twist) = MelonsLevels.worlds[n - 1]
        Text("World $n · $name", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(twist, color = SelectMuted, fontSize = 12.sp, maxLines = 1)
        repeat(3) { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) { col ->
                    val index = first + row * 3 + col
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
