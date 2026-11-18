package org.sableos.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.sableos.games.GamesText.t
import kotlin.math.abs
import kotlin.random.Random

/** One colour per shape (I, O, T, S Z, J, L), bright on the dark well, used for the brick rows too. */
private val PieceColors =
    listOf(
        Color(0xFF7AA2FF),
        Color(0xFFFFC72C),
        Color(0xFFFF5A8A),
        Color(0xFF8BE04E),
        Color(0xFF2FD0B5),
        Color(0xFFC58BFF),
        Color(0xFFFF8A3D),
    )

private val ShapeNames = listOf("I", "O", "T", "S", "Z", "J", "L")

private const val PADDLE_STEP = 0.06f

/** How much longer (slow) or shorter (fast) each Snake step is than normal. */
private val SNAKE_PACE = floatArrayOf(1.4f, 1f, 0.7f)
private const val SWIPE_PX = 32f
private const val NANOS_PER_SECOND = 1_000_000_000f
private const val MAX_FRAME_SECONDS = 0.05f

/** The board's frame: a square surface with the theme's outline, as in the other Sable games. */
@Composable
private fun ArcadeBoard(
    side: Dp,
    modifier: Modifier = Modifier,
    draw: DrawScope.() -> Unit,
) {
    Surface(
        modifier = modifier.size(side),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        tonalElevation = 0.dp,
    ) {
        Canvas(Modifier.size(side), onDraw = draw)
    }
}

/** A run state for the timed games: playing, paused by P, or finished. */
private fun runStatus(
    over: Boolean,
    paused: Boolean,
    playing: String,
): String =
    when {
        over -> t("game_over_space_plays_again")
        paused -> t("paused_p_resumes")
        else -> playing
    }

@Composable
internal fun SnakeScreen() {
    val random = remember { Random(System.nanoTime()) }
    var game by remember { mutableStateOf(SnakeGame.start(random)) }
    var paused by remember { mutableStateOf(false) }
    var best by remember { mutableIntStateOf(0) }

    fun reset() {
        game = SnakeGame.start(random)
        paused = false
    }

    val shell = LocalGameShell.current
    val held = paused || shell?.suspended == true
    // Speed on the title menu: slow, normal or fast.
    val pace = SNAKE_PACE[shell?.option ?: 1]
    LaunchedEffect(game.alive, held) {
        while (game.alive && !held) {
            delay((game.stepMillis * pace).toLong())
            game = game.step(random)
            if (game.score > best) best = game.score
        }
    }

    GameKeyHandler(GameKind.Snake) { command ->
        when (command) {
            is GameCommand.Move -> game = game.turn(command.dx, command.dy)
            GameCommand.Pause -> paused = !paused && game.alive
            GameCommand.Primary -> if (!game.alive) reset() else paused = false
            GameCommand.NewGame -> reset()
            else -> return@GameKeyHandler false
        }
        true
    }

    GameFrame(
        title = t("snake"),
        subtitle = t("eat_grow_and_keep_clear"),
        statusLabel = t("score_best", best),
        status = runStatus(!game.alive, paused, game.score.toString()),
        hint = t("arrows_or_the_trackpad_turn"),
        actions =
            listOf(
                GameAction(tab = true, label = if (paused) t("resume") else t("pause"), key = "P", active = paused) {
                    paused =
                        !paused && game.alive
                },
                GameAction(tab = true, label = t("new_label"), key = "N", onClick = ::reset),
            ),
    ) { side ->
        val colors = MaterialTheme.colorScheme
        ArcadeBoard(
            side = side,
            modifier =
                Modifier.pointerInput(Unit) {
                    var drag = Offset.Zero
                    detectDragGestures(
                        onDragStart = { drag = Offset.Zero },
                        onDragEnd = {
                            if (abs(drag.x) > abs(drag.y) && abs(drag.x) > SWIPE_PX) {
                                game = game.turn(if (drag.x > 0) 1 else -1, 0)
                            } else if (abs(drag.y) > SWIPE_PX) {
                                game = game.turn(0, if (drag.y > 0) 1 else -1)
                            }
                        },
                    ) { _, amount -> drag += amount }
                },
        ) {
            val cell = size.width / game.size

            fun square(
                at: Cell,
                color: Color,
            ) = drawRoundRect(
                color = color,
                topLeft = Offset(at.x * cell + 1f, at.y * cell + 1f),
                size = Size(cell - 2f, cell - 2f),
                cornerRadius = CornerRadius(cell / 4),
            )
            square(game.food, PieceColors[4])
            game.body.forEachIndexed { index, part ->
                square(
                    part,
                    if (index ==
                        0
                    ) {
                        colors.primary
                    } else {
                        colors.primary.copy(alpha = 0.6f)
                    },
                )
            }
        }
    }
}

