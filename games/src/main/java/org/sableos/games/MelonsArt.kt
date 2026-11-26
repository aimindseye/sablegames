package org.sableos.games

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/** A melon's pose: in the pouch or the queue, in the air, or come to rest. */
internal enum class MelonPose { Ready, Flying, Landed }

/**
 * Bitter Melons' sprites, cut from the owner's art sheets (2026-11-25) by tools/melons/cut_sprites.py: the
 * Bitter Melon in three poses and four rage levels, the Split Cantaloupe and Heavy Honeydew in three poses, the
 * five enemies whole and hit, glass, wood, stone and steel in four wear stages, the slingshot, the AD banner, the
 * cooling fan, the hanging beam and each world's background.
 * Each sheet's sprites share one scale.
 */
internal class MelonsSprites(
    private val load: (Int) -> ImageBitmap,
) {
    private fun all(vararg ids: Int) = ids.map(load)

    private val worlds = all(R.drawable.melons_world1, R.drawable.melons_world2, R.drawable.melons_world3)
    val slingshot = load(R.drawable.melons_slingshot)
    val banner = load(R.drawable.melons_banner)
    val fan = load(R.drawable.melons_fan)
    val beam = load(R.drawable.melons_beam)
    private val ready =
        all(
            R.drawable.melons_melon_ready_0,
            R.drawable.melons_melon_ready_1,
            R.drawable.melons_melon_ready_2,
            R.drawable.melons_melon_ready_3,
        )
    private val flying =
        all(
            R.drawable.melons_melon_flying_0,
            R.drawable.melons_melon_flying_1,
            R.drawable.melons_melon_flying_2,
            R.drawable.melons_melon_flying_3,
        )
    private val landed =
        all(
            R.drawable.melons_melon_landed_0,
            R.drawable.melons_melon_landed_1,
            R.drawable.melons_melon_landed_2,
            R.drawable.melons_melon_landed_3,
        )

    /** The Split Cantaloupe: ready, flying and landed. */
    private val split = all(R.drawable.melons_split_ready, R.drawable.melons_split_flying, R.drawable.melons_split_landed)

    /** The Heavy Honeydew: ready, flying and landed. */
    private val heavy = all(R.drawable.melons_heavy_ready, R.drawable.melons_heavy_flying, R.drawable.melons_heavy_landed)
    private val app = all(R.drawable.melons_enemy_app, R.drawable.melons_enemy_app_hit)
    private val bot = all(R.drawable.melons_enemy_bot, R.drawable.melons_enemy_bot_hit)
    private val ad = all(R.drawable.melons_enemy_ad, R.drawable.melons_enemy_ad_hit)
    private val battery = all(R.drawable.melons_enemy_battery, R.drawable.melons_enemy_battery_hit)
    private val prompt = all(R.drawable.melons_enemy_prompt, R.drawable.melons_enemy_prompt_hit)
    private val glass =
        all(
            R.drawable.melons_block_glass_0,
            R.drawable.melons_block_glass_1,
            R.drawable.melons_block_glass_2,
            R.drawable.melons_block_glass_3,
        )
    private val wood =
        all(
            R.drawable.melons_block_wood_0,
            R.drawable.melons_block_wood_1,
            R.drawable.melons_block_wood_2,
            R.drawable.melons_block_wood_3,
        )
    private val stone =
        all(
            R.drawable.melons_block_stone_0,
            R.drawable.melons_block_stone_1,
            R.drawable.melons_block_stone_2,
            R.drawable.melons_block_stone_3,
        )
    private val steel =
        all(
            R.drawable.melons_block_steel_0,
            R.drawable.melons_block_steel_1,
            R.drawable.melons_block_steel_2,
            R.drawable.melons_block_steel_3,
        )

    /** World [n]'s background, from 1. */
    fun world(n: Int): ImageBitmap = worlds[(n - 1).coerceIn(worlds.indices)]

    /** A melon of [kind] in [pose]; the Bitter Melon's [rage] 0-3 grows with each melon fired and each world. */
    fun melon(
        pose: MelonPose,
        rage: Int,
        kind: MelonKind = MelonKind.Bitter,
    ): ImageBitmap {
        if (kind == MelonKind.Split) return split[pose.ordinal]
        if (kind == MelonKind.Heavy) return heavy[pose.ordinal]
        return when (pose) {
            MelonPose.Ready -> ready
            MelonPose.Flying -> flying
            MelonPose.Landed -> landed
        }[rage.coerceIn(0, MAX_RAGE)]
    }

    /** The enemy's two sprites, whole then hit. */
    fun enemy(
        kind: PieceKind,
        slop: SlopKind?,
    ): List<ImageBitmap> =
        when {
            kind == PieceKind.Battery -> battery
            slop == SlopKind.Bot -> bot
            slop == SlopKind.Ad -> ad
            slop == SlopKind.Prompt -> prompt
            else -> app
        }

    /** A block tile: [wear] 0 is whole, 3 is nearly broken. Fixed ground pieces are stone. */
    fun block(
        material: MelonMaterial?,
        wear: Int,
    ): ImageBitmap =
        when (material) {
            MelonMaterial.Glass -> glass
            MelonMaterial.Wood -> wood
            MelonMaterial.Steel -> steel
            else -> stone
        }[wear.coerceIn(0, 3)]

    companion object {
        const val MAX_RAGE = 3
    }
}

