package org.sableos.games

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** How hard Bricks plays, picked on the title menu. Normal plays as Bricks did before levels. */
internal enum class BricksDifficulty(
    val label: String,
    val paddle: Float,
    val speed: Float,
    val perLevel: Float,
    val lives: Int,
    /** The share of capsules that help. */
    val goodChance: Float,
    val scoreTimes: Float,
) {
    Easy("Easy", 0.25f, 0.6f, 0.015f, 5, 0.8f, 0.5f),
    Normal("Normal", 0.2f, 0.7f, 0.025f, 3, 0.65f, 1f),
    Hard("Hard", 0.15f, 0.8f, 0.04f, 2, 0.45f, 2f),
}

internal enum class BrickKind { Plain, Tough, Hard, Steel, Explosive }

internal data class Brick(
    val kind: BrickKind,
    val hits: Int,
) {
    val breakable: Boolean get() = kind != BrickKind.Steel
    val points: Int
        get() =
            when (kind) {
                BrickKind.Plain -> 10
                BrickKind.Tough -> 20
                BrickKind.Hard -> 30
                BrickKind.Explosive -> 15
                BrickKind.Steel -> 0
            }
}

/** What a falling capsule does when the paddle catches it; [seconds] 0 is at once. */
internal enum class BricksPower(
    val mark: String,
    val title: String,
    val good: Boolean,
    val seconds: Float,
) {
    Wide("W", "Wide paddle", true, 15f),
    Multi("M", "Multi-ball", true, 0f),
    Slow("S", "Slow ball", true, 10f),
    Life("+1", "Extra life", true, 0f),
    Catch("C", "Catch", true, 15f),
    Laser("L", "Laser", true, 8f),
    Narrow("N", "Narrow paddle", false, 12f),
    Fast("F", "Fast ball", false, 10f),
}

/** A ball; [stuck] rides on the paddle at [offset] from its centre until launched. */
internal data class BricksBall(
    val x: Float,
    val y: Float,
    val vx: Float = 0f,
    val vy: Float = 0f,
    val stuck: Boolean = false,
    val offset: Float = 0f,
)

internal data class BricksCapsule(
    val power: BricksPower,
    val x: Float,
    val y: Float,
)

internal data class BricksShot(
    val x: Float,
    val y: Float,
)

/**
 * Bricks on a square field from (0, 0) at the top left to (1, 1), over the walls of [BricksLevels].
 * Balls bounce off the walls, the paddle (the further from its centre, the wider the angle) and the
 * bricks. Tough and hard bricks take 2 or 3 hits, steel never breaks, and an explosive clears the
 * bricks around it. About one brick in eight drops a capsule ([BricksPower]). Losing every ball costs
 * a life. Clearing every breakable brick finishes the level with up to three stars: one for clearing
 * it, one for losing no life on it, one for beating its par time.
 */
