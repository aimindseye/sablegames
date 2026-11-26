package org.sableos.games

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The rider built from parts: a bike and an animal drawn from lines and shapes, posed every frame. The wheels spin
 * with the distance ridden, the cranks turn, and each leg is worked out from hip to pedal so the knees rise and fall.
 * The bike banks into a turn, swings round to face the way it is steered, pitches up in the air, and the rider stands
 * on the pedals to sprint.
 *
 * Parts are placed on the bike in its own frame: u forward, w to the side, h up, in "units" of about two thirds of
 * a tile. [GgRigMath] turns them into screen points, and has no Android in it so it can be tested.
 */

/** How one rider looks. */
internal data class GgLook(
    val fur: Color,
    val belly: Color,
    val shade: Color,
    val helmet: Color,
    val frame: Color,
    val fork: Color,
    val ears: Ears,
    val tail: Tail,
    val stripe: Boolean = false,
) {
    enum class Ears { HORNS, POINTED, ROUND }

    enum class Tail { TUFT, BRUSH, STUB }

    companion object {
        val Gravel =
            GgLook(
                fur = Color(0xFFF2E9D8), belly = Color(0xFFFFFAF0), shade = Color(0xFFCDBFA6), helmet = Color(0xFFF06A24),
                frame = Color(0xFFB4532A), fork = Color(0xFFE8892E), ears = Ears.HORNS, tail = Tail.TUFT,
            )
        val Spokes =
            GgLook(
                fur = Color(0xFFD9542A), belly = Color(0xFFFFF1E2), shade = Color(0xFFA63B1E), helmet = Color(0xFF2E9C96),
                frame = Color(0xFF3D8C86), fork = Color(0xFFF08A2A), ears = Ears.POINTED, tail = Tail.BRUSH,
            )
        val Knobby =
            GgLook(
                fur = Color(0xFF45494F), belly = Color(0xFF6A6E73), shade = Color(0xFF2C2F33), helmet = Color(0xFFF2B630),
                frame = Color(0xFF34555A), fork = Color(0xFFF09A2A), ears = Ears.ROUND, tail = Tail.STUB, stripe = true,
            )

        fun of(id: String) =
            when (id) {
                "spokes" -> Spokes
                "knobby" -> Knobby
                else -> Gravel
            }
    }
}

/** One frame of the rider: everything that moves. Angles are in radians. */
internal data class GgRigPose(
    /** Which way the bike points on screen: 0 straight down the trail, positive to the right. */
    val yaw: Float,
    /** Banking into a turn, positive to the right. */
    val roll: Float,
    /** Nose up in the air, positive up. */
    val pitch: Float,
    /** The crank angle and the wheel turn, both from the distance ridden. */
    val crank: Float,
    val wheel: Float,
    /** How far the rider stands up off the saddle, 0 sitting to 1 standing. */
    val stand: Float,
    /** How low the rider tucks over the bars, 0 to 1. */
    val tuck: Float,
)

internal object GgRigMath {
    /** Wheel radius, wheelbase and crank, in units. */
    const val WHEEL = 0.34f
    const val REAR = -0.56f
    const val FRONT = 0.56f
    const val CRANK = 0.17f
    const val THIGH = 0.42f
    const val SHIN = 0.42f

    /** How much the forward axis shows on screen against the up axis: the camera looks down at the trail. */
    const val DEPTH = 0.5f

    /** The bottom bracket, where the cranks turn. */
    val BB = floatArrayOf(-0.04f, 0.3f)

    /**
     * A point on the bike ([u], [w], [h]) on screen, in units from the ground under the bike, after [pose]'s pitch,
     * roll and yaw. Returns screen x, screen y (down) and a depth that grows toward the viewer.
     */
    fun project(
        pose: GgRigPose,
        u: Float,
        w: Float,
        h: Float,
    ): FloatArray {
        // Pitch about the rear axle when the nose is up, so the rear wheel stays on the ground for a manual.
        val pu = u - REAR
        val ph = h - WHEEL
        val cp = cos(pose.pitch)
        val sp = sin(pose.pitch)
        val u1 = REAR + pu * cp - ph * sp
        val h1 = WHEEL + pu * sp + ph * cp
        // Roll about the line along the ground.
        val cr = cos(pose.roll)
        val sr = sin(pose.roll)
        val side = w * cr + h1 * sr
        val up = -w * sr + h1 * cr
        // Yaw: forward turns from straight down the screen toward the side the bike faces.
        val cy = cos(pose.yaw)
        val sy = sin(pose.yaw)
        val worldX = u1 * sy + side * cy
        val worldY = u1 * cy - side * sy
        return floatArrayOf(worldX, worldY * DEPTH - up, worldY + up * 0.2f)
    }