@Composable
internal fun BlocksScreen() {
    val random = remember { Random(System.nanoTime()) }
    var game by remember { mutableStateOf(BlocksGame.start(random)) }
    var paused by remember { mutableStateOf(false) }

    fun reset() {
        game = BlocksGame.start(random)
        paused = false
    }

    val held = paused || LocalGameShell.current?.suspended == true
    LaunchedEffect(game.over, held) {
        while (!game.over && !held) {
            delay(game.fallMillis)
            game = game.fall(random)
        }
    }

    GameKeyHandler(GameKind.Blocks) { command ->
        if (paused && command != GameCommand.Pause && command != GameCommand.NewGame) return@GameKeyHandler true
        when (command) {
            is GameCommand.Move -> {
                game =
                    when {
                        command.dx != 0 -> game.move(command.dx)
                        command.dy < 0 -> game.rotate()
                        else -> game.fall(random)
                    }
            }

            GameCommand.Primary -> {
                if (game.over) reset() else game = game.drop(random)
            }

            GameCommand.Pause -> {
                paused = !paused && !game.over
            }

            GameCommand.NewGame -> {
                reset()
            }

            else -> {
                return@GameKeyHandler false
            }
        }
        true
    }

    GameFrame(
        title = t("blocks"),
        subtitle = t("fit_the_falling_pieces_into"),
        statusLabel = "level ${game.level} · ${game.lines % 10}/10 rows · next ${ShapeNames[game.next]}",
        status = runStatus(game.over, paused, game.score.toString()),
        hint = t("left_right_move_up_turns"),
        actions =
            listOf(
                GameAction(tab = true, label = if (paused) t("resume") else t("pause"), key = "P", active = paused) {
                    paused =
                        !paused && !game.over
                },
                GameAction(tab = true, label = t("new_label"), key = "N", onClick = ::reset),
            ),
    ) { side ->
        val colors = MaterialTheme.colorScheme
        ArcadeBoard(
            side = side,
            modifier =
                Modifier
                    .pointerInput(Unit) { detectTapGestures { game = game.rotate() } }
                    .pointerInput(Unit) {
                        var drag = Offset.Zero
                        detectDragGestures(
                            onDragStart = { drag = Offset.Zero },
                            onDragEnd = {
                                game =
                                    when {
                                        abs(drag.x) > abs(drag.y) && abs(drag.x) > SWIPE_PX -> {
                                            game.move(
                                                if (drag.x >
                                                    0
                                                ) {
                                                    1
                                                } else {
                                                    -1
                                                },
                                            )
                                        }

                                        drag.y > SWIPE_PX -> {
                                            game.drop(random)
                                        }

                                        else -> {
                                            game
                                        }
                                    }
                            },
                        ) { _, amount -> drag += amount }
                    },
        ) {
            val cell = size.width / BlocksGame.WIDTH
            drawRect(BlocksWell)
            for (i in 1 until BlocksGame.WIDTH) {
                drawLine(BlocksGrid, Offset(i * cell, 0f), Offset(i * cell, size.height))
                drawLine(BlocksGrid, Offset(0f, i * cell), Offset(size.width, i * cell))
            }

            fun block(
                x: Int,
                y: Int,
                color: Color,
            ) = drawRoundRect(
                color,
                Offset(x * cell + 1f, y * cell + 1f),
                Size(cell - 2f, cell - 2f),
                CornerRadius(cell / 6),
            )
            game.well.forEachIndexed { y, row ->
                row.forEachIndexed { x, v -> if (v > 0) block(x, y, PieceColors[v - 1]) }
            }
            val pieceColor = PieceColors[game.piece.kind]
            game
                .ghost()
                .absolute
                .filter { it.y >= 0 }
                .forEach {
                    drawRoundRect(
                        pieceColor.copy(alpha = 0.75f),
                        Offset(it.x * cell + 2f, it.y * cell + 2f),
                        Size(cell - 4f, cell - 4f),
                        CornerRadius(cell / 6),
                        style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))),
                    )
                }
            game.piece.absolute
                .filter { it.y >= 0 }
                .forEach { block(it.x, it.y, pieceColor) }
        }
    }
}

