package org.sableos.games

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The weapon in hand, the crosshair and the HUD of Tartarus Breach, following the owner's arena mockup: a wave
 * counter, the timer and Obols across the top, and a locked marble-and-bronze status bar over the bottom 22 %, where
 * the physical keys sit.
 */

internal const val TB_BAR_SHARE = 0.22f

internal fun DrawScope.hudText(
    m: TextMeasurer,
    text: String,
    x: Float,
    y: Float,
    px: Float,
    color: Color,
    align: Int = 0,
    weight: FontWeight = FontWeight.Bold,
): Float {
    val style = TextStyle(color = color, fontSize = px.toSp(), fontWeight = weight, fontFamily = FontFamily.Serif)
    val r = m.measure(text, style, maxLines = 1, softWrap = false)
    val w = r.size.width.toFloat()
    val x0 =
        when (align) {
            0 -> x
            1 -> x - w / 2f
            else -> x - w
        }
    drawText(r, color = Color.Black.copy(alpha = 0.65f), topLeft = Offset(x0 + px * 0.06f, y + px * 0.06f))
    drawText(r, topLeft = Offset(x0, y))
    return w
}

/** The first-person weapon, with sway while moving, recoil when it fires and a slide when you switch. */
internal fun DrawScope.drawWeapon(
    run: TbRun,
    art: TbSprites,
    cam: TbCam,
) {
    val s = cam.s
    val img = art.weapon(run.weapon.id)
    val moving = min(1f, hypot(run.moveSpeed, run.strafeSpeed))
    val bob = sin(run.clock * 9f) * s * 0.012f * moving
    val sway = cos(run.clock * 4.5f) * s * 0.014f * moving
    val kick = run.kick
    val w = s * 0.54f * (1f + kick * 0.04f)
    val h = w * img.height / img.width
    val swap = run.swapT / 0.28f
    val cx = s * 0.72f + sway + kick * s * 0.012f
    val bottom = cam.vh + s * 0.07f + bob + kick * s * 0.035f + swap * s * 0.4f
    clipRect(0f, 0f, s, cam.vh + s * 0.02f) {
        drawImage(
            img,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(img.width, img.height),
            dstOffset = IntOffset((cx - w / 2f).roundToInt(), (bottom - h).roundToInt()),
            dstSize = IntSize(w.roundToInt(), h.roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
        if (kick > 0.55f) {
            val c = colorOf(run.weapon.color)
            val o = Offset(s * 0.68f, cam.vh * 0.84f)
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = kick), c.copy(alpha = 0.7f * kick), Color.Transparent), o, s * 0.12f), s * 0.12f, o)
        }
    }
}

internal fun DrawScope.drawSight(
    run: TbRun,
    cam: TbCam,
) {
    val s = cam.s
    val c = Offset(s / 2f, cam.hz + s * 0.02f)
    val locked = run.aimTarget != null
    val r = s * (if (locked) 0.045f else 0.058f) * (1f + run.kick * 0.15f)
    val color = if (locked) Color(0xFFFFD447) else Color(0xFF9FEFFF)
    drawCircle(color.copy(alpha = 0.85f), r, c, style = Stroke(s * 0.004f))
    drawLine(color, Offset(c.x - r * 1.5f, c.y), Offset(c.x - r * 0.7f, c.y), s * 0.004f)
    drawLine(color, Offset(c.x + r * 1.5f, c.y), Offset(c.x + r * 0.7f, c.y), s * 0.004f)
    drawLine(color, Offset(c.x, c.y - r * 1.5f), Offset(c.x, c.y - r * 0.7f), s * 0.004f)
    drawLine(color, Offset(c.x, c.y + r * 1.5f), Offset(c.x, c.y + r * 0.7f), s * 0.004f)
    if (run.hitMarker > 0f) {
        val k = r * 0.75f
        val hc = Color.White.copy(alpha = run.hitMarker / 0.18f)
        drawLine(hc, Offset(c.x - k, c.y - k), Offset(c.x - k * 0.4f, c.y - k * 0.4f), s * 0.006f, StrokeCap.Round)
        drawLine(hc, Offset(c.x + k, c.y - k), Offset(c.x + k * 0.4f, c.y - k * 0.4f), s * 0.006f, StrokeCap.Round)
        drawLine(hc, Offset(c.x - k, c.y + k), Offset(c.x - k * 0.4f, c.y + k * 0.4f), s * 0.006f, StrokeCap.Round)
        drawLine(hc, Offset(c.x + k, c.y + k), Offset(c.x + k * 0.4f, c.y + k * 0.4f), s * 0.006f, StrokeCap.Round)
    }
}

