package org.sableos.games

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/*
 * The first-person view of Tartarus Breach, drawn the way Gravel & Gears draws its trail: one Compose Canvas, redrawn
 * every frame from the run's state. The arena is 2.5D (owner's handoff): a panorama backdrop that turns with the
 * player, a perspective floor, and billboard sprites placed in world space and sorted far to near.
 */

private const val FOV = 1.4f
private const val CAM_H = 1.3f
private const val VIEW_SHARE = 0.78f
private const val HORIZON = 0.53f
private const val START_YAW = (PI / 2).toFloat()
private const val NEAR = 0.45f

/** Where the camera is and how world points land on the screen. One instance, reused every frame. */
internal class TbCam {
    var s = 1f
    var vh = 1f
    var hz = 0f
    var f = 1f
    private var cosY = 0f
    private var sinY = 1f
    private var cx = 0f
    private var cy = 0f

    /** Results of the last [project]: the depth and the screen column. */
    var z = 0f
    var sx = 0f

    /** How much wider than the square view the canvas is, each side. */
    var extra = 0f

    fun set(
        run: TbRun,
        side: Float,
        extraEach: Float = 0f,
    ) {
        s = side
        extra = extraEach
        vh = side * VIEW_SHARE
        hz = vh * HORIZON
        f = (side / 2f) / tan(FOV / 2f)
        cosY = cos(run.yaw)
        sinY = sin(run.yaw)
        cx = run.px
        cy = run.py
    }

    /** True if the point is in front of the camera. */
    fun project(
        wx: Float,
        wy: Float,
    ): Boolean {
        val dx = wx - cx
        val dy = wy - cy
        z = dx * cosY + dy * sinY
        val xr = dx * sinY - dy * cosY
        sx = s / 2f + xr / max(z, NEAR) * f
        return z > NEAR
    }

    fun floorY(depth: Float) = hz + CAM_H / max(depth, NEAR) * f

    fun heightY(
        depth: Float,
        h: Float,
    ) = hz + (CAM_H - h) / max(depth, NEAR) * f

    /** The bearing of a world point relative to where the camera looks: positive to the right. */
    fun bearing(
        wx: Float,
        wy: Float,
    ): Float {
        val dx = wx - cx
        val dy = wy - cy
        return atan2(dx * sinY - dy * cosY, dx * cosY + dy * sinY)
    }
}

private class TbThing(
    val z: Float,
    val kind: Int,
    val ref: Any,
)

private fun fog(z: Float) = 1f - ((z - 30f) / 16f).coerceIn(0f, 1f) * 0.4f

internal fun colorOf(c: Long) = Color(c.toInt())

private fun DrawScope.sprite(
    img: ImageBitmap,
    cx: Float,
    footY: Float,
    h: Float,
    alpha: Float = 1f,
    squeeze: Float = 1f,
    filter: ColorFilter? = null,
) {
    val w = h * img.width / img.height * squeeze
    if (w < 1f || h < 1f) return
    drawImage(
        img,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(img.width, img.height),
        dstOffset = IntOffset((cx - w / 2f).roundToInt(), (footY - h).roundToInt()),
        dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
        alpha = alpha.coerceIn(0f, 1f),
        colorFilter = filter,
        filterQuality = FilterQuality.Medium,
    )
}