    /** Where a pedal is, in (u, h), for crank angle [a]. */
    fun pedal(a: Float): FloatArray = floatArrayOf(BB[0] + CRANK * cos(a), BB[1] + CRANK * sin(a))

    /**
     * The knee for a leg from [hip] to [foot] (both (u, h)), bending forward. When the foot is out of reach the
     * leg goes straight toward it.
     */
    fun knee(
        hip: FloatArray,
        foot: FloatArray,
        thigh: Float = THIGH,
        shin: Float = SHIN,
    ): FloatArray {
        val du = foot[0] - hip[0]
        val dh = foot[1] - hip[1]
        val d = sqrt(du * du + dh * dh).coerceIn(0.001f, thigh + shin - 0.001f)
        val toFoot = atan2(dh, du)
        val bend = acos(((thigh * thigh + d * d - shin * shin) / (2 * thigh * d)).coerceIn(-1f, 1f))
        // Two answers, one each side of the hip-foot line: take the one further forward.
        val a1 = toFoot + bend
        val a2 = toFoot - bend
        val k1 = floatArrayOf(hip[0] + thigh * cos(a1), hip[1] + thigh * sin(a1))
        val k2 = floatArrayOf(hip[0] + thigh * cos(a2), hip[1] + thigh * sin(a2))
        return if (k1[0] >= k2[0]) k1 else k2
    }
}

/** What the rig is drawn with: a canvas on the phone, a recorder in the tests. */
internal interface GgPen {
    fun line(
        c: Color,
        a: Offset,
        b: Offset,
        width: Float,
    )

    fun dot(
        c: Color,
        p: Offset,
        r: Float,
    )

    /** A closed shape through [pts], filled, or outlined [stroke] wide when it is above 0. */
    fun shape(
        c: Color,
        pts: List<Offset>,
        stroke: Float = 0f,
    )
}

/** Draws the rig on a Compose canvas. */
internal fun DrawScope.drawRig(
    look: GgLook,
    pose: GgRigPose,
    ground: Offset,
    unit: Float,
    night: Boolean,
    alpha: Float = 1f,
) {
    val scope = this
    val pen =
        object : GgPen {
            override fun line(
                c: Color,
                a: Offset,
                b: Offset,
                width: Float,
            ) = scope.drawLine(c, a, b, width, StrokeCap.Round)

            override fun dot(
                c: Color,
                p: Offset,
                r: Float,
            ) = scope.drawCircle(c, r, p)

            override fun shape(
                c: Color,
                pts: List<Offset>,
                stroke: Float,
            ) {
                if (pts.isEmpty()) return
                val path = Path()
                path.moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                path.close()
                scope.drawPath(path, c, style = if (stroke > 0f) Stroke(stroke) else Fill)
            }
        }
    paintRig(pen, look, pose, ground, unit, night, alpha)
}

/**
 * Paints the rider built from parts with [pen], standing on the ground at [ground] with [unit] pixels to a unit.
 * [night] adds the glowing pack and helmet lamp.
 */