/** Everything on top of the view: counters, boss bar, toasts, the countdown and the status bar. */
internal fun DrawScope.drawHud(
    run: TbRun,
    art: TbSprites,
    cam: TbCam,
    m: TextMeasurer,
    keyLabels: List<String>,
) {
    val s = cam.s
    val big = s * 0.058f
    val boss = run.boss
    hudText(m, "WAVE ${run.wave.toString().padStart(2, '0')}", s * 0.03f, s * 0.012f, big, TbColors.Gold)
    hudText(m, "OBOLS ${run.obols}", s * 0.935f, s * 0.012f, big, TbColors.Gold, align = 2)
    drawCoin(Offset(s * 0.965f, s * 0.045f), s * 0.022f)
    if (boss == null) {
        val t = run.timeLeft
        val mm = (t / 60f).toInt()
        val ss = t - mm * 60f
        hudText(m, "%02d:%04.1f".format(mm, ss), s / 2f, s * 0.004f, s * 0.07f, TbColors.Cyan, align = 1)
    } else {
        hudText(m, boss.def.name.uppercase(), s / 2f, s * 0.0f, s * 0.042f, Color(0xFFFFB0A0), align = 1)
        val bw = s * 0.52f
        val bx = s / 2f - bw / 2f
        val by = s * 0.052f
        drawRoundRect(Color.Black.copy(alpha = 0.7f), Offset(bx - s * 0.005f, by - s * 0.005f), Size(bw + s * 0.01f, s * 0.03f), CornerRadius(s * 0.008f))
        val frac = (boss.hp / boss.maxHp).coerceIn(0f, 1f)
        drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF7A0F1C), TbColors.Blood, Color(0xFFFF6A55)), bx, bx + bw), Offset(bx, by), Size(bw * frac, s * 0.02f), CornerRadius(s * 0.005f))
        drawRoundRect(TbColors.Gold.copy(alpha = 0.8f), Offset(bx - s * 0.005f, by - s * 0.005f), Size(bw + s * 0.01f, s * 0.03f), CornerRadius(s * 0.008f), style = Stroke(s * 0.003f))
    }
    drawRadar(run, cam)
    // A reminder of the controls while you settle in on the first wave.
    if (run.wave == 1 && run.runTime < HINT_SECONDS && run.phase != TbPhase.Draft) {
        val a = (1f - (run.runTime - (HINT_SECONDS - 1.5f)) / 1.5f).coerceIn(0f, 1f)
        hudText(m, "W A S D MOVE  ·  J L TURN  ·  SPACE FIRE  ·  X DASH", s / 2f, cam.vh * 0.9f, s * 0.03f, Color.White.copy(alpha = a), align = 1)
    }
    // Toasts and the streak.
    var ty = s * 0.115f
    for (t in run.toasts.takeLast(3)) {
        val age = run.clock - t.at
        val a = (1f - (age / TbRun.TOAST_TIME - 0.7f).coerceAtLeast(0f) / 0.3f).coerceIn(0f, 1f)
        hudText(m, t.text, s / 2f, ty, s * 0.04f, (if (t.good) TbColors.Gold else Color(0xFFFF8A7A)).copy(alpha = a), align = 1)
        ty += s * 0.048f
    }
    if (run.streak >= 3) hudText(m, "x${run.streak}", s * 0.62f, cam.hz - s * 0.1f, s * 0.05f, Color(0xFFFFD447), align = 0)
    // Damage numbers.
    var shown = 0
    for (f in run.fx) {
        if (f.kind != TbFxKind.Text || shown >= 14) continue
        val o = textPos(cam, f) ?: continue
        hudText(m, f.text, o.x, o.y, s * 0.032f, colorOf(f.color).copy(alpha = 1f - f.t / f.life), align = 1)
        shown++
    }
    if (run.phase == TbPhase.Ready) drawReady(run, cam, m)
    drawBar(run, art, cam, m, keyLabels)
}