internal data class BricksGame(
    val difficulty: BricksDifficulty = BricksDifficulty.Normal,
    val levelIndex: Int = 0,
    val bricks: List<Brick?> = wallFor(BricksLevels.all[levelIndex]),
    val balls: List<BricksBall> = listOf(restingBall(0.5f)),
    val paddleX: Float = 0.5f,
    val lives: Int = difficulty.lives,
    val score: Int = 0,
    val capsules: List<BricksCapsule> = emptyList(),
    val shots: List<BricksShot> = emptyList(),
    val timers: Map<BricksPower, Float> = emptyMap(),
    /** Seconds played on this level, counted while a ball is moving. */
    val time: Float = 0f,
    /** Rows the ceiling has dropped. */
    val drop: Int = 0,
    val ceilingClock: Float = 0f,
    val lostLife: Boolean = false,
    val combo: Int = 0,
    val shotCooldown: Float = 0f,
    /** Stars won on this level once it is cleared, else null. */
    val cleared: Int? = null,
) {
    val level: BricksLevel get() = BricksLevels.all[levelIndex]
    val over: Boolean get() = lives <= 0
    val launched: Boolean get() = balls.any { !it.stuck }
    val lastLevel: Boolean get() = levelIndex == BricksLevels.all.lastIndex

    /** For the first ball, as the screen and tests read it. */
    val ballX: Float get() = balls.firstOrNull()?.x ?: paddleX
    val ballY: Float get() = balls.firstOrNull()?.y ?: (PADDLE_Y - BALL_RADIUS)
    val velY: Float get() = balls.firstOrNull()?.vy ?: 0f

    val paddleWidth: Float
        get() =
            difficulty.paddle *
                when {
                    active(BricksPower.Wide) -> WIDE
                    active(BricksPower.Narrow) -> NARROW
                    else -> 1f
                }

    fun active(power: BricksPower): Boolean = (timers[power] ?: 0f) > 0f

    /** The ball's speed now: the level's, or level 1's while slowed, a third faster while fast. */
    val speed: Float
        get() {
            val base = if (active(BricksPower.Slow)) difficulty.speed else difficulty.speed + levelIndex * difficulty.perLevel
            return if (active(BricksPower.Fast)) base * FAST else base
        }

    /** The time to beat for the third star. */
    val par: Float get() = PAR_BASE + PAR_PER_BRICK * wallFor(level).count { it?.breakable == true }

    /** How far odd rows have slid in World 4, as a share of the field. */
    fun shift(row: Int): Float = if (level.sliding && row % 2 == 1) SLIDE * sin(time * SLIDE_RATE) else 0f

    fun brickTop(row: Int): Float = WALL_TOP + (row + drop) * BRICK_HEIGHT

    /** Moves the paddle by [dx] of the field width; balls resting on it move with it. */
    fun movePaddle(dx: Float): BricksGame = paddleTo(paddleX + dx)

    fun paddleTo(x: Float): BricksGame {
        if (over || cleared != null) return this
        val half = paddleWidth / 2
        val clamped = x.coerceIn(half, 1f - half)
        return copy(paddleX = clamped, balls = balls.map { if (it.stuck) it.copy(x = clamped + it.offset) else it })
    }

    /** Space: lets go of every ball on the paddle, or fires the laser. */
    fun launch(): BricksGame {
        if (over || cleared != null) return this
        if (balls.any { it.stuck }) {
            return copy(
                balls =
                    balls.map {
                        if (!it.stuck) {
                            it
                        } else {
                            val slant = (it.offset / (paddleWidth / 2)).coerceIn(-1f, 1f) * MAX_SLANT
                            val dx = if (abs(slant) < 0.05f) LAUNCH_SLANT else slant
                            val norm = sqrt(1 + dx * dx)
                            it.copy(stuck = false, vx = speed * dx / norm, vy = -speed / norm)
                        }
                    },
            )
        }
        if (active(BricksPower.Laser) && shotCooldown <= 0f) {
            val half = paddleWidth / 2 - 0.01f
            return copy(
                shots = shots + BricksShot(paddleX - half, PADDLE_Y) + BricksShot(paddleX + half, PADDLE_Y),
                shotCooldown = SHOT_GAP,
            )
        }
        return this
    }

    /** The next level after a clear, keeping lives and score. */
    fun next(): BricksGame {
        if (cleared == null || lastLevel) return this
        return BricksGame(
            difficulty = difficulty,
            levelIndex = levelIndex + 1,
            paddleX = paddleX,
            balls = listOf(restingBall(paddleX)),
            lives = lives,
            score = score,
        )
    }

    /** Advances the game by [seconds], in small sub-steps so a ball never passes through a brick. */
    fun step(
        seconds: Float,
        random: Random = Random.Default,
    ): BricksGame {
        if (over || cleared != null) return this
        var state = this
        val steps = (seconds / MAX_SUBSTEP).toInt() + 1
        repeat(steps) { if (!state.over && state.cleared == null) state = state.substep(seconds / steps, random) }
        return state
    }

    private fun substep(
        dt: Float,
        random: Random,
    ): BricksGame {
        val moving = launched
        var wall = bricks.toMutableList()
        var points = 0
        var combo = combo
        val drops = mutableListOf<BricksCapsule>()

        fun hit(index: Int) {
            // Damage one brick; an explosive takes its neighbours with it, and they can chain.
            val queue = ArrayDeque<Int>()
            queue += index
            var first = true
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                val brick = wall[i] ?: continue
                if (!brick.breakable) continue
                val left = if (first) brick.hits - 1 else 0
                first = false
                if (left > 0) {
                    wall[i] = brick.copy(hits = left)
                    continue
                }
                wall[i] = null
                combo++
                points += brick.points + if (combo > 1) COMBO_POINTS * (combo - 1) else 0
                if (random.nextInt(DROP_ONE_IN) == 0) {
                    val good = random.nextFloat() < difficulty.goodChance
                    val pool = BricksPower.entries.filter { it.good == good }
                    val row = i / COLUMNS
                    val col = i % COLUMNS
                    drops +=
                        BricksCapsule(
                            pool[random.nextInt(pool.size)],
                            (col + 0.5f) / COLUMNS + shift(row),
                            brickTop(row) + BRICK_HEIGHT / 2,
                        )
                }
                if (brick.kind == BrickKind.Explosive) {
                    val row = i / COLUMNS
                    val col = i % COLUMNS
                    for (dr in -1..1) {
                        for (dc in -1..1) {
                            val r = row + dr
                            val c = col + dc
                            if ((dr != 0 || dc != 0) && r in 0 until ROWS && c in 0 until COLUMNS) queue += r * COLUMNS + c
                        }
                    }
                }
            }
        }

        fun cellAt(
            x: Float,
            y: Float,
        ): Int {
            val row = floor((y - WALL_TOP) / BRICK_HEIGHT).toInt() - drop
            if (row !in 0 until ROWS || y < WALL_TOP) return -1
            val col = floor((x - shift(row)) * COLUMNS).toInt()
            if (col !in 0 until COLUMNS) return -1
            val index = row * COLUMNS + col
            return if (wall[index] != null) index else -1
        }

        val width = paddleWidth
        val target = speed
        val catching = active(BricksPower.Catch)
        val nextBalls = mutableListOf<BricksBall>()
        for (ball in balls) {
            if (ball.stuck) {
                nextBalls += ball.copy(x = paddleX + ball.offset, y = PADDLE_Y - BALL_RADIUS)
                continue
            }
            // Keep the speed the level (and any power) sets, in the ball's direction.
            val now = sqrt(ball.vx * ball.vx + ball.vy * ball.vy).coerceAtLeast(0.0001f)
            var vx = ball.vx * target / now
            var vy = ball.vy * target / now
            var x = ball.x + vx * dt
            var y = ball.y + vy * dt
            if (x < BALL_RADIUS || x > 1f - BALL_RADIUS) {
                vx = -vx
                x = x.coerceIn(BALL_RADIUS, 1f - BALL_RADIUS)
            }
            if (y < BALL_RADIUS) {
                vy = abs(vy)
                y = BALL_RADIUS
            }
            if (vy > 0 && y + BALL_RADIUS >= PADDLE_Y && y < PADDLE_Y + PADDLE_HEIGHT &&
                abs(x - paddleX) <= width / 2 + BALL_RADIUS
            ) {
                combo = 0
                if (catching) {
                    nextBalls += BricksBall(x, PADDLE_Y - BALL_RADIUS, stuck = true, offset = x - paddleX)
                    continue
                }
                val offset = ((x - paddleX) / (width / 2)).coerceIn(-1f, 1f)
                vx = target * offset * MAX_SLANT
                vy = -sqrt((target * target - vx * vx).coerceAtLeast(target * target * MIN_RISE))
                y = PADDLE_Y - BALL_RADIUS
            }
            if (y > 1f) continue
            val cell = cellAt(x, y)
            if (cell >= 0) {
                // Came in from the side when the row was the same a moment ago; else from above or below.
                val fromSide = cellAt(ball.x, y) < 0 && cellAt(x, ball.y) >= 0
                if (fromSide) vx = -vx else vy = -vy
                if (wall[cell]?.breakable == true) hit(cell)
                nextBalls += BricksBall(ball.x, ball.y, vx, vy)
            } else {
                nextBalls += BricksBall(x, y, vx, vy)
            }
        }

        // Laser shots go straight up and break the first brick they meet.
        val nextShots = mutableListOf<BricksShot>()
        for (shot in shots) {
            val y = shot.y - SHOT_SPEED * dt
            if (y < 0f) continue
            val cell = cellAt(shot.x, y)
            if (cell >= 0) {
                if (wall[cell]?.breakable == true) hit(cell)
                continue
            }
            nextShots += shot.copy(y = y)
        }

        // Capsules fall; the paddle catches one when it lands on it.
        var caught = emptyList<BricksPower>()
        val nextCapsules =
            (capsules + drops).mapNotNull { c ->
                val y = c.y + CAPSULE_SPEED * dt
                if (y >= PADDLE_Y && y <= PADDLE_Y + PADDLE_HEIGHT * 2 && abs(c.x - paddleX) <= width / 2 + CAPSULE_HALF) {
                    caught = caught + c.power
                    null
                } else if (y > 1f) {
                    null
                } else {
                    c.copy(y = y)
                }
            }

        var timers = timers.mapValues { (it.value - dt).coerceAtLeast(0f) }.filterValues { it > 0f }
        var lives = lives
        var balls2: List<BricksBall> = nextBalls
        for (power in caught) {
            when (power) {
                BricksPower.Wide -> timers = timers - BricksPower.Narrow + (power to power.seconds)
                BricksPower.Narrow -> timers = timers - BricksPower.Wide + (power to power.seconds)
                BricksPower.Slow -> timers = timers - BricksPower.Fast + (power to power.seconds)
                BricksPower.Fast -> timers = timers - BricksPower.Slow + (power to power.seconds)
                BricksPower.Catch, BricksPower.Laser -> timers = timers + (power to power.seconds)
                BricksPower.Life -> lives = (lives + 1).coerceAtMost(MAX_LIVES)
                BricksPower.Multi -> balls2 = split(balls2)
            }
        }

        var next =
            copy(
                bricks = wall,
                balls = balls2,
                capsules = nextCapsules,
                shots = nextShots,
                timers = timers,
                lives = lives,
                score = score + (points * difficulty.scoreTimes).toInt(),
                combo = combo,
                time = if (moving) time + dt else time,
                shotCooldown = (shotCooldown - dt).coerceAtLeast(0f),
            )

        // World 4: the ceiling drops a row now and then; a wall that reaches the paddle costs a life.
        if (level.ceilingSeconds > 0f && moving) {
            val clock = ceilingClock + dt
            next =
                if (clock >= level.ceilingSeconds) {
                    next.copy(drop = drop + 1, ceilingClock = 0f)
                } else {
                    next.copy(ceilingClock = clock)
                }
            val lowest = wall.indices.filter { wall[it] != null }.maxOfOrNull { it / COLUMNS }
            if (lowest != null && next.brickTop(lowest) + BRICK_HEIGHT >= CEILING_LIMIT) return next.loseLife()
        }

        if (wall.none { it?.breakable == true }) {
            val stars = 1 + (if (!lostLife) 1 else 0) + (if (next.time <= par) 1 else 0)
            return next.copy(cleared = stars, balls = listOf(restingBall(paddleX)), capsules = emptyList(), shots = emptyList())
        }
        if (balls2.isEmpty()) return next.loseLife()
        return next
    }

    private fun loseLife(): BricksGame =
        copy(
            lives = lives - 1,
            balls = listOf(restingBall(paddleX)),
            capsules = emptyList(),
            shots = emptyList(),
            timers = emptyMap(),
            drop = 0,
            ceilingClock = 0f,
            lostLife = true,
            combo = 0,
        )

    private fun split(list: List<BricksBall>): List<BricksBall> {
        val out = list.toMutableList()
        for (ball in list) {
            if (ball.stuck) continue
            for (angle in listOf(-SPLIT_ANGLE, SPLIT_ANGLE)) {
                if (out.size >= MAX_BALLS) break
                val c = cos(angle)
                val s = sin(angle)
                out += ball.copy(vx = ball.vx * c - ball.vy * s, vy = ball.vx * s + ball.vy * c)
            }
        }
        return out
    }

    companion object {
        const val COLUMNS = 10
        const val ROWS = 8
        const val WALL_TOP = 0.1f
        const val BRICK_HEIGHT = 0.042f
        const val PADDLE_Y = 0.93f
        const val PADDLE_HEIGHT = 0.025f
        const val PADDLE_WIDTH = 0.2f
        const val BALL_RADIUS = 0.015f
        const val CAPSULE_HALF = 0.035f
        const val MAX_LIVES = 5
        private const val CEILING_LIMIT = 0.82f
        private const val WIDE = 1.5f
        private const val NARROW = 0.67f
        private const val FAST = 1.33f
        private const val LAUNCH_SLANT = 0.5f
        private const val MAX_SLANT = 0.8f
        private const val MIN_RISE = 0.3f
        private const val MAX_SUBSTEP = 0.004f
        private const val SLIDE = 0.08f
        private const val SLIDE_RATE = 1.2f
        private const val DROP_ONE_IN = 8
        private const val COMBO_POINTS = 5
        private const val SHOT_SPEED = 1.6f
        private const val SHOT_GAP = 0.3f
        private const val CAPSULE_SPEED = 0.35f
        private const val SPLIT_ANGLE = 0.45f
        private const val MAX_BALLS = 6
        private const val PAR_BASE = 20f
        private const val PAR_PER_BRICK = 1.5f

        fun restingBall(paddleX: Float) = BricksBall(paddleX, PADDLE_Y - BALL_RADIUS, stuck = true)

        fun start(
            levelIndex: Int,
            difficulty: BricksDifficulty,
        ): BricksGame = BricksGame(difficulty = difficulty, levelIndex = levelIndex.coerceIn(BricksLevels.all.indices))

        /** The bricks of [level], [ROWS] × [COLUMNS], row by row. */
        fun wallFor(level: BricksLevel): List<Brick?> =
            List(ROWS * COLUMNS) { i ->
                val row = i / COLUMNS
                val col = i % COLUMNS
                when (level.rows.getOrNull(row)?.getOrNull(col)) {
                    'n' -> Brick(BrickKind.Plain, 1)
                    '2' -> Brick(BrickKind.Tough, 2)
                    '3' -> Brick(BrickKind.Hard, 3)
                    's' -> Brick(BrickKind.Steel, 1)
                    'x' -> Brick(BrickKind.Explosive, 1)
                    else -> null
                }
            }
    }
}