internal fun paintRig(
    pen: GgPen,
    look: GgLook,
    pose: GgRigPose,
    ground: Offset,
    unit: Float,
    night: Boolean,
    alpha: Float = 1f,
) {
    fun at(
        u: Float,
        w: Float,
        h: Float,
    ): Offset {
        val p = GgRigMath.project(pose, u, w, h)
        return Offset(ground.x + p[0] * unit, ground.y + p[1] * unit)
    }

    fun depth(
        u: Float,
        w: Float,
        h: Float,
    ) = GgRigMath.project(pose, u, w, h)[2]

    fun line(
        c: Color,
        a: Offset,
        b: Offset,
        width: Float,
    ) = pen.line(c.copy(alpha = c.alpha * alpha), a, b, width * unit)

    fun dot(
        c: Color,
        p: Offset,
        r: Float,
    ) = pen.dot(c.copy(alpha = c.alpha * alpha), p, r * unit)

    val tyre = Color(0xFF2A2522)
    val rim = Color(0xFFBDB6AC)
    val dark = Color(0xFF2B2622)
    // The side nearer the viewer is drawn last; which one that is depends on the way the bike faces.
    val near = if (depth(0f, 1f, 0.5f) > depth(0f, -1f, 0.5f)) 1f else -1f
    val far = -near

    val bob = sin(pose.crank * 2f).let { it * it } * 0.02f * (1f - pose.stand)
    val hip = floatArrayOf(-0.24f + pose.stand * 0.12f, 0.86f + pose.stand * 0.16f - bob - pose.tuck * 0.04f)
    val shoulder = floatArrayOf(hip[0] + 0.3f + pose.tuck * 0.08f, hip[1] + 0.36f - pose.tuck * 0.12f)
    val head = floatArrayOf(shoulder[0] + 0.16f + pose.tuck * 0.04f, shoulder[1] + 0.16f - pose.tuck * 0.05f)
    val grip = floatArrayOf(0.36f, 0.98f)

    fun leg(
        side: Float,
        a: Float,
        shade: Boolean,
    ) {
        val foot = GgRigMath.pedal(a)
        val knee = GgRigMath.knee(hip, foot)
        val w = side * 0.11f
        val c = if (shade) look.shade else look.fur
        val bbPt = at(GgRigMath.BB[0], side * 0.07f, GgRigMath.BB[1])
        line(dark, bbPt, at(foot[0], w, foot[1]), 0.05f)
        line(c, at(hip[0], w, hip[1]), at(knee[0], w, knee[1]), 0.15f)
        line(c, at(knee[0], w, knee[1]), at(foot[0], w, foot[1]), 0.11f)
        dot(dark, at(foot[0] + 0.03f, w, foot[1] - 0.01f), 0.065f)
    }

    fun arm(
        side: Float,
        shade: Boolean,
    ) {
        val w = side * 0.2f
        val s = at(shoulder[0], side * 0.12f, shoulder[1])
        val g = at(grip[0], w, grip[1])
        val elbow = at((shoulder[0] + grip[0]) / 2f - 0.02f, side * 0.24f, (shoulder[1] + grip[1]) / 2f - 0.05f)
        val c = if (shade) look.shade else look.fur
        line(c, s, elbow, 0.1f)
        line(c, elbow, g, 0.09f)
        dot(dark, g, 0.05f)
    }

    fun wheel(cu: Float) {
        val pts = (0 until 24).map { i -> (i / 24f) * 2f * PI.toFloat() }
        val ring = { r: Float -> pts.map { t -> at(cu + r * cos(t), 0f, GgRigMath.WHEEL + r * sin(t)) } }
        pen.shape(tyre.copy(alpha = alpha), ring(GgRigMath.WHEEL), 0.085f * unit)
        val hub = at(cu, 0f, GgRigMath.WHEEL)
        for (k in 0 until 6) {
            val t = -pose.wheel + k * PI.toFloat() / 3f
            val r = GgRigMath.WHEEL * 0.86f
            line(rim.copy(alpha = 0.8f), hub, at(cu + r * cos(t), 0f, GgRigMath.WHEEL + r * sin(t)), 0.018f)
        }
        pen.shape(rim.copy(alpha = alpha), ring(GgRigMath.WHEEL * 0.86f), 0.03f * unit)
        dot(dark, hub, 0.04f)
    }

    // A tail behind the body, swishing with the pedalling.
    fun tail() {
        val base = at(hip[0] - 0.08f, 0f, hip[1] + 0.06f)
        val swish = sin(pose.crank) * 0.05f
        when (look.tail) {
            GgLook.Tail.BRUSH -> {
                val mid = at(hip[0] - 0.24f, swish * 0.5f, hip[1] - 0.02f)
                val tip = at(hip[0] - 0.4f, swish, hip[1] + 0.04f)
                line(look.fur, base, mid, 0.14f)
                line(look.fur, mid, tip, 0.11f)
                dot(look.belly, tip, 0.06f)
            }

            GgLook.Tail.TUFT -> line(look.fur, base, at(hip[0] - 0.2f, swish, hip[1] + 0.12f), 0.08f)
            GgLook.Tail.STUB -> line(look.shade, base, at(hip[0] - 0.14f, swish, hip[1] + 0.02f), 0.1f)
        }
    }

    // Far side first.
    leg(far, pose.crank + PI.toFloat() * (if (far > 0) 1f else 0f), shade = true)
    arm(far, shade = true)
    tail()

    wheel(GgRigMath.REAR)
    wheel(GgRigMath.FRONT)
    // The frame: chainstay, seat stay, seat tube, top tube, down tube; then the fork, stem and bars.
    val rearAxle = at(GgRigMath.REAR, 0f, GgRigMath.WHEEL)
    val frontAxle = at(GgRigMath.FRONT, 0f, GgRigMath.WHEEL)
    val bb = at(GgRigMath.BB[0], 0f, GgRigMath.BB[1])
    val seatTop = at(-0.24f, 0f, 0.74f)
    val headTop = at(0.4f, 0f, 0.78f)
    val headLow = at(0.45f, 0f, 0.64f)
    line(look.frame, rearAxle, bb, 0.06f)
    line(look.frame, rearAxle, seatTop, 0.05f)
    line(look.frame, bb, seatTop, 0.07f)
    line(look.frame, seatTop, headTop, 0.07f)
    line(look.frame, bb, headLow, 0.08f)
    line(look.fork, headLow, frontAxle, 0.06f)
    line(dark, headTop, at(0.38f, 0f, grip[1]), 0.05f)
    line(dark, at(grip[0], -0.22f, grip[1]), at(grip[0], 0.22f, grip[1]), 0.04f)
    line(dark, at(-0.33f, 0f, 0.8f), at(-0.16f, 0f, 0.8f), 0.07f)
    dot(dark, bb, 0.07f)

    // Near side over the bike, then the body and the head on top.
    leg(near, pose.crank + PI.toFloat() * (if (near > 0) 1f else 0f), shade = false)
    val hipPt = at(hip[0], 0f, hip[1])
    val shPt = at(shoulder[0], 0f, shoulder[1])
    val outline = dark.copy(alpha = 0.35f)
    line(outline, hipPt, shPt, 0.36f)
    line(look.fur, hipPt, shPt, 0.32f)
    line(look.belly, at(hip[0] + 0.06f, near * 0.08f, hip[1] + 0.04f), at(shoulder[0] + 0.03f, near * 0.08f, shoulder[1] - 0.04f), 0.14f)
    if (night) {
        // The glowing trail pack.
        val packA = at(hip[0] + 0.02f, far * 0.1f, hip[1] + 0.16f)
        val packB = at(shoulder[0] - 0.08f, far * 0.1f, shoulder[1])
        line(Color(0xFF1C2A33), packA, packB, 0.2f)
        line(Color(0xFF3FFFEF), packA, packB, 0.04f)
    }
    arm(near, shade = false)

    val h = at(head[0], 0f, head[1])
    val snout = at(head[0] + 0.16f, 0f, head[1] - 0.05f)
    when (look.ears) {
        GgLook.Ears.HORNS -> {
            for (s in listOf(-1f, 1f)) {
                val root = at(head[0] - 0.04f, s * 0.07f, head[1] + 0.14f)
                val mid = at(head[0] - 0.12f, s * 0.09f, head[1] + 0.3f)
                val tip = at(head[0] - 0.24f, s * 0.08f, head[1] + 0.3f)
                line(Color(0xFF4A4038), root, mid, 0.06f)
                line(Color(0xFF4A4038), mid, tip, 0.045f)
            }
        }

        GgLook.Ears.POINTED -> {
            for (s in listOf(-1f, 1f)) {
                val a = at(head[0] - 0.1f, s * 0.04f, head[1] + 0.1f)
                val b = at(head[0] - 0.12f, s * 0.15f, head[1] + 0.32f)
                val c = at(head[0] + 0.02f, s * 0.14f, head[1] + 0.12f)
                pen.shape(look.fur.copy(alpha = alpha), listOf(a, b, c))
            }
        }

        GgLook.Ears.ROUND -> {
            for (s in listOf(-1f, 1f)) dot(look.shade, at(head[0] - 0.09f, s * 0.12f, head[1] + 0.13f), 0.06f)
        }
    }
    line(outline, h, snout, 0.21f)
    dot(outline, h, 0.19f)
    line(look.fur, h, snout, 0.17f)
    dot(look.fur, h, 0.17f)
    if (look.stripe) line(look.belly, at(head[0] - 0.12f, 0f, head[1] + 0.12f), snout, 0.06f)
    dot(look.belly, snout, 0.08f)
    dot(dark, at(head[0] + 0.24f, 0f, head[1] - 0.04f), 0.035f)
    for (side in listOf(-1f, 1f)) dot(dark, at(head[0] + 0.09f, side * 0.075f, head[1] + 0.05f), 0.028f)
    // The helmet: a dome over the top of the head with a peak at the front.
    val dome =
        (0..12).map { i ->
            val t = PI.toFloat() * i / 12f
            at(head[0] - 0.02f + 0.19f * cos(t), 0f, head[1] + 0.03f + 0.17f * sin(t))
        }
    pen.shape(look.helmet.copy(alpha = alpha), dome)
    // The same dome across the head, so the helmet reads from the front as well as the side.
    val across =
        (0..12).map { i ->
            val t = PI.toFloat() * i / 12f
            at(head[0] - 0.02f, 0.19f * cos(t), head[1] + 0.03f + 0.17f * sin(t))
        }
    pen.shape(look.helmet.copy(alpha = alpha), across)
    line(look.helmet, at(head[0] + 0.12f, 0f, head[1] + 0.06f), at(head[0] + 0.26f, 0f, head[1] + 0.04f), 0.05f)
    line(dark.copy(alpha = 0.5f), at(head[0] - 0.08f, 0f, head[1] + 0.06f), at(head[0] + 0.02f, 0f, head[1] + 0.16f), 0.025f)
    if (night) dot(Color(0xFFFFF2B0), at(head[0] + 0.15f, 0f, head[1] + 0.12f), 0.035f)
}