private fun DrawScope.drawCoin(
    c: Offset,
    r: Float,
) {
    drawCircle(Color(0xFF8A6A2A), r, c)
    drawCircle(TbColors.Gold, r * 0.82f, c)
    drawCircle(Color(0xFFB8913A), r * 0.55f, c, style = Stroke(r * 0.14f))
    drawCircle(Color(0xFFFFF3C0), r * 0.18f, Offset(c.x - r * 0.3f, c.y - r * 0.3f))
}

private fun DrawScope.drawReady(
    run: TbRun,
    cam: TbCam,
    m: TextMeasurer,
) {
    val s = cam.s
    val a = (run.readyT / 0.6f).coerceIn(0f, 1f)
    drawRect(Color.Black.copy(alpha = 0.38f * a), topLeft = Offset(-cam.extra, 0f), size = Size(s + 2f * cam.extra, cam.vh))
    val col = TbColors.Gold.copy(alpha = a.coerceAtLeast(0.2f))
    hudText(m, "WAVE ${run.wave} OF ${run.gate.stages.size}", s / 2f, cam.vh * 0.22f, s * 0.045f, col, align = 1)
    hudText(m, run.stage.title.uppercase(), s / 2f, cam.vh * 0.22f + s * 0.06f, s * 0.07f, Color.White.copy(alpha = a.coerceAtLeast(0.2f)), align = 1)
    val hazard = hazardName(run.stage.hazard)
    if (hazard.isNotEmpty()) hudText(m, hazard, s / 2f, cam.vh * 0.22f + s * 0.15f, s * 0.04f, Color(0xFFFF9A7A).copy(alpha = a.coerceAtLeast(0.2f)), align = 1)
    val n = ceil(run.readyT / 0.8f).toInt()
    hudText(m, if (run.readyT > 0.7f) n.toString() else "DESCEND!", s / 2f, cam.vh * 0.52f, s * 0.12f, TbColors.Cyan, align = 1)
}

internal fun hazardName(h: String): String =
    when (h) {
        "soul_fog_slow_zones" -> "Soul fog slows you"
        "lava_border" -> "The edge is lava"
        "lava_channels_2" -> "Lava channels"
        "erupting_lava_vents" -> "Lava vents erupt"
        "spike_floor_plates" -> "Spike plates"
        "void_gravity_wells" -> "Gravity wells pull"
        "time_dilation_zones" -> "Time slows in the rifts"
        else -> ""
    }

private fun DrawScope.meander(
    x0: Float,
    x1: Float,
    y: Float,
    h: Float,
    color: Color,
) {
    val c = h * 1.4f
    var x = x0
    val w = h * 0.16f
    while (x + c <= x1) {
        val p =
            Path().apply {
                moveTo(x, y + h)
                lineTo(x, y)
                lineTo(x + c, y)
                lineTo(x + c, y + h)
                lineTo(x + c * 0.3f, y + h)
                lineTo(x + c * 0.3f, y + h * 0.35f)
                lineTo(x + c * 0.65f, y + h * 0.35f)
                lineTo(x + c * 0.65f, y + h * 0.68f)
            }
        drawPath(p, color, style = Stroke(w))
        x += c
    }
}