/** Stars per level and the best score per difficulty, kept on the phone. */
internal data class BricksProgress(
    val stars: List<Int> = List(BricksLevels.all.size) { 0 },
    val best: Map<BricksDifficulty, Int> = emptyMap(),
) {
    /** A level opens when the one before it has a star; the first is always open. */
    fun open(index: Int): Boolean = index == 0 || stars.getOrElse(index - 1) { 0 } > 0

    fun record(
        index: Int,
        won: Int,
    ): BricksProgress = copy(stars = stars.mapIndexed { i, s -> if (i == index) maxOf(s, won) else s })

    fun score(
        difficulty: BricksDifficulty,
        score: Int,
    ): BricksProgress = if (score > (best[difficulty] ?: 0)) copy(best = best + (difficulty to score)) else this

    fun encode(): String = stars.joinToString("") + "|" + BricksDifficulty.entries.joinToString(",") { (best[it] ?: 0).toString() }

    companion object {
        fun decode(text: String?): BricksProgress {
            val parts = text?.split('|') ?: return BricksProgress()
            val stars = parts[0].map { (it - '0').coerceIn(0, 3) }
            if (stars.size != BricksLevels.all.size) return BricksProgress()
            val scores = parts.getOrNull(1)?.split(',')?.map { it.toIntOrNull() ?: 0 }.orEmpty()
            val best = BricksDifficulty.entries.zip(scores).toMap().filterValues { it > 0 }
            return BricksProgress(stars, best)
        }
    }
}