/** The view part of the board: everything above the status bar. */
internal fun DrawScope.drawTartarusWorld(
    run: TbRun,
    art: TbSprites,
    cam: TbCam,
) {
    cam.set(run, size.height, (size.width - size.height) / 2f)
    val s = cam.s
    val ex = cam.extra
    val shake = run.shake * s * 0.012f
    val gate = run.gate
    clipRect(-ex, 0f, s + ex, cam.vh) {
        withTransform({
            translate(sin(run.clock * 83f) * shake, cos(run.clock * 71f) * shake)
            rotate(-run.strafeSpeed * 1.4f + run.hurtFlash * 0.8f, Offset(s / 2f, cam.hz))
            scale(1.04f, 1.04f, Offset(s / 2f, cam.hz))
        }) {
            drawBackdrop(run, art, cam, gate.number)
            drawFloorGrid(run, cam, colorOf(gate.glow))
            drawZones(run, cam)
            drawTelegraphs(run, cam)
            drawThings(run, art, cam, colorOf(gate.pillar), colorOf(gate.glow))
            drawEffects(run, cam)
        }
        // The hurt vignette: red from the edges, strongest when a hit has just landed.
        if (run.hurtFlash > 0f) {
            drawRect(
                Brush.radialGradient(
                    listOf(Color.Transparent, TbColors.Blood.copy(alpha = 0.55f * run.hurtFlash)),
                    center = Offset(s / 2f, cam.vh / 2f),
                    radius = s * 0.78f,
                ),
                topLeft = Offset(-ex, 0f),
                size = Size(s + 2f * ex, cam.vh),
            )
        }
        if (run.slowT > 0f) drawRect(Color(0xFF7FE8C8).copy(alpha = 0.10f), topLeft = Offset(-ex, 0f), size = Size(s + 2f * ex, cam.vh))
        if (run.dashT > 0f) drawDashLines(run, cam)
    }
}

