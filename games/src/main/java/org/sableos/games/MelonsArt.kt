package org.sableos.games

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.PathParser

/**
 * Bitter Melons' art, drawn from the owner's SVG sprites (2026-11-25): the melon and enemy at 256 x 256,
 * a block at 128 x 128 per metre and the World 1 background at 1440 x 720. Each sprite is drawn in its
 * own SVG coordinates, scaled to the thing's size on screen, so the shapes match the files.
 */
internal object MelonsArt {
    val Ink = Color(0xFF1C2331)
    val Sky = Color(0xFF38B6FF)
    val Sun = Color(0xFFFFD400)
    val HillBack = Color(0xFF6EE76B)
    val HillFront = Color(0xFF4CC936)
    val Ridge = Color(0xFF2E8B22)
    val Red = Color(0xFFFF3B4E)
    val Yellow = Color(0xFFFFD400)
    private val Rind = Color(0xFF4CC936)
    private val Seam = Color(0xFF1F6E19)
    private val Stem = Color(0xFF3A8E2A)
    private val Vine = Color(0xFF6B4423)
    private val Leaf = Color(0xFF4CC936)
    private val Band = Color(0xFF2E7D1E)

    private fun path(d: String): Path = PathParser().parsePathString(d).toPath()

    // The melon (base-melon.svg).
    private val stem = path("M116,44 C112,24 134,18 144,28 C150,34 142,44 134,44 Z")
    private val seams =
        listOf(
            path("M92,52 C74,96 74,176 92,220"),
            path("M128,48 C118,96 118,176 128,224"),
            path("M164,52 C182,96 182,176 164,220"),
        )
    private val shine = path("M72,84 C82,68 98,62 110,64")
    private val browLeft = path("M78,100 L122,114 L120,122 L76,110 Z")
    private val browRight = path("M178,100 L134,114 L136,122 L180,110 Z")
    private val mouth = path("M96,162 Q128,146 160,162 Q152,184 128,184 Q104,184 96,162 Z")
    private val teeth = path("M104,162 L112,172 L120,160 L128,173 L136,160 L144,172 L152,162")
    private val sparkLeft = path("M46,56 L58,72 L40,78 Z")
    private val sparkRight = path("M210,52 L198,70 L216,76 Z")

    // The enemy (enemy.svg), its damaged marks.
    private val crackScreen = path("M64,68 L112,114 L96,136 L132,160")
    private val sparkEye = path("M150,94 L168,104 L152,114 Z")
    private val burst = path("M196,36 L226,24 L212,52 L236,64 L204,70 Z")

    // The block cracks (material-block.svg), in a 128 x 128 metre.
    private val crackA = path("M6,34 L42,56 L30,78 L68,96 L84,122")
    private val crackB = path("M122,28 L86,52 L98,74 L68,96")

    // The World 1 hills (worldground.svg).
    private val hillBack = path("M0,430 Q320,300 680,420 T1440,380 L1440,720 L0,720 Z")
    private val hillFront = path("M0,490 Q400,390 820,500 T1440,460 L1440,720 L0,720 Z")

    private val labelPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