private fun DrawScope.drawBar(
    run: TbRun,
    art: TbSprites,
    cam: TbCam,
    m: TextMeasurer,
    keyLabels: List<String>,
) {
    val s = cam.s
    val bh = s * TB_BAR_SHARE
    val y0 = s - bh
    val x0 = -cam.extra
    val fullW = s + 2f * cam.extra
    drawRect(Brush.verticalGradient(listOf(Color(0xFF3A342C), TbColors.StoneDark), y0, s), Offset(x0, y0), Size(fullW, bh))
    drawRect(TbColors.Bronze, Offset(x0, y0), Size(fullW, s * 0.01f))
    drawRect(TbColors.Gold.copy(alpha = 0.7f), Offset(x0, y0 + s * 0.01f), Size(fullW, s * 0.003f))
    meander(s * 0.01f, s * 0.395f, y0 + s * 0.018f, s * 0.016f, TbColors.GoldDark)
    meander(s * 0.605f, s * 0.99f, y0 + s * 0.018f, s * 0.016f, TbColors.GoldDark)
    meander(s * 0.01f, s * 0.395f, s - s * 0.03f, s * 0.016f, TbColors.GoldDark)
    meander(s * 0.605f, s * 0.99f, s - s * 0.03f, s * 0.016f, TbColors.GoldDark)

    // HP and Favor, left.
    val hpFrac = (run.hp / run.maxHp).coerceIn(0f, 1f)
    val row1 = y0 + s * 0.04f
    val row2 = y0 + s * 0.108f
    drawHeart(Offset(s * 0.065f, row1 + s * 0.037f), s * 0.026f)
    val tw = hudText(m, "HP: ", s * 0.105f, row1, s * 0.062f, TbColors.Gold)
    hudText(m, run.hp.roundToInt().coerceAtLeast(0).toString(), s * 0.105f + tw, row1, s * 0.062f, Color(0xFFFF6A6A))
    drawBolt(Offset(s * 0.065f, row2 + s * 0.037f), s * 0.026f)
    val fw = hudText(m, "FAVOR: ", s * 0.105f, row2, s * 0.062f, TbColors.Gold)
    hudText(m, run.favor.roundToInt().toString(), s * 0.105f + fw, row2, s * 0.062f, Color(0xFF6CC4FF))
    val bx = s * 0.04f
    val bw = s * 0.34f
    drawRoundRect(Color.Black.copy(alpha = 0.55f), Offset(bx, y0 + s * 0.098f), Size(bw, s * 0.009f), CornerRadius(s * 0.004f))
    drawRoundRect(TbColors.Hp, Offset(bx, y0 + s * 0.098f), Size(bw * hpFrac, s * 0.009f), CornerRadius(s * 0.004f))
    drawRoundRect(Color.Black.copy(alpha = 0.55f), Offset(bx, y0 + s * 0.166f), Size(bw, s * 0.009f), CornerRadius(s * 0.004f))
    drawRoundRect(TbColors.Favor, Offset(bx, y0 + s * 0.166f), Size(bw * (run.favor / TbRun.MAX_FAVOR).coerceIn(0f, 1f), s * 0.009f), CornerRadius(s * 0.004f))

    drawPortrait(run, art, cam, hpFrac)

    // The four artifacts, right: keycaps, the chosen one lit in its own colour.
    val kw = s * 0.076f
    val kx0 = s * 0.625f
    val ky = y0 + s * 0.04f
    for (i in 0 until 4) {
        val w = run.weapons[i]
        val on = i == run.weaponIdx
        val x = kx0 + i * (kw + s * 0.012f)
        val col = colorOf(w.color)
        val afford = run.favor >= w.favor
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0xFF5A544C), Color(0xFF38332D)), ky, ky + kw),
            Offset(x, ky),
            Size(kw, kw),
            CornerRadius(s * 0.012f),
        )
        if (on) drawRoundRect(col.copy(alpha = 0.22f), Offset(x, ky), Size(kw, kw), CornerRadius(s * 0.012f))
        drawRoundRect(if (on) col else Color(0xFF1A1612), Offset(x, ky), Size(kw, kw), CornerRadius(s * 0.012f), style = Stroke(if (on) s * 0.006f else s * 0.003f))
        hudText(m, "[${keyLabels.getOrElse(i) { w.badge }}]", x + kw / 2f, ky + kw * 0.2f, s * 0.042f, (if (afford) Color(0xFFE6E0D2) else Color(0xFF857F73)), align = 1)
        if (on) {
            val cd = (run.fireCd / (w.fireMs / 1000f * run.fireMult)).coerceIn(0f, 1f)
            drawRect(col.copy(alpha = 0.9f), Offset(x + kw * 0.1f, ky + kw * 0.86f), Size(kw * 0.8f * (1f - cd), kw * 0.07f))
        }
    }
    val cur = run.weapon
    hudText(m, cur.label, kx0 + (4 * (kw + s * 0.012f) - s * 0.012f) / 2f, y0 + s * 0.128f, s * 0.04f, colorOf(cur.color), align = 1)
    val dashFrac = 1f - (run.dashCd / run.dashCooldown).coerceIn(0f, 1f)
    val dw = 4 * (kw + s * 0.012f) - s * 0.012f
    drawRoundRect(Color.Black.copy(alpha = 0.55f), Offset(kx0, y0 + s * 0.178f), Size(dw, s * 0.009f), CornerRadius(s * 0.004f))
    drawRoundRect(Color(0xFF00E5A0), Offset(kx0, y0 + s * 0.178f), Size(dw * dashFrac, s * 0.009f), CornerRadius(s * 0.004f))
}