private fun DrawScope.drawBackdrop(
    run: TbRun,
    art: TbSprites,
    cam: TbCam,
    gateNo: Int,
) {
    val img = art.background(gateNo)
    val pxPerRad = cam.s / FOV
    val tileW = cam.s * (PI.toFloat() / 2f) / FOV
    val base = cam.s / 2f + (run.yaw - START_YAW) * pxPerRad
    // The image's own horizon sits about 53 % down; line it up with the camera's.
    val top = cam.hz - HORIZON * tileW
    val first = -((base / tileW).toInt()) - 3
    for (k in first..first + 8) {
        val c = base + k * tileW
        if (c + tileW / 2f < -cam.extra || c - tileW / 2f > cam.s + cam.extra) continue
        withTransform({ if (k and 1 != 0) scale(-1f, 1f, Offset(c, 0f)) }) {
            drawImage(
                img,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(img.width, img.height),
                dstOffset = IntOffset((c - tileW / 2f).roundToInt(), top.roundToInt()),
                dstSize = IntSize(tileW.roundToInt() + 2, tileW.roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}

/** A faint grid on the floor in world space, so walking and turning read as movement. */
private fun DrawScope.drawFloorGrid(
    run: TbRun,
    cam: TbCam,
    glow: Color,
) {
    val step = 4f
    val reach = 36f
    val color = glow.copy(alpha = 0.16f)
    val w = cam.s * 0.004f
    val x0 = (kotlin.math.floor((run.px - reach) / step) * step)
    val y0 = (kotlin.math.floor((run.py - reach) / step) * step)
    var gx = x0
    while (gx <= run.px + reach) {
        gridLine(cam, gx, run.py - reach, gx, run.py + reach, color, w)
        gx += step
    }
    var gy = y0
    while (gy <= run.py + reach) {
        gridLine(cam, run.px - reach, gy, run.px + reach, gy, color, w)
        gy += step
    }
}

private fun DrawScope.gridLine(
    cam: TbCam,
    ax: Float,
    ay: Float,
    bx: Float,
    by: Float,
    color: Color,
    width: Float,
) {
    cam.project(ax, ay)
    val za = cam.z
    val xa = cam.sx
    cam.project(bx, by)
    val zb = cam.z
    if (za < 0.8f && zb < 0.8f) return
    // Clip against the near plane in world space, where the line is still straight.
    var pax = ax
    var pay = ay
    var pbx = bx
    var pby = by
    if (za < 0.8f) {
        val t = (0.8f - za) / (zb - za)
        pax = ax + (bx - ax) * t
        pay = ay + (by - ay) * t
    } else if (zb < 0.8f) {
        val t = (0.8f - zb) / (za - zb)
        pbx = bx + (ax - bx) * t
        pby = by + (ay - by) * t
    }
    cam.project(pax, pay)
    val s1 = Offset(cam.sx, cam.floorY(cam.z))
    cam.project(pbx, pby)
    val s2 = Offset(cam.sx, cam.floorY(cam.z))
    if (xa.isNaN()) return
    drawLine(color, s1, s2, width)
}

private fun DrawScope.floorPoint(
    cam: TbCam,
    x: Float,
    y: Float,
): Offset {
    cam.project(x, y)
    return Offset(cam.sx, cam.floorY(cam.z))
}

private fun DrawScope.floorCircle(
    cam: TbCam,
    x: Float,
    y: Float,
    r: Float,
): Path {
    val p = Path()
    val n = 28
    for (i in 0..n) {
        val a = i * 2f * PI.toFloat() / n
        val o = floorPoint(cam, x + cos(a) * r, y + sin(a) * r)
        if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y)
    }
    p.close()
    return p
}

private fun DrawScope.drawZones(
    run: TbRun,
    cam: TbCam,
) {
    val lineW = cam.s * 0.006f
    for (z in run.zones) {
        when (z.kind) {
            TbZoneKind.Fog -> {
                drawPath(floorCircle(cam, z.x, z.y, z.r), Color(0xFF9FB7D6).copy(alpha = 0.16f + 0.05f * sin(run.clock * 1.5f + z.phase)))
            }

            TbZoneKind.Time -> {
                drawPath(floorCircle(cam, z.x, z.y, z.r), Color(0xFFB07CFF).copy(alpha = 0.18f))
                drawPath(floorCircle(cam, z.x, z.y, z.r * (0.5f + 0.4f * ((run.clock * 0.6f + z.phase) % 1f))), Color(0xFFD9B8FF).copy(alpha = 0.5f), style = Stroke(lineW))
            }

            TbZoneKind.Rift -> {
                val pulse = 0.25f + 0.1f * sin(run.clock * 6f)
                drawPath(floorCircle(cam, z.x, z.y, z.r), Color(0xFFC77DFF).copy(alpha = pulse))
                drawPath(floorCircle(cam, z.x, z.y, z.r), Color(0xFFE8C8FF), style = Stroke(lineW))
            }

            TbZoneKind.LavaRing -> {
                drawPath(floorCircle(cam, z.x, z.y, z.r), Color(0xFFFF7B00).copy(alpha = 0.85f), style = Stroke(lineW * 2.4f))
            }

            TbZoneKind.LavaStrip -> {
                val a = floorPoint(cam, z.x - z.r, -run.radius)
                val b = floorPoint(cam, z.x + z.r, -run.radius)
                val c = floorPoint(cam, z.x + z.r, run.radius)
                val d = floorPoint(cam, z.x - z.r, run.radius)
                val q = Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close() }
                drawPath(q, Color(0xFFFF6A00).copy(alpha = 0.55f + 0.1f * sin(run.clock * 3f)))
            }

            TbZoneKind.Vent -> {
                val (fill, edge) =
                    when (z.stage) {
                        1 -> Color(0xFFFF3B30).copy(alpha = 0.25f + 0.2f * sin(run.clock * 18f)) to Color(0xFFFF3B30)
                        2 -> Color(0xFFFFB347).copy(alpha = 0.8f) to Color(0xFFFFE08A)
                        else -> Color(0xFF3A1E14).copy(alpha = 0.7f) to Color(0xFFFF7B00).copy(alpha = 0.6f)
                    }
                drawPath(floorCircle(cam, z.x, z.y, z.r), fill)
                drawPath(floorCircle(cam, z.x, z.y, z.r), edge, style = Stroke(lineW))
            }

            TbZoneKind.Spikes -> {
                val fill = if (z.stage == 1) Color(0xFFFF3B30).copy(alpha = 0.3f) else Color(0xFF3A3A44).copy(alpha = 0.6f)
                drawPath(floorCircle(cam, z.x, z.y, z.r), fill)
                drawPath(floorCircle(cam, z.x, z.y, z.r), Color(0xFFB0B0C0).copy(alpha = 0.7f), style = Stroke(lineW))
                if (z.stage == 2) {
                    for (i in 0 until 7) {
                        val a = i * 0.9f
                        val rr = if (i == 0) 0f else z.r * 0.6f
                        val b = floorPoint(cam, z.x + cos(a) * rr, z.y + sin(a) * rr)
                        val h = 1.1f / max(cam.z, NEAR) * cam.f
                        drawLine(Color(0xFFE6E6F0), b, Offset(b.x, b.y - h), cam.s * 0.012f, StrokeCap.Round)
                    }
                }
            }

            TbZoneKind.Well -> {
                val rr = z.r * (0.35f + 0.65f * ((1f - (run.clock * 0.5f) % 1f)))
                drawPath(floorCircle(cam, z.x, z.y, z.r), Color(0xFF2A0F4A).copy(alpha = 0.3f))
                drawPath(floorCircle(cam, z.x, z.y, rr), Color(0xFFC77DFF).copy(alpha = 0.55f), style = Stroke(lineW))
            }
        }
    }
}

private fun DrawScope.drawTelegraphs(
    run: TbRun,
    cam: TbCam,
) {
    for (t in run.teles) {
        val p = 1f - (t.t / t.total).coerceIn(0f, 1f)
        val fill = Color(0xFFFF2A1A).copy(alpha = 0.14f + 0.32f * p)
        val edge = Color(0xFFFF6A4A).copy(alpha = 0.55f + 0.4f * p)
        if (t.line) {
            val dx = t.x2 - t.x
            val dy = t.y2 - t.y
            val len = max(hypot(dx, dy), 0.01f)
            val nx = -dy / len * t.r
            val ny = dx / len * t.r
            val a = floorPoint(cam, t.x + nx, t.y + ny)
            val b = floorPoint(cam, t.x2 + nx, t.y2 + ny)
            val c = floorPoint(cam, t.x2 - nx, t.y2 - ny)
            val d = floorPoint(cam, t.x - nx, t.y - ny)
            val q = Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close() }
            drawPath(q, fill)
            drawPath(q, edge, style = Stroke(cam.s * 0.004f))
        } else {
            drawPath(floorCircle(cam, t.x, t.y, t.r), fill)
            drawPath(floorCircle(cam, t.x, t.y, t.r), edge, style = Stroke(cam.s * 0.005f))
            // The fill grows from the middle so the player can read the moment of impact.
            drawPath(floorCircle(cam, t.x, t.y, t.r * p), edge.copy(alpha = 0.3f))
        }
    }
}

private fun DrawScope.drawThings(
    run: TbRun,
    art: TbSprites,
    cam: TbCam,
    pillar: Color,
    glow: Color,
) {
    val things = ArrayList<TbThing>(run.enemies.size + run.pillars.size + run.projs.size)
    for (p in run.pillars) if (cam.project(p.x, p.y)) things += TbThing(cam.z, 0, p)
    for (e in run.enemies) if (cam.project(e.x, e.y)) things += TbThing(cam.z, 1, e)
    for (p in run.projs) if (cam.project(p.x, p.y)) things += TbThing(cam.z, 2, p)
    things.sortByDescending { it.z }
    for (t in things) {
        when (t.kind) {
            0 -> drawPillar(t.ref as TbPillar, cam, pillar, glow)
            1 -> drawEnemy(t.ref as TbEnemy, run, art, cam)
            else -> drawShot(t.ref as TbProj, cam)
        }
    }
}

private fun DrawScope.drawPillar(
    p: TbPillar,
    cam: TbCam,
    body: Color,
    glow: Color,
) {
    cam.project(p.x, p.y)
    val z = cam.z
    val sx = cam.sx
    val hh = 9f / z * cam.f
    val ww = 2f * p.r / z * cam.f
    val foot = cam.floorY(z)
    val top = foot - hh
    val a = fog(z)
    val dark = lerpColor(body, Color.Black, 0.55f).copy(alpha = a)
    val mid = body.copy(alpha = a)
    val lit = lerpColor(body, glow, 0.25f).copy(alpha = a)
    drawRect(Brush.horizontalGradient(listOf(dark, lit, mid, dark), startX = sx - ww / 2f, endX = sx + ww / 2f), Offset(sx - ww / 2f, top), Size(ww, hh))
    // Capital and base: wider, darker blocks, so a column reads as a column.
    drawRect(dark, Offset(sx - ww * 0.58f, top), Size(ww * 1.16f, hh * 0.06f))
    drawRect(dark, Offset(sx - ww * 0.58f, foot - hh * 0.05f), Size(ww * 1.16f, hh * 0.05f))
    drawLine(Color.Black.copy(alpha = 0.35f * a), Offset(sx - ww * 0.12f, top + hh * 0.2f), Offset(sx - ww * 0.02f, top + hh * 0.5f), ww * 0.02f)
    drawLine(Color.Black.copy(alpha = 0.35f * a), Offset(sx + ww * 0.2f, top + hh * 0.45f), Offset(sx + ww * 0.12f, top + hh * 0.75f), ww * 0.02f)
    drawLine(glow.copy(alpha = 0.22f * a), Offset(sx + ww * 0.46f, top + hh * 0.07f), Offset(sx + ww * 0.46f, foot - hh * 0.05f), ww * 0.03f)
}

private fun lerpColor(
    a: Color,
    b: Color,
    t: Float,
) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)

