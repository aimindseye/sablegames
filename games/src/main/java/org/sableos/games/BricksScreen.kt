package org.sableos.games

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sableos.games.GamesText.t
import kotlin.math.roundToInt
import kotlin.random.Random

private const val BRICKS_PADDLE_STEP = 0.06f
private const val BRICKS_NANOS = 1_000_000_000f
private const val BRICKS_MAX_FRAME = 0.05f
private const val BRICKS_PREFS = "sable_bricks"

/** Brick rows from rose at the top to violet at the bottom, on a deep violet field. */
private val BrickRows =
    listOf(
        Color(0xFFFF5A8A),
        Color(0xFFFF8A3D),
        Color(0xFFFFC72C),
        Color(0xFF8BE04E),
        Color(0xFF2FD0B5),
        Color(0xFF7AA2FF),
        Color(0xFFC58BFF),
        Color(0xFFFF5A8A),
    )
private val BricksField = Color(0xFF1D1540)
private val BricksPaddle = Color(0xFF2FD0B5)
private val BricksEdge = Color(0xFF3A2E6E)
private val BricksInk = Color(0xFF0E0920)
private val BricksMuted = Color(0xFFC3CBE0)
private val BricksGold = Color(0xFFFFC72C)
private val BricksOrange = Color(0xFFFF8A3D)
private val BricksSteel = Color(0xFF8C93A8)
private val BricksSteelEdge = Color(0xFFD5D9E6)
private val BricksBoom = Color(0xFFFF5A4E)
private val BricksBoomEdge = Color(0xFFFFE0A0)
private val BricksGood = Color(0xFF2FD0B5)

private fun loadProgress(context: Context): BricksProgress =
    BricksProgress.decode(context.getSharedPreferences(BRICKS_PREFS, Context.MODE_PRIVATE).getString("progress", null))

private fun saveProgress(
    context: Context,
    progress: BricksProgress,
) = context
    .getSharedPreferences(BRICKS_PREFS, Context.MODE_PRIVATE)
    .edit()
    .putString("progress", progress.encode())
    .apply()

private fun stars(n: Int) = "★".repeat(n) + "☆".repeat(3 - n)

