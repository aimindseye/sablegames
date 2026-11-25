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
 * melon in three poses and three rage levels, the four enemies whole and hit, glass, wood and stone in four
 * wear stages, the slingshot and the World 1 background. Each sheet's sprites share one scale.
 */
internal class MelonsSprites(
    private val load: (Int) -> ImageBitmap,
) {
    private fun all(vararg ids: Int) = ids.map(load)

    val world1 = load(R.drawable.melons_world1)
    val slingshot = load(R.drawable.melons_slingshot)
    private val ready = all(R.drawable.melons_melon_ready_0, R.drawable.melons_melon_ready_1, R.drawable.melons_melon_ready_2)
    private val flying = all(R.drawable.melons_melon_flying_0, R.drawable.melons_melon_flying_1, R.drawable.melons_melon_flying_2)
    private val landed = all(R.drawable.melons_melon_landed_0, R.drawable.melons_melon_landed_1, R.drawable.melons_melon_landed_2)
    private val app = all(R.drawable.melons_enemy_app, R.drawable.melons_enemy_app_hit)
    private val bot = all(R.drawable.melons_enemy_bot, R.drawable.melons_enemy_bot_hit)
    private val ad = all(R.drawable.melons_enemy_ad, R.drawable.melons_enemy_ad_hit)
    private val battery = all(R.drawable.melons_enemy_battery, R.drawable.melons_enemy_battery_hit)
    private val glass =
        all(
            R.drawable.melons_block_glass_0,
            R.drawable.melons_block_glass_1,
            R.drawable.melons_block_glass_2,
            R.drawable.melons_block_glass_3,
        )
    private val wood =
        all(R.drawable.melons_block_wood_0, R.drawable.melons_block_wood_1, R.drawable.melons_block_wood_2, R.drawable.melons_block_wood_3)
    private val stone =
        all(
            R.drawable.melons_block_stone_0,
            R.drawable.melons_block_stone_1,
            R.drawable.melons_block_stone_2,
            R.drawable.melons_block_stone_3,
        )

    /** [rage] 0-2 grows with each melon fired in a level. */
    fun melon(
        pose: MelonPose,
        rage: Int,
    ): ImageBitmap =
        when (pose) {
            MelonPose.Ready -> ready
            MelonPose.Flying -> flying
            MelonPose.Landed -> landed
        }[rage.coerceIn(0, MAX_RAGE)]

    /** The enemy's two sprites, whole then hit. */
    fun enemy(
        kind: PieceKind,
        slop: SlopKind?,
    ): List<ImageBitmap> =
        when {
            kind == PieceKind.Battery -> battery
            slop == SlopKind.Bot -> bot
            slop == SlopKind.Ad -> ad
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
            else -> stone
        }[wear.coerceIn(0, 3)]

    companion object {
        const val MAX_RAGE = 2
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

    /** Where World 1's dirt path starts, as a share of the background's height. */
    private const val PATH_TOP = 0.86f

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
        image: ImageBitmap,
        cameraX: Float,
        maxCameraX: Float,
        groundY: Float,
    ) {
        val k = maxOf(groundY / (PATH_TOP * image.height), size.height / image.height)
        val width = image.width * k
        val slack = (width - size.width).coerceAtLeast(0f)
        val shift = if (maxCameraX > 0f) slack * (cameraX / maxCameraX).coerceIn(0f, 1f) else 0f
        sprite(image, -shift, groundY - PATH_TOP * image.height * k, width, image.height * k)
    }

    /** A melon of radius [r] px at [center], turned by [degrees]; a landed melon rests on the bottom of its circle. */
    fun DrawScope.melon(
        sprites: MelonsSprites,
        center: Offset,
        r: Float,
        degrees: Float,
        pose: MelonPose,
        rage: Int,
    ) {
        val image = sprites.melon(pose, rage)
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
                if (along) sprite(image, left + i * step, top, step, h) else sprite(image, left, top + i * step, w, step)
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

    /** An enemy's sprite is a fifth larger than its box: legs, antennas and crowns stick out. */
    private const val ENEMY_FIT = 1.2f
}