private fun DrawScope.drawEnemy(
    e: TbEnemy,
    run: TbRun,
    art: TbSprites,
    cam: TbCam,
) {
    val z = cam.z
    val sx = cam.sx
    val img = art.enemy(e.def.id)
    var scale = 1f
    var alpha = fog(z)
    var lift = 0f
    var squash = 1f
    when {
        e.state == TbEState.Dying -> {
            val p = e.fall.coerceIn(0f, 1f)
            alpha *= 1f - p
            scale = 1f - 0.35f * p
            lift = p * 0.7f
        }

        e.state == TbEState.Spawn -> {
            val p = (e.age / if (e.boss) 1.8f else 0.7f).coerceIn(0f, 1f)
            scale = 0.15f + 0.85f * p
            alpha *= p
        }
    }
    val worldH = TbLook.height(e.def) * scale
    val hover = TbLook.hover(e.def) * (if (e.state == TbEState.Dying) 0.3f else 1f) + (if (TbLook.hover(e.def) > 0f) sin(e.age * 3f + e.uid) * 0.2f else 0f) + lift
    val moving = e.state == TbEState.Move
    val bob = if (moving && hover == 0f) abs(sin(e.age * e.def.speed * 1.6f)) * 0.06f * worldH else 0f
    if (e.state == TbEState.Windup) squash = 1f + 0.05f * sin(e.age * 34f)
    // Never smaller than a readable minimum, however far away (bosses are big enough already).
    val h = max(worldH / z * cam.f, if (e.boss) 0f else cam.s * 0.16f * scale)
    val foot = cam.heightY(z, hover) - bob / z * cam.f
    val floor = cam.floorY(z)
    val ww = h * img.width / img.height
    // A shadow on the floor, smaller the higher the foe floats.
    drawOval(
        Color.Black.copy(alpha = 0.38f * alpha / (1f + hover)),
        Offset(sx - ww * 0.34f, floor - ww * 0.04f),
        Size(ww * 0.68f, ww * 0.09f),
    )
    val tint: ColorFilter? =
        when {
            e.flash > 0f -> ColorFilter.tint(Color.White.copy(alpha = 0.7f), BlendMode.SrcAtop)
            e.state == TbEState.Windup -> ColorFilter.tint(Color(0xFFFF3B30).copy(alpha = 0.25f + 0.2f * sin(e.age * 28f)), BlendMode.SrcAtop)
            e.burn > 0f -> ColorFilter.tint(Color(0xFFFF7B00).copy(alpha = 0.3f), BlendMode.SrcAtop)
            e.marked > 0f -> ColorFilter.tint(Color(0xFFB44CFF).copy(alpha = 0.25f), BlendMode.SrcAtop)
            e.enraged -> ColorFilter.tint(Color(0xFFFF3B30).copy(alpha = 0.18f), BlendMode.SrcAtop)
            e.state == TbEState.Stun -> ColorFilter.tint(Color(0xFFFFE08A).copy(alpha = 0.3f), BlendMode.SrcAtop)
            else -> null
        }
    withTransform({ rotate(sin(e.age * 5f + e.uid) * (if (moving) 2.2f else 0.6f), Offset(sx, foot)) }) {
        sprite(img, sx, foot, h, alpha, squash, tint)
    }
    if (!e.boss && e.state != TbEState.Dying && e.hp < e.maxHp && e.def.tier != "swarm") {
        val bw = max(ww * 0.55f, cam.s * 0.04f)
        val by = foot - h - cam.s * 0.012f
        drawRect(Color.Black.copy(alpha = 0.6f * alpha), Offset(sx - bw / 2f, by), Size(bw, cam.s * 0.008f))
        drawRect(TbColors.Hp.copy(alpha = alpha), Offset(sx - bw / 2f, by), Size(bw * (e.hp / e.maxHp).coerceIn(0f, 1f), cam.s * 0.008f))
    }
}