private val BlocksWell = Color(0xFF141C40)
private val BlocksGrid = Color(0xFF1D2650)

@Composable
internal fun BricksScreen() {
    var game by remember { mutableStateOf(BricksGame()) }
    var paused by remember { mutableStateOf(false) }

    fun reset() {
        game = BricksGame()
        paused = false
    }

    val held = paused || LocalGameShell.current?.suspended == true
    LaunchedEffect(game.launched, held, game.over) {
        var last = 0L
        while (game.launched && !held && !game.over) {
            withFrameNanos { now ->
                if (last != 0L) {
                    game = game.step(((now - last) / NANOS_PER_SECOND).coerceAtMost(MAX_FRAME_SECONDS))
                }
                last = now
            }
        }
    }

    GameKeyHandler(GameKind.Bricks) { command ->
        when (command) {
            is GameCommand.Move -> if (!paused && command.dx != 0) game = game.movePaddle(command.dx * PADDLE_STEP)
            GameCommand.Primary -> if (game.over) reset() else game = game.launch()
            GameCommand.Pause -> paused = !paused && game.launched
            GameCommand.NewGame -> reset()
            else -> return@GameKeyHandler false
        }
        true
    }

    GameFrame(
        title = t("bricks"),
        subtitle = t("keep_the_ball_in_play"),
        statusLabel = t("score_lives_level", game.lives, game.level),
        status =
            when {
                game.over -> t("game_over_space_plays_again")
                paused -> t("paused_p_resumes")
                !game.launched -> t("space_launches", game.score)
                else -> game.score.toString()
            },
        hint = t("left_right_or_the_trackpad"),
        actions =
            listOf(
                GameAction(tab = true, label = if (paused) t("resume") else t("pause"), key = "P", active = paused) {
                    paused =
                        !paused && game.launched
                },
                GameAction(tab = true, label = t("new_label"), key = "N", onClick = ::reset),
            ),
    ) { side ->
        val colors = MaterialTheme.colorScheme
        ArcadeBoard(
            side = side,
            modifier =
                Modifier
                    .pointerInput(Unit) { detectTapGestures { game = if (game.over) BricksGame() else game.launch() } }
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ -> game = game.paddleTo(change.position.x / size.width) }
                    },
        ) {
            val w = size.width
            val gap = 2f
            val brickW = w / BricksGame.COLUMNS
            val brickH = w * BricksGame.BRICK_HEIGHT
            game.bricks.forEachIndexed { index, hits ->
                if (hits <= 0) return@forEachIndexed
                val row = index / BricksGame.COLUMNS
                val col = index % BricksGame.COLUMNS
                drawRoundRect(
                    PieceColors[row % PieceColors.size],
                    Offset(col * brickW + gap, w * BricksGame.WALL_TOP + row * brickH + gap),
                    Size(brickW - gap * 2, brickH - gap * 2),
                    CornerRadius(4f),
                )
            }
            val paddleW = w * BricksGame.PADDLE_WIDTH
            drawRoundRect(
                colors.primary,
                Offset(game.paddleX * w - paddleW / 2, w * BricksGame.PADDLE_Y),
                Size(paddleW, w * BricksGame.PADDLE_HEIGHT),
                CornerRadius(w * BricksGame.PADDLE_HEIGHT / 2),
            )
            drawCircle(colors.onSurface, w * BricksGame.BALL_RADIUS, Offset(game.ballX * w, game.ballY * w))
        }
    }
}