private fun DrawScope.drawPortrait(
    run: TbRun,
    art: TbSprites,
    cam: TbCam,
    hpFrac: Float,
) {
    val s = cam.s
    val w = s * 0.2f
    val h = w * 0.93f
    val x = s / 2f - w / 2f
    val y = s - h - s * 0.012f
    val shake = if (run.hurtFlash > 0.5f) sin(run.clock * 90f) * s * 0.004f else 0f
    drawRect(Color(0xFF14100C), Offset(x - s * 0.008f, y - s * 0.008f), Size(w + s * 0.016f, h + s * 0.02f))
    val img = art.portrait()
    val tint =
        when {
            hpFrac <= 0.3f -> ColorFilter.tint(TbColors.Blood.copy(alpha = 0.3f + 0.15f * sin(run.clock * 6f)), BlendMode.SrcAtop)
            hpFrac <= 0.6f -> ColorFilter.tint(Color(0xFF3A2A1A).copy(alpha = 0.3f), BlendMode.SrcAtop)
            else -> null
        }
    clipRect(x, y, x + w, y + h) {
        drawImage(
            img,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(img.width, img.height),
            dstOffset = IntOffset((x + shake).roundToInt(), y.roundToInt()),
            dstSize = IntSize(w.roundToInt(), h.roundToInt()),
            colorFilter = tint,
            filterQuality = FilterQuality.Medium,
        )
        // Cracks across the face as the demigod is worn down.
        val crack = Color.Black.copy(alpha = 0.7f)
        if (hpFrac <= 0.6f) drawLine(crack, Offset(x + w * 0.62f, y + h * 0.1f), Offset(x + w * 0.5f, y + h * 0.42f), s * 0.004f)
        if (hpFrac <= 0.3f) {
            drawLine(crack, Offset(x + w * 0.5f, y + h * 0.42f), Offset(x + w * 0.58f, y + h * 0.7f), s * 0.004f)
            drawLine(crack, Offset(x + w * 0.25f, y + h * 0.2f), Offset(x + w * 0.4f, y + h * 0.5f), s * 0.004f)
        }
    }
    drawRect(TbColors.Bronze, Offset(x - s * 0.008f, y - s * 0.008f), Size(w + s * 0.016f, h + s * 0.02f), style = Stroke(s * 0.007f))
    drawRect(TbColors.Gold.copy(alpha = 0.8f), Offset(x - s * 0.002f, y - s * 0.002f), Size(w + s * 0.004f, h + s * 0.008f), style = Stroke(s * 0.002f))
}

private fun DrawScope.drawHeart(
    c: Offset,
    r: Float,
) {
    val p =
        Path().apply {
            moveTo(c.x, c.y + r)
            cubicTo(c.x - r * 2f, c.y - r * 0.2f, c.x - r * 0.9f, c.y - r * 1.4f, c.x, c.y - r * 0.5f)
            cubicTo(c.x + r * 0.9f, c.y - r * 1.4f, c.x + r * 2f, c.y - r * 0.2f, c.x, c.y + r)
            close()
        }
    drawPath(p, Color(0xFFE5484D))
    drawPath(p, Color(0xFF5A0F18), style = Stroke(r * 0.15f))
}