private fun DrawScope.drawShot(
    p: TbProj,
    cam: TbCam,
) {
    val z = cam.z
    val sx = cam.sx
    val y = cam.heightY(z, 1.1f)
    val r = (p.radius * 1.5f / z * cam.f).coerceIn(cam.s * 0.008f, cam.s * 0.1f)
    val c = colorOf(p.color)
    val a = fog(z)
    if (p.friendly) {
        for (k in 1..4) {
            if (cam.project(p.x - p.dx * k * 0.6f, p.y - p.dy * k * 0.6f)) {
                drawCircle(c.copy(alpha = 0.22f * (5 - k) / 4f), r * (1f - k * 0.15f), Offset(cam.sx, cam.heightY(cam.z, 1.1f)))
            }
        }
    }
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = a), c.copy(alpha = 0.85f * a), c.copy(alpha = 0f)), Offset(sx, y), r * 1.6f), r * 1.6f, Offset(sx, y))
}

private fun DrawScope.drawEffects(
    run: TbRun,
    cam: TbCam,
) {
    val muzzle = Offset(cam.s * 0.68f, cam.vh * 0.84f)
    for (f in run.fx) {
        val p = (f.t / f.life).coerceIn(0f, 1f)
        val c = colorOf(f.color)
        when (f.kind) {
            TbFxKind.Beam -> {
                val end = worldToScreen(cam, f.x2, f.y2, 1.0f) ?: Offset(cam.s / 2f, cam.hz)
                drawFireStreak(muzzle, end, c, p, cam.s, f.n + (run.clock * 30f).toInt())
            }

            TbFxKind.Wave -> drawWave(f, run, cam, p, c)

            TbFxKind.Chain -> {
                val from = if (f.n == 0) muzzle.copy(y = muzzle.y - cam.s * 0.2f) else worldToScreen(cam, f.x, f.y, 1.2f)
                val to = worldToScreen(cam, f.x2, f.y2, 1.2f)
                if (from != null && to != null) drawBolt(from, to, c, p, f.n * 7 + (run.clock * 40f).toInt(), cam.s)
            }

            TbFxKind.Puff -> {
                if (cam.project(f.x, f.y)) {
                    drawCircle(c.copy(alpha = 0.35f * (1f - p)), (0.6f + 1.6f * p) / cam.z * cam.f, Offset(cam.sx, cam.floorY(cam.z) - 0.4f / cam.z * cam.f))
                }
            }

            TbFxKind.Burst -> {
                if (cam.project(f.x, f.y)) {
                    val o = Offset(cam.sx, cam.heightY(cam.z, 1.0f))
                    val r = (0.8f + 3.2f * p * (1f + f.n * 0.4f)) / cam.z * cam.f
                    drawCircle(c.copy(alpha = 0.5f * (1f - p)), r, o, style = Stroke(cam.s * 0.01f))
                    for (i in 0 until 8) {
                        val a = i * PI.toFloat() / 4f + f.x
                        drawCircle(c.copy(alpha = 1f - p), cam.s * 0.008f * (1f - p * 0.5f), Offset(o.x + cos(a) * r * 0.9f, o.y + sin(a) * r * 0.9f - p * cam.s * 0.03f))
                    }
                }
            }

            TbFxKind.Coin -> {
                if (cam.project(f.x, f.y)) {
                    val from = Offset(cam.sx, cam.heightY(cam.z, 1.0f))
                    val to = Offset(cam.s * 0.9f, cam.s * 0.045f)
                    val q = p * p
                    val o = Offset(from.x + (to.x - from.x) * q, from.y + (to.y - from.y) * q - sin(p * PI.toFloat()) * cam.s * 0.1f)
                    drawCircle(TbColors.Gold, cam.s * 0.014f, o)
                    drawCircle(Color(0xFFFFF3C0), cam.s * 0.007f, o.copy(x = o.x - cam.s * 0.003f, y = o.y - cam.s * 0.003f))
                }
            }

            TbFxKind.Ember -> {
                if (cam.project(f.x, f.y)) drawCircle(c.copy(alpha = 0.6f * (1f - p)), 0.3f / cam.z * cam.f, Offset(cam.sx, cam.heightY(cam.z, 0.8f)))
            }

            TbFxKind.Text, TbFxKind.Hurt -> Unit
        }
    }
}

