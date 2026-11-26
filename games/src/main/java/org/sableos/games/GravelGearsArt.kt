package org.sableos.games

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/** The art of Gravel & Gears, cut from the owner's sheets by tools/gravelgears/cut_sprites.py. */
internal class GgSprites(
    private val load: (Int) -> ImageBitmap,
) {
    private val day: Map<GgKind, ImageBitmap> by lazy {
        mapOf(
            GgKind.PINE_TREE to load(R.drawable.gg_day_pine),
            GgKind.BOULDER to load(R.drawable.gg_day_boulder),
            GgKind.LOG to load(R.drawable.gg_day_log),
            GgKind.STUMP to load(R.drawable.gg_day_stump),
            GgKind.SMALL_ROCK to load(R.drawable.gg_day_boulder),
            GgKind.RAMP to load(R.drawable.gg_day_ramp),
            GgKind.BERM_LEFT to load(R.drawable.gg_day_berm),
            GgKind.BERM_RIGHT to load(R.drawable.gg_day_berm),
            GgKind.MUD to load(R.drawable.gg_day_mud),
            GgKind.GRAVEL to load(R.drawable.gg_day_gravel),
        )
    }
    private val night: Map<GgKind, ImageBitmap> by lazy {
        mapOf(
            GgKind.PINE_TREE to load(R.drawable.gg_night_pine),
            GgKind.BOULDER to load(R.drawable.gg_night_boulder),
            GgKind.LOG to load(R.drawable.gg_night_log),
            GgKind.STUMP to load(R.drawable.gg_night_stump),
            GgKind.SMALL_ROCK to load(R.drawable.gg_night_boulder),
            GgKind.RAMP to load(R.drawable.gg_night_ramp),
            GgKind.BERM_LEFT to load(R.drawable.gg_night_berm),
            GgKind.BERM_RIGHT to load(R.drawable.gg_night_berm),
            GgKind.MUD to load(R.drawable.gg_night_mud),
            GgKind.GRAVEL to load(R.drawable.gg_night_gravel),
        )
    }
    private val flagsDay by lazy { load(R.drawable.gg_day_flags) }
    private val flagsNight by lazy { load(R.drawable.gg_night_flags) }

    private val heads: Map<String, ImageBitmap> by lazy {
        mapOf(
            "gravel" to load(R.drawable.gg_head_gravel_front),
            "spokes" to load(R.drawable.gg_head_spokes_front),
            "knobby" to load(R.drawable.gg_head_knobby_front),
        )
    }
    private val marmot by lazy { listOf(R.drawable.gg_marmot_idle, R.drawable.gg_marmot_run, R.drawable.gg_marmot_tumble).map(load) }
    private val deer by lazy { listOf(R.drawable.gg_deer_stand, R.drawable.gg_deer_crouch, R.drawable.gg_deer_leap).map(load) }
    private val crow by lazy { listOf(R.drawable.gg_crow_a, R.drawable.gg_crow_b, R.drawable.gg_crow_c).map(load) }

    fun prop(
        kind: GgKind,
        night: Boolean,
    ): ImageBitmap = (if (night) this.night else day).getValue(kind)

    fun flags(night: Boolean) = if (night) flagsNight else flagsDay

    fun head(id: String): ImageBitmap = heads.getValue(id)

    fun marmot(frame: Int) = marmot[frame.coerceIn(0, 2)]

    fun deer(frame: Int) = deer[frame.coerceIn(0, 2)]

    fun crow(frame: Int) = crow[frame.mod(3)]
}

@Composable
internal fun rememberGgSprites(): GgSprites {
    val resources = LocalContext.current.resources
    return remember(resources) { GgSprites { id -> androidx.compose.ui.graphics.ImageBitmap.imageResource(resources, id) } }
}

internal object GgArt {
    val DayDirt = Color(0xFFE59866)
    val DaySlope = Color(0xFFD9744A)
    val DayContour = Color(0xFFB94A33)
    val DayEdge = Color(0xFFC77D50)
    val NightDirt = Color(0xFF111A24)
    val NightSlope = Color(0xFF080D14)
    val NightContour = Color(0xFF1E3246)
    val NightEdge = Color(0xFF0C141E)
    val Safe = Color(0xFFFFFFFF)
    val SafeNight = Color(0xFF00F2FE)
    val Bounce = Color(0xFF00F2FE)
    val Lethal = Color(0xFFFF3B30)
    val Ink = Color(0xFF0E3A3F)
    val Panel = Color(0xE6102A2E)
    val Amber = Color(0xFFFFB347)
    val Teal = Color(0xFF16A0A0)

    /** Draws [image] with its bottom-centre at [foot], [h] px tall, keeping its proportions. */
    fun DrawScope.prop(
        image: ImageBitmap,
        foot: Offset,
        h: Float,
        alpha: Float = 1f,
        anchor: Float = 0.9f,
    ) {
        val w = h * image.width / image.height
        drawImage(
            image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset((foot.x - w / 2).roundToInt(), (foot.y - h * anchor).roundToInt()),
            dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
            alpha = alpha,
            filterQuality = FilterQuality.Medium,
        )
    }

    /** Same, but sized by width: used for logs, berms and the gate flags. */
    fun DrawScope.propWide(
        image: ImageBitmap,
        foot: Offset,
        w: Float,
        alpha: Float = 1f,
        anchor: Float = 0.9f,
    ) = prop(image, foot, w * image.height / image.width, alpha, anchor)

    /** A bold arrow from [from] to [to]: the stroke is thick so it reads on a 3.5 inch screen. */
    fun DrawScope.arrow(
        from: Offset,
        to: Offset,
        color: Color,
        width: Float,
    ) {
        drawLine(color, from, to, width, androidx.compose.ui.graphics.StrokeCap.Round)
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
        val ux = dx / len
        val uy = dy / len
        val head = width * 2.6f
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(to.x + ux * head * 0.6f, to.y + uy * head * 0.6f)
            lineTo(to.x - ux * head * 0.6f - uy * head * 0.7f, to.y - uy * head * 0.6f + ux * head * 0.7f)
            lineTo(to.x - ux * head * 0.6f + uy * head * 0.7f, to.y - uy * head * 0.6f - ux * head * 0.7f)
            close()
        }
        drawPath(p, color)
    }

    fun DrawScope.cross(
        c: Offset,
        r: Float,
        color: Color,
        width: Float,
    ) {
        drawCircle(color.copy(alpha = 0.25f), r * 1.3f, c)
        drawCircle(color, r * 1.3f, c, style = Stroke(width * 0.6f))
        drawLine(color, Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r), width, androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color, Offset(c.x + r, c.y - r), Offset(c.x - r, c.y + r), width, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}