@Composable
internal fun BricksScreen() {
    val context = LocalContext.current
    val random = remember { Random(System.nanoTime()) }
    val shell = LocalGameShell.current
    val difficulty = BricksDifficulty.entries[(shell?.option ?: 1).coerceIn(BricksDifficulty.entries.indices)]
    var progress by remember { mutableStateOf(loadProgress(context)) }
    var game by remember { mutableStateOf(BricksGame.start(0, difficulty)) }
    var paused by remember { mutableStateOf(false) }
    // The level select shows first, after a game over and on New.
    var selecting by remember { mutableStateOf(true) }
    var cursor by remember { mutableIntStateOf(progress.stars.indexOfFirst { it == 0 }.coerceAtLeast(0)) }

    fun play(index: Int) {
        if (!progress.open(index)) return
        game = BricksGame.start(index, difficulty)
        paused = false
        selecting = false
    }

    fun select() {
        progress = progress.score(game.difficulty, game.score)
        saveProgress(context, progress)
        cursor = game.levelIndex
        selecting = true
    }

    fun update(next: BricksGame) {
        val wasCleared = game.cleared
        game = next
        if (wasCleared == null && next.cleared != null) {
            progress = progress.record(next.levelIndex, next.cleared).score(next.difficulty, next.score)
            saveProgress(context, progress)
        }
        if (!game.over || selecting) return
        progress = progress.score(game.difficulty, game.score)
        saveProgress(context, progress)
    }

    fun primary() {
        when {
            selecting -> play(cursor)
            game.over -> select()
            game.cleared != null -> if (game.lastLevel) select() else update(game.next())
            else -> update(game.launch())
        }
    }

    val held = paused || shell?.suspended == true || selecting
    LaunchedEffect(game.over, game.cleared, held) {
        var last = 0L
        while (!game.over && game.cleared == null && !held) {
            withFrameNanos { now ->
                if (last != 0L) update(game.step(((now - last) / BRICKS_NANOS).coerceAtMost(BRICKS_MAX_FRAME), random))
                last = now
            }
        }
    }

    GameKeyHandler(GameKind.Bricks) { command ->
        when (command) {
            is GameCommand.Move -> {
                if (selecting) {
                    val next = (cursor + command.dx + command.dy * BricksLevels.PER_WORLD).coerceIn(BricksLevels.all.indices)
                    if (progress.open(next)) cursor = next
                } else if (!paused && command.dx != 0) {
                    update(game.movePaddle(command.dx * BRICKS_PADDLE_STEP))
                }
            }

            GameCommand.Primary -> {
                primary()
            }

            GameCommand.Pause -> {
                paused = !paused && game.launched && !selecting
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

    val level = game.level
    val powers =
        game.timers.entries
            .filter { it.value > 0f }
            .joinToString(" ") { "${it.key.mark} ${it.value.roundToInt()}s" }
    GameFrame(
        title = t("bricks"),
        subtitle = t("keep_the_ball_in_play"),
        statusLabel =
            if (selecting) {
                "${difficulty.label} · best ${progress.best[difficulty] ?: 0}"
            } else {
                "♥".repeat(game.lives.coerceAtLeast(0)) +
                    " · ${level.world}-${level.number} ${level.name}" +
                    (if (powers.isNotEmpty()) " · $powers" else "")
            },
        status =
            when {
                selecting -> "Pick a level · Enter plays"
                game.over -> "Game over · ${game.score} · Space for levels"
                game.cleared != null && game.lastLevel -> "Every wall cleared! ${stars(game.cleared!!)} · Space"
                game.cleared != null -> "Cleared ${stars(game.cleared!!)} · Space for the next wall"
                paused -> t("paused_p_resumes")
                !game.launched -> t("space_launches", game.score)
                else -> game.score.toString()
            },
        hint = t("left_right_or_the_trackpad"),
        actions =
            listOf(
                GameAction(tab = true, label = if (paused) t("resume") else t("pause"), key = "P", active = paused) {
                    paused = !paused && game.launched && !selecting
                },
                GameAction(tab = true, label = "Levels", key = "N", onClick = ::select),
            ),
    ) { side ->
        if (selecting) {
            BricksLevelSelect(side.value, progress, cursor) { index ->
                cursor = index
                play(index)
            }
            return@GameFrame
        }
        val measurer = rememberTextMeasurer()
        ArcadeBoard(
            side = side,
            modifier =
                Modifier
                    .pointerInput(Unit) { detectTapGestures { primary() } }
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ -> update(game.paddleTo(change.position.x / size.width)) }
                    },
        ) {
            val w = size.width
            val gap = 2f
            val brickW = w / BricksGame.COLUMNS
            val brickH = w * BricksGame.BRICK_HEIGHT
            // The field first: drawn later, it would cover the bricks.
            drawRect(BricksField)
            game.bricks.forEachIndexed { index, brick ->
                if (brick == null) return@forEachIndexed
                val row = index / BricksGame.COLUMNS
                val col = index % BricksGame.COLUMNS
                val topLeft = Offset((col / BricksGame.COLUMNS.toFloat() + game.shift(row)) * w + gap, game.brickTop(row) * w + gap)
                val box = Size(brickW - gap * 2, brickH - gap * 2)
                val fill =
                    when (brick.kind) {
                        BrickKind.Plain -> BrickRows[row % BrickRows.size]
                        BrickKind.Tough -> BricksGold
                        BrickKind.Hard -> BricksOrange
                        BrickKind.Steel -> BricksSteel
                        BrickKind.Explosive -> BricksBoom
                    }
                drawRoundRect(fill, topLeft, box, CornerRadius(4f))
                when (brick.kind) {
                    BrickKind.Steel -> {
                        drawRoundRect(BricksSteelEdge, topLeft, box, CornerRadius(4f), style = Stroke(2f))
                    }

                    BrickKind.Explosive -> {
                        drawRoundRect(
                            BricksBoomEdge,
                            topLeft,
                            box,
                            CornerRadius(4f),
                            style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 3f))),
                        )
                    }

                    else -> {}
                }
                val mark =
                    when {
                        brick.kind == BrickKind.Explosive -> "✱"
                        brick.kind == BrickKind.Tough || brick.kind == BrickKind.Hard -> brick.hits.toString()
                        else -> null
                    }
                if (mark != null) {
                    val text = measurer.measure(mark, TextStyle(fontSize = (brickH * 0.55f / density).sp, fontWeight = FontWeight.Bold))
                    drawText(
                        text,
                        color = BricksInk,
                        topLeft =
                            Offset(
                                topLeft.x + (box.width - text.size.width) / 2,
                                topLeft.y + (box.height - text.size.height) / 2,
                            ),
                    )
                }
            }
            game.capsules.forEach { c ->
                val cw = w * BricksGame.CAPSULE_HALF * 2
                val ch = w * 0.03f
                val at = Offset(c.x * w - cw / 2, c.y * w - ch / 2)
                drawRoundRect(if (c.power.good) BricksGood else BricksBoom, at, Size(cw, ch), CornerRadius(ch / 2))
                val text = measurer.measure(c.power.mark, TextStyle(fontSize = (ch * 0.7f / density).sp, fontWeight = FontWeight.Bold))
                drawText(
                    text,
                    color = BricksInk,
                    topLeft = Offset(at.x + (cw - text.size.width) / 2, at.y + (ch - text.size.height) / 2),
                )
            }
            game.shots.forEach { s -> drawRect(Color.White, Offset(s.x * w - 1.5f, s.y * w), Size(3f, w * 0.02f)) }
            val paddleW = w * game.paddleWidth
            drawRoundRect(
                if (game.active(BricksPower.Narrow)) BricksBoom else BricksPaddle,
                Offset(game.paddleX * w - paddleW / 2, w * BricksGame.PADDLE_Y),
                Size(paddleW, w * BricksGame.PADDLE_HEIGHT),
                CornerRadius(w * BricksGame.PADDLE_HEIGHT / 2),
            )
            game.balls.forEach { b ->
                // A faint halo, so the ball never gets lost against a brick.
                drawCircle(Color.White.copy(alpha = 0.25f), w * BricksGame.BALL_RADIUS * 1.6f, Offset(b.x * w, b.y * w))
                drawCircle(Color.White, w * BricksGame.BALL_RADIUS, Offset(b.x * w, b.y * w))
            }
        }
    }
}