/** The floating damage numbers: drawn by the caller, which owns the text measurer. */
internal fun DrawScope.textPos(
    cam: TbCam,
    f: TbFx,
): Offset? {
    val o = worldToScreen(cam, f.x, f.y, 2.2f) ?: return null
    val p = (f.t / f.life).coerceIn(0f, 1f)
    return Offset(o.x, o.y - p * cam.s * 0.07f)
}

private fun worldToScreen(
    cam: TbCam,
    x: Float,
    y: Float,
    h: Float,
): Offset? = if (cam.project(x, y)) Offset(cam.sx, cam.heightY(cam.z, h)) else null

private fun DrawScope.drawBolt(
    a: Offset,
    b: Offset,
    c: Color,
    p: Float,
    seed: Int,
    s: Float,
) {
    val path = Path().apply { moveTo(a.x, a.y) }
    val n = 8
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len = max(hypot(dx, dy), 1f)
    for (i in 1 until n) {
        val t = i / n.toFloat()
        val j = (((seed * 31 + i * 17) % 100) / 100f - 0.5f) * s * 0.07f
        path.lineTo(a.x + dx * t - dy / len * j, a.y + dy * t + dx / len * j)
    }
    path.lineTo(b.x, b.y)
    val fade = 1f - p
    drawPath(path, c.copy(alpha = 0.4f * fade), style = Stroke(s * 0.034f, cap = StrokeCap.Round))
    drawPath(path, c.copy(alpha = 0.9f * fade), style = Stroke(s * 0.014f, cap = StrokeCap.Round))
    drawPath(path, Color.White.copy(alpha = fade), style = Stroke(s * 0.005f, cap = StrokeCap.Round))
}