private fun DrawScope.drawBolt(
    c: Offset,
    r: Float,
) {
    val p =
        Path().apply {
            moveTo(c.x + r * 0.3f, c.y - r * 1.2f)
            lineTo(c.x - r * 0.8f, c.y + r * 0.15f)
            lineTo(c.x - r * 0.05f, c.y + r * 0.15f)
            lineTo(c.x - r * 0.4f, c.y + r * 1.2f)
            lineTo(c.x + r * 0.9f, c.y - r * 0.25f)
            lineTo(c.x + r * 0.1f, c.y - r * 0.25f)
            close()
        }
    drawPath(p, Color(0xFF4DA8FF))
    drawPath(p, Color(0xFF0F2A5A), style = Stroke(r * 0.15f))
}

/** Used by the screens that share the bar's look: a marble banner with gold edges. */
internal fun DrawScope.drawBanner(
    topLeft: Offset,
    size: Size,
) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFFE6E0D2), Color(0xFFB9B1A0)), topLeft.y, topLeft.y + size.height), topLeft, size)
    drawRect(TbColors.Gold, topLeft, size, style = Stroke(size.height * 0.07f))
    drawRect(TbColors.GoldDark, Offset(topLeft.x + size.height * 0.1f, topLeft.y + size.height * 0.1f), Size(size.width - size.height * 0.2f, size.height * 0.8f), style = Stroke(size.height * 0.03f))
}

private const val HINT_SECONDS = 9f

/** A small round map at the top right: you at the centre looking up, a dot for each foe, an edge flash where you were hit. */
private fun DrawScope.drawRadar(
    run: TbRun,
    cam: TbCam,
) {
    val s = cam.s
    val c = Offset(s * 0.855f, s * 0.2f)
    val r = s * 0.115f
    drawCircle(Color.Black.copy(alpha = 0.55f), r, c)
    // The slice of the map you can see.
    val half = cam.fov / 2f
    for (side in intArrayOf(-1, 1)) {
        drawLine(
            Color.White.copy(alpha = 0.3f),
            c,
            Offset(c.x + sin(half) * side * r, c.y - cos(half) * r),
            s * 0.003f,
        )
    }
    drawCircle(TbColors.Gold.copy(alpha = 0.6f), r, c, style = Stroke(s * 0.004f))
    drawCircle(Color.White.copy(alpha = 0.15f), r * 0.5f, c, style = Stroke(s * 0.002f))
    for (h in run.hitDirs) {
        val age = (run.clock - h.at) / TbRun.HIT_ARROW_TIME
        if (age !in 0f..1f) continue
        val b = cam.bearing(run.px + cos(h.angle) * 10f, run.py + sin(h.angle) * 10f)
        drawArc(
            TbColors.Blood.copy(alpha = 0.9f * (1f - age)),
            startAngle = -90f + b * 180f / PI.toFloat() - 16f,
            sweepAngle = 32f,
            useCenter = false,
            topLeft = Offset(c.x - r, c.y - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(s * 0.012f, cap = StrokeCap.Round),
        )
    }
    for (e in run.enemies) {
        if (!e.alive || e.state == TbEState.Spawn) continue
        val d = TbWorld.dist(run.px, run.py, e.x, e.y)
        val b = cam.bearing(e.x, e.y)
        val rr = (d / RADAR_RANGE).coerceAtMost(1f) * (r - s * 0.012f)
        val p = Offset(c.x + sin(b) * rr, c.y - cos(b) * rr)
        val dot = if (e.boss) s * 0.016f else s * 0.01f
        drawCircle(if (e.boss) TbColors.Gold else Color(0xFFFF5A4A), dot, p)
        if (e.culprit > 0f) drawCircle(Color.White.copy(alpha = 0.9f), dot * 2f, p, style = Stroke(s * 0.004f))
        if (d > RADAR_RANGE) drawCircle(Color.Black.copy(alpha = 0.5f), dot * 0.4f, p)
    }
    val tri =
        Path().apply {
            moveTo(c.x, c.y - s * 0.015f)
            lineTo(c.x + s * 0.01f, c.y + s * 0.01f)
            lineTo(c.x - s * 0.01f, c.y + s * 0.01f)
            close()
        }
    drawPath(tri, Color.White)
}

private const val RADAR_RANGE = 20f