/** The four worlds of five levels, with the stars won on each; locked levels are dimmed. */
@Composable
private fun BricksLevelSelect(
    side: Float,
    progress: BricksProgress,
    cursor: Int,
    onPick: (Int) -> Unit,
) {
    Column(
        Modifier
            .size(side.dp)
            .background(BricksField, RoundedCornerShape(12.dp))
            .border(1.dp, BricksEdge, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BricksLevels.worlds.forEachIndexed { w, (name, twist) ->
            val first = w * BricksLevels.PER_WORLD
            val won = progress.stars.subList(first, first + BricksLevels.PER_WORLD).sum()
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("World ${w + 1} · $name", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Box(Modifier.weight(1f))
                    Text("$won/15 ★", color = BricksGold, fontSize = 12.sp)
                }
                Text(twist, color = BricksMuted, fontSize = 11.sp, maxLines = 1)
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(BricksLevels.PER_WORLD) { n ->
                        val index = first + n
                        val open = progress.open(index)
                        val here = index == cursor
                        Column(
                            Modifier
                                .weight(1f)
                                .height(40.dp)
                                .background(if (here) Color(0xFF2A1F55) else BricksInk, RoundedCornerShape(6.dp))
                                .border(if (here) 2.dp else 1.dp, if (here) BricksOrange else BricksEdge, RoundedCornerShape(6.dp))
                                .clickable(enabled = open) { onPick(index) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                BricksLevels.all[index].name,
                                color = if (open) Color.White else BricksMuted.copy(alpha = 0.6f),
                                fontSize = 11.sp,
                                maxLines = 1,
                            )
                            Text(
                                if (open) stars(progress.stars[index]) else "locked",
                                color = if (open) BricksGold else BricksMuted.copy(alpha = 0.6f),
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