/** Poseidon's wave: a fan of foam and droplets rolling out across the floor. */
private fun DrawScope.drawWave(
    f: TbFx,
    run: TbRun,
    cam: TbCam,
    p: Float,
    c: Color,
) {
    val aim = atan2(f.y2, f.x2)
    val reach = 1f + p * 9.5f
    val half = 0.21f
    val fan = Path()
    var started = false
    for (i in 0..16) {
        val a = aim - half + 2f * half * i / 16f
        val o = floorPoint(cam, run.px + cos(a) * reach, run.py + sin(a) * reach)
        if (!started) {
            fan.moveTo(o.x, o.y)
            started = true
        } else {
            fan.lineTo(o.x, o.y)
        }
    }
    val back = floorPoint(cam, run.px + cos(aim) * reach * 0.3f, run.py + sin(aim) * reach * 0.3f)
    fan.lineTo(back.x, back.y)
    fan.close()
    drawPath(fan, c.copy(alpha = 0.32f * (1f - p)))
    drawPath(fan, Color.White.copy(alpha = 0.5f * (1f - p)), style = Stroke(cam.s * 0.008f))
    for (i in 0 until 46) {
        val h = (i * 2654435761L % 1000L) / 1000f
        val h2 = (i * 40503L % 1000L) / 1000f
        val a = aim + (h - 0.5f) * 2f * half
        val d = reach * (0.35f + 0.65f * h2)
        if (!cam.project(run.px + cos(a) * d, run.py + sin(a) * d)) continue
        val y = cam.heightY(cam.z, 0.25f + 1.4f * sin(p * PI.toFloat()) * h2)
        drawCircle((if (i % 3 == 0) Color.White else c).copy(alpha = 0.9f * (1f - p)), cam.s * (0.006f + 0.008f * h2) * (1f - p * 0.4f), Offset(cam.sx, y))
    }
}