/** Loads the sprites once per screen. */
@Composable
internal fun rememberMelonsSprites(): MelonsSprites {
    val resources = LocalContext.current.resources
    return remember(resources) {
        MelonsSprites { id ->
            ImageBitmap.imageResource(resources, id)
        }
    }
}

internal object MelonsArt {
    val Ink = Color(0xFF1C2331)
    val Red = Color(0xFFFF3B4E)
    val Yellow = Color(0xFFFFD400)
    private val Band = Color(0xFF4A2E17)

    /** The melon's round body, in sprite pixels: its sheet draws a melon about 100 px across at our scale. */
    private const val MELON_BODY_PX = 100f

    /** Where each world's ground starts, as a share of its background's height: the path, then the mall floor. */
    private val PATH_TOP = floatArrayOf(0.86f, 0.9f, 0.8f)

    private fun pathTop(world: Int) = PATH_TOP[(world - 1).coerceIn(PATH_TOP.indices)]

    /** Where the slingshot's band is tied, as a share of its sprite, and where its roots end. */
    private const val SLING_TIE_Y = 0.14f
    private const val SLING_LEFT_X = 0.36f
    private const val SLING_RIGHT_X = 0.88f
    private const val SLING_FOOT_Y = 0.97f

    private val labelPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

    private fun DrawScope.sprite(
        image: ImageBitmap,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
    ) {
        drawImage(
            image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = IntSize(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.Medium,
        )
    }

    /**
     * World 1's hills, filling the height with the dirt path at [groundY], sliding across as the camera goes
     * from 0 to [maxCameraX] metres so the whole picture is seen once.
     */
    fun DrawScope.background(
        sprites: MelonsSprites,
        world: Int,
        cameraX: Float,
        maxCameraX: Float,
        groundY: Float,
    ) {
        val image = sprites.world(world)
        val path = pathTop(world)
        val k = maxOf(groundY / (path * image.height), size.height / image.height)
        val width = image.width * k
        val slack = (width - size.width).coerceAtLeast(0f)
        val shift = if (maxCameraX > 0f) slack * (cameraX / maxCameraX).coerceIn(0f, 1f) else 0f
        sprite(image, -shift, groundY - path * image.height * k, width, image.height * k)
    }

    /**
     * The same background inside a [width] by [height] box at [left], [top], with the path at [groundY]; [slide]
     * 0-1 picks which part of the picture shows, as the camera's position would.
     */
    fun DrawScope.backgroundIn(
        sprites: MelonsSprites,
        world: Int,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        groundY: Float,
        slide: Float,
    ) {
        val image = sprites.world(world)
        val path = pathTop(world)
        val k = maxOf((groundY - top) / (path * image.height), height / image.height)
        val w = image.width * k
        val shift = (w - width).coerceAtLeast(0f) * slide.coerceIn(0f, 1f)
        sprite(image, left - shift, groundY - path * image.height * k, w, image.height * k)
    }

    /** A melon of radius [r] px at [center], turned by [degrees]; a landed melon rests on the bottom of its circle. */
    fun DrawScope.melon(
        sprites: MelonsSprites,
        center: Offset,
        r: Float,
        degrees: Float,
        pose: MelonPose,
        rage: Int,
        kind: MelonKind = MelonKind.Bitter,
    ) {
        val image = sprites.melon(pose, rage, kind)
        val k = 2f * r / MELON_BODY_PX
        val w = image.width * k
        val h = image.height * k
        if (pose == MelonPose.Landed) {
            // The squashed melon sits upright on the ground under its circle.
            sprite(image, center.x - w / 2, center.y + r - h * 0.94f, w, h)
            return
        }
        rotate(degrees, center) {
            // The stem sits above the body, so the body's middle is a little below the sprite's.
            sprite(image, center.x - w / 2, center.y - h * 0.56f, w, h)
        }
    }

    /**
     * A gold AD banner [w] px long at [center], turned by [degrees]: the trampoline's pad lies along the plate and
     * its springs stand off behind it, squashed so they stay short.
     */
    fun DrawScope.banner(
        sprites: MelonsSprites,
        center: Offset,
        w: Float,
        degrees: Float,
    ) {
        val image = sprites.banner
        val h = w * BANNER_HEIGHT
        rotate(degrees, center) {
            sprite(image, center.x - w / 2, center.y - h * BANNER_PAD_Y, w, h)
        }
    }

    /**
     * A cooling fan on the floor at [base] ([w] px wide, its updraft [high] px tall): a pale column with streaks
     * rising through it at [time] seconds, and the fan housing at its foot.
     */
    fun DrawScope.fan(
        sprites: MelonsSprites,
        base: Offset,
        w: Float,
        high: Float,
        time: Float,
    ) {
        drawRect(Updraft.copy(alpha = 0.12f), Offset(base.x - w / 2, base.y - high), Size(w, high))
        val streak = high / FAN_STREAKS
        for (i in 0 until FAN_STREAKS) {
            val rise = ((i + time * FAN_STREAK_SPEED) % FAN_STREAKS) * streak
            val x = base.x + w * ((i * 0.37f) % 1f - 0.5f) * 0.8f
            val y = base.y - rise
            drawLine(Updraft.copy(alpha = 0.45f), Offset(x, y), Offset(x, y - streak * 0.5f), w * 0.03f, StrokeCap.Round)
        }
        val image = sprites.fan
        val fw = w * FAN_SIZE
        val fh = fw * image.height / image.width
        sprite(image, base.x - fw / 2, base.y - fh, fw, fh)
    }

    /**
     * A beam still on its cables, [w] by [h] px with its middle at [center]: the beam sprite's I-beam over the
     * piece, and its two cables running on up to the top of the screen.
     */
    fun DrawScope.hangingBeam(
        sprites: MelonsSprites,
        center: Offset,
        w: Float,
        h: Float,
    ) {
        val image = sprites.beam
        // The sprite's I-beam (its lower part) is drawn over the piece, a little deeper than it; the hooks above.
        val iw = w / BEAM_SHARE
        val ih = h * BEAM_DEEP / BEAM_PART
        val top = center.y + h / 2 * BEAM_DEEP - ih
        sprite(image, center.x - iw / 2, top, iw, ih)
        for (side in listOf(CABLE_LEFT, CABLE_RIGHT)) {
            val x = center.x - iw / 2 + iw * side
            drawLine(Ink, Offset(x, 0f), Offset(x, top + ih * 0.02f), (h * 0.08f).coerceAtLeast(1f))
        }
    }

    /** A Seed Burst seed: a pale seed with an ink edge. */
    fun DrawScope.seed(
        center: Offset,
        r: Float,
        degrees: Float,
    ) {
        rotate(degrees, center) {
            drawOval(Color(0xFFFFF1C1), Offset(center.x - r * 0.75f, center.y - r), Size(r * 1.5f, r * 2f))
            drawOval(Ink, Offset(center.x - r * 0.75f, center.y - r), Size(r * 1.5f, r * 2f), style = Stroke(r * 0.25f))
        }
    }

    /**
     * An enemy or battery [w] by [h] px at [center], standing on the bottom of its box and turning with it;
     * the hit sprite once [damaged], at the same scale as the whole one.
     */
    fun DrawScope.enemy(
        sprites: MelonsSprites,
        center: Offset,
        w: Float,
        h: Float,
        degrees: Float,
        kind: PieceKind,
        slop: SlopKind?,
        damaged: Boolean,
    ) {
        val (whole, hit) = sprites.enemy(kind, slop)
        val k = minOf(w * ENEMY_FIT / whole.width, h * ENEMY_FIT / whole.height)
        val image = if (damaged) hit else whole
        val iw = image.width * k
        val ih = image.height * k
        rotate(degrees, center) {
            sprite(image, center.x - iw / 2, center.y + h / 2 + h * 0.08f - ih, iw, ih)
        }
    }

    /**
     * A block [w] by [h] px at [center]: tiles of the material along its length, each up to [TILE_STRETCH] times longer than wide, worn by the share of
     * its health lost ([hp] of [maxHp]), with ink bolts on a fixed block.
     */
    fun DrawScope.block(
        sprites: MelonsSprites,
        center: Offset,
        w: Float,
        h: Float,
        degrees: Float,
        material: MelonMaterial?,
        hp: Float,
        maxHp: Float,
        fixed: Boolean,
    ) {
        val wear = if (fixed || maxHp <= 0f) 0 else ((1f - hp / maxHp) * 4f).toInt()
        val image = sprites.block(material, wear)
        val along = w >= h
        val length = if (along) w else h
        val across = if (along) h else w
        val tiles = (length / (across * TILE_STRETCH)).roundToInt().coerceAtLeast(1)
        val step = length / tiles
        withTransform({ rotate(degrees, center) }) {
            val left = center.x - w / 2
            val top = center.y - h / 2
            for (i in 0 until tiles) {
                if (along) {
                    sprite(
                        image,
                        left + i * step,
                        top,
                        step,
                        h,
                    )
                } else {
                    sprite(image, left, top + i * step, w, step)
                }
            }
            if (fixed) {
                val r = (minOf(w, h) * 0.06f).coerceIn(2f, 4f)
                drawCircle(Ink, r, Offset(left + r * 3, top + r * 3))
                drawCircle(Ink, r, Offset(left + w - r * 3, top + r * 3))
            }
        }
    }

    private fun slingHeight(
        tieMiddle: Offset,
        groundY: Float,
    ) = (groundY - tieMiddle.y) / (SLING_FOOT_Y - SLING_TIE_Y)

    /** The slingshot's two band ties, for its fork's middle at [tieMiddle] standing on [groundY]. */
    fun slingTies(
        image: ImageBitmap,
        tieMiddle: Offset,
        groundY: Float,
    ): Pair<Offset, Offset> {
        val half = (SLING_RIGHT_X - SLING_LEFT_X) / 2 * slingHeight(tieMiddle, groundY) * image.width / image.height
        return Offset(tieMiddle.x - half, tieMiddle.y) to Offset(tieMiddle.x + half, tieMiddle.y)
    }

    /** The slingshot sprite standing on [groundY], its band ties at [tieMiddle]. */
    fun DrawScope.slingshot(
        image: ImageBitmap,
        tieMiddle: Offset,
        groundY: Float,
    ) {
        val height = slingHeight(tieMiddle, groundY)
        val width = height * image.width / image.height
        val left = tieMiddle.x - (SLING_LEFT_X + SLING_RIGHT_X) / 2 * width
        sprite(image, left, groundY - SLING_FOOT_Y * height, width, height)
    }

    /** The slingshot's band from both ties to the pouch at [pouch]. */
    fun DrawScope.band(
        left: Offset,
        right: Offset,
        pouch: Offset,
        m: Float,
    ) {
        drawLine(Band, left, pouch, m * 0.22f, StrokeCap.Round)
        drawLine(Band, right, pouch, m * 0.22f, StrokeCap.Round)
    }

    /** Dark text centred at [at], for a label on a yellow button. */
    fun DrawScope.buttonLabel(
        text: String,
        at: Offset,
        sizePx: Float,
    ) {
        labelPaint.textSize = sizePx
        labelPaint.style = Paint.Style.FILL
        labelPaint.color = android.graphics.Color.rgb(0x1C, 0x23, 0x31)
        drawContext.canvas.nativeCanvas.drawText(text, at.x, at.y, labelPaint)
    }

    /** A score or bonus floating up, in yellow with an ink outline. */
    fun DrawScope.popup(
        text: String,
        at: Offset,
        sizePx: Float,
        alpha: Float,
    ) {
        val canvas = drawContext.canvas.nativeCanvas
        labelPaint.textSize = sizePx
        labelPaint.style = Paint.Style.STROKE
        labelPaint.strokeWidth = sizePx / 6f
        labelPaint.color = android.graphics.Color.argb((alpha * 255).toInt(), 0x1C, 0x23, 0x31)
        canvas.drawText(text, at.x, at.y, labelPaint)
        labelPaint.style = Paint.Style.FILL
        labelPaint.color = android.graphics.Color.argb((alpha * 255).toInt(), 0xFF, 0xD4, 0x00)
        canvas.drawText(text, at.x, at.y, labelPaint)
    }

    /** A tile may stretch to three times its width, so a long plank reads as a plank, not a row of crates. */
    private const val TILE_STRETCH = 3f

    private val Updraft = Color(0xFFBFEFFF)
    private const val FAN_STREAKS = 8
    private const val FAN_STREAK_SPEED = 3f
    private const val FAN_SIZE = 0.45f

    /** The I-beam's share of the beam sprite's width and height, how much deeper than the piece it is drawn, and the cables. */
    private const val BEAM_SHARE = 0.95f
    private const val BEAM_PART = 0.42f
    private const val BEAM_DEEP = 1.4f
    private const val CABLE_LEFT = 0.3f
    private const val CABLE_RIGHT = 0.81f

    /** The banner's drawn height as a share of its length, and where its pad's middle is down the sprite. */
    private const val BANNER_HEIGHT = 0.5f
    private const val BANNER_PAD_Y = 0.3f

    /** An enemy's sprite is a fifth larger than its box: legs, antennas and crowns stick out. */
    private const val ENEMY_FIT = 1.2f
}