    private fun ink(width: Float) = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)

    /** The sky, sun and two layers of hills, sliding at a third of the camera's speed. */
    fun DrawScope.background(
        cameraX: Float,
        pxPerMetre: Float,
        groundY: Float,
    ) {
        drawRect(Sky)
        val k = size.height / 720f
        drawCircle(Sun, 68f * k, Offset(size.width * 0.82f, 140f * k))
        drawCircle(Ink, 68f * k, Offset(size.width * 0.82f, 140f * k), style = Stroke(6f * k))
        val tile = 1440f * k
        val shift = -((cameraX * pxPerMetre / 3f) % tile)
        // The hills end where the ground starts.
        val lift = groundY - 575f * k
        for (i in 0..2) {
            translate(shift + i * tile, lift) {
                scale(k, k, Offset.Zero) {
                    drawPath(hillBack, HillBack)
                    drawPath(hillBack, Ink, style = Stroke(6f))
                    drawPath(hillFront, HillFront)
                    drawPath(hillFront, Ink, style = Stroke(6f))
                }
            }
        }
        drawRect(Ridge, Offset(0f, groundY), Size(size.width, size.height - groundY))
        drawLine(Ink, Offset(0f, groundY), Offset(size.width, groundY), 3f)
    }

    /**
     * A melon of radius [r] px at [center], turned by [degrees]. [rage] 1-4 sets the red flush (0, 25,
     * 50 and 75 %); [squashed] is the landed pose.
     */
    fun DrawScope.melon(
        center: Offset,
        r: Float,
        degrees: Float,
        rage: Int = 1,
        squashed: Boolean = false,
    ) {
        val k = r / 88f
        val flush = ((rage - 1) * 0.25f).coerceIn(0f, 0.75f)
        withTransform({
            translate(center.x, center.y)
            rotate(degrees, Offset.Zero)
            scale(k, k, Offset.Zero)
            translate(-128f, -136f)
        }) {
            if (rage >= 3) {
                drawPath(sparkLeft, Yellow)
                drawPath(sparkLeft, Ink, style = ink(5f))
                drawPath(sparkRight, Red)
                drawPath(sparkRight, Ink, style = ink(5f))
            }
            val sx = if (squashed) 1.16f else 1f
            val sy = if (squashed) 0.82f else 1f
            scale(sx, sy, Offset(128f, if (squashed) 200f else 136f)) {
                drawPath(stem, Stem)
                drawPath(stem, Ink, style = ink(8f))
                drawOval(Rind, Offset(44f, 48f), Size(168f, 176f))
                drawOval(Ink, Offset(44f, 48f), Size(168f, 176f), style = ink(8f))
                seams.forEach { drawPath(it, Seam, style = ink(10f)) }
                if (flush > 0f) drawOval(Red.copy(alpha = flush), Offset(52f, 48f), Size(152f, 156f))
                drawPath(shine, Color.White.copy(alpha = 0.75f), style = ink(7f))
                // The face.
                for (x in listOf(102f, 154f)) {
                    drawCircle(Color.White, 18f, Offset(x, 122f))
                    drawCircle(Ink, 18f, Offset(x, 122f), style = ink(5f))
                }
                drawCircle(Ink, 7f, Offset(105f, 124f))
                drawCircle(Ink, 7f, Offset(151f, 124f))
                drawCircle(Color.White, 2.5f, Offset(108f, 120f))
                drawCircle(Color.White, 2.5f, Offset(154f, 120f))
                drawPath(browLeft, Ink)
                drawPath(browRight, Ink)
                drawPath(mouth, Red)
                drawPath(mouth, Ink, style = ink(5f))
                drawPath(teeth, Color.White)
                drawPath(teeth, Ink, style = ink(5f))
            }
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

    private data class EnemyLook(
        val chassis: Color,
        val label: String,
    )

    private fun look(
        kind: PieceKind,
        slop: SlopKind?,
    ): EnemyLook =
        when {
            kind == PieceKind.Battery -> EnemyLook(Red, "HOT")
            slop == SlopKind.Bot -> EnemyLook(Color(0xFF2ECC71), "BOT")
            slop == SlopKind.Ad -> EnemyLook(Yellow, "AD")
            else -> EnemyLook(Color(0xFF8A2BE2), "SLOP")
        }

    /**
     * An enemy or battery [w] by [h] px at [center]: the chassis, the screen face and the label chip, with
     * a cracked screen and sparks once [damaged].
     */
    fun DrawScope.enemy(
        center: Offset,
        w: Float,
        h: Float,
        degrees: Float,
        kind: PieceKind,
        slop: SlopKind?,
        damaged: Boolean,
    ) {
        val look = look(kind, slop)
        withTransform({
            translate(center.x, center.y)
            rotate(degrees, Offset.Zero)
            scale(w / 168f, h / 168f, Offset.Zero)
            translate(-128f, -128f)
        }) {
            if (slop == SlopKind.Ad) {
                drawCircle(look.chassis, 84f, Offset(128f, 128f))
                drawCircle(Ink, 84f, Offset(128f, 128f), style = ink(8f))
            } else {
                drawRoundRect(look.chassis, Offset(44f, 44f), Size(168f, 168f), CornerRadius(22f))
                drawRoundRect(Ink, Offset(44f, 44f), Size(168f, 168f), CornerRadius(22f), style = ink(8f))
            }
            drawRoundRect(Ink, Offset(64f, 68f), Size(128f, 92f), CornerRadius(12f))
            if (damaged) {
                drawPath(crackScreen, Red, style = ink(6f))
                drawRect(Red, Offset(84f, 102f), Size(20f, 8f))
                drawPath(sparkEye, Yellow)
                drawPath(burst, Yellow)
                drawPath(burst, Ink, style = ink(5f))
            } else {
                drawRoundRect(Yellow, Offset(84f, 98f), Size(24f, 14f), CornerRadius(4f))
                drawRoundRect(Yellow, Offset(148f, 98f), Size(24f, 14f), CornerRadius(4f))
                drawLine(Red, Offset(92f, 134f), Offset(164f, 134f), 8f, StrokeCap.Round)
            }
            drawRoundRect(Color.White, Offset(64f, 172f), Size(128f, 24f), CornerRadius(6f))
            drawRoundRect(Ink, Offset(64f, 172f), Size(128f, 24f), CornerRadius(6f), style = ink(5f))
            labelPaint.textSize = 15f
            labelPaint.color = android.graphics.Color.rgb(0x1C, 0x23, 0x31)
            drawContext.canvas.nativeCanvas.drawText(look.label, 128f, 189f, labelPaint)
        }
    }

    private data class BlockLook(
        val fill: Color,
        val line: Color,
        val lineAlpha: Float,
    )

    private fun look(material: MelonMaterial?): BlockLook =
        when (material) {
            MelonMaterial.Glass -> BlockLook(Color(0xC07CE8FF), Color(0xFFB8F4FF), 0.9f)
            MelonMaterial.Stone -> BlockLook(Color(0xFF8D99AE), Color(0xFF6C757D), 0.9f)
            else -> BlockLook(Color(0xFFC68642), Ink, 0.35f)
        }

    /**
     * A block [w] by [h] px at [center]: the material's colour, a grain or seam line every metre
     * ([pxPerMetre]) along its length, and the crack lines once [cracked].
     */
    fun DrawScope.block(
        center: Offset,
        w: Float,
        h: Float,
        degrees: Float,
        material: MelonMaterial?,
        cracked: Boolean,
        pxPerMetre: Float,
        bolted: Boolean = false,
    ) {
        val look = look(material)
        val corner = CornerRadius(pxPerMetre * 10f / 128f)
        val edge = (pxPerMetre * 7f / 128f).coerceIn(1.5f, 3f)
        rotate(degrees, center) {
            val topLeft = Offset(center.x - w / 2, center.y - h / 2)
            drawRoundRect(look.fill, topLeft, Size(w, h), corner)
            // A grain, shine or seam line every metre, along the long side.
            val along = w >= h
            val length = if (along) w else h
            val across = if (along) h else w
            var at = pxPerMetre * 0.5f
            while (at < length) {
                if (along) {
                    val x = topLeft.x + at
                    drawLine(
                        look.line.copy(alpha = look.lineAlpha),
                        Offset(x, topLeft.y + across * 0.2f),
                        Offset(
                            x,
                            topLeft.y + across * 0.8f,
                        ),
                        edge * 0.6f,
                    )
                } else {
                    val y = topLeft.y + at
                    drawLine(
                        look.line.copy(alpha = look.lineAlpha),
                        Offset(topLeft.x + across * 0.2f, y),
                        Offset(topLeft.x + across * 0.8f, y),
                        edge * 0.6f,
                    )
                }
                at += pxPerMetre
            }
            if (bolted) {
                val r = (pxPerMetre * 0.08f).coerceAtLeast(2f)
                drawCircle(Ink, r, Offset(topLeft.x + r * 3, topLeft.y + r * 3))
                drawCircle(Ink, r, Offset(topLeft.x + w - r * 3, topLeft.y + r * 3))
            }
            if (cracked) {
                withTransform({
                    translate(topLeft.x, topLeft.y)
                    scale(w / 128f, h / 128f, Offset.Zero)
                }) {
                    // The sprite's 4.5-unit crack, kept about the width of the edge whatever the block's size.
                    val crack = Stroke(edge * 128f / minOf(w, h).coerceAtLeast(1f))
                    drawPath(crackA, Ink, style = crack)
                    drawPath(crackB, Ink, style = crack)
                }
            }
            drawRoundRect(Ink, topLeft, Size(w, h), corner, style = Stroke(edge))
        }
    }

    /** The vine slingshot: its foot at [foot], fork tips [left] and [right] (px), [m] px per metre. */
    fun DrawScope.slingshot(
        foot: Offset,
        fork: Offset,
        left: Offset,
        right: Offset,
        m: Float,
    ) {
        drawLine(Vine, foot, fork, m * 0.55f, StrokeCap.Round)
        drawLine(Vine, fork, left, m * 0.45f, StrokeCap.Round)
        drawLine(Vine, fork, right, m * 0.45f, StrokeCap.Round)
        drawCircle(Leaf, m * 0.35f, left + Offset(-m * 0.3f, -m * 0.25f))
        drawCircle(Leaf, m * 0.35f, right + Offset(m * 0.3f, -m * 0.25f))
    }

    /** The slingshot's band from both tips to the pouch at [pouch]. */
    fun DrawScope.band(
        left: Offset,
        right: Offset,
        pouch: Offset,
        m: Float,
    ) {
        drawLine(Band, left, pouch, m * 0.18f, StrokeCap.Round)
        drawLine(Band, right, pouch, m * 0.18f, StrokeCap.Round)
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
}