private fun DrawScope.drawDashLines(
    run: TbRun,
    cam: TbCam,
) {
    val c = Color(0xFF00E5A0).copy(alpha = 0.4f)
    for (i in 0 until 14) {
        val x = ((i * 53 + (run.clock * 900f).toInt()) % 100) / 100f * cam.s
        val y = ((i * 37) % 100) / 100f * cam.vh
        drawLine(c, Offset(x, y), Offset(cam.s / 2f + (x - cam.s / 2f) * 1.3f, y + (y - cam.hz) * 0.3f), cam.s * 0.004f)
    }
}

/** The Sunbow's shot: a tapering streak of flame, fat at the bow and thin at the target, with embers streaming off it. */
private fun DrawScope.drawFireStreak(
    from: Offset,
    to: Offset,
    c: Color,
    p: Float,
    s: Float,
    seed: Int,
) {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val len = hypot(dx, dy).coerceAtLeast(1f)
    val nx = -dy / len
    val ny = dx / len
    val fade = 1f - p
    for ((widthFrac, color, a) in listOf(Triple(0.16f, c, 0.3f), Triple(0.1f, Color(0xFFFF9A2E), 0.65f), Triple(0.05f, Color(0xFFFFE08A), 0.9f), Triple(0.02f, Color.White, 1f))) {
        val w0 = s * widthFrac * (0.6f + 0.4f * fade)
        val w1 = w0 * 0.14f
        val path =
            Path().apply {
                moveTo(from.x + nx * w0, from.y + ny * w0)
                lineTo(to.x + nx * w1, to.y + ny * w1)
                lineTo(to.x - nx * w1, to.y - ny * w1)
                lineTo(from.x - nx * w0, from.y - ny * w0)
                close()
            }
        drawPath(path, color.copy(alpha = a * fade))
    }
    for (i in 0 until 14) {
        val t = (i + 0.5f) / 14f
        val jitter = (((seed * 31 + i * 17) % 23) - 11) / 11f
        val r = s * (0.012f + 0.01f * ((i * 7 + seed) % 5) / 5f) * fade
        drawCircle(Color(0xFFFFC04D).copy(alpha = 0.85f * fade), r, Offset(from.x + dx * t + nx * jitter * s * 0.05f * (1f - t * 0.6f), from.y + dy * t + ny * jitter * s * 0.05f * (1f - t * 0.6f) - p * s * 0.04f))
    }
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = fade), c.copy(alpha = 0.7f * fade), Color.Transparent), to, s * 0.07f * (1f + p)), s * 0.07f * (1f + p), to)
}
