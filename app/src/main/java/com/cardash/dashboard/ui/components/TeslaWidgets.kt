package com.cardash.dashboard.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardash.dashboard.insights.Health
import com.cardash.dashboard.nav.SpeedLimit
import com.cardash.dashboard.ui.theme.Accent
import com.cardash.dashboard.ui.theme.Danger
import com.cardash.dashboard.ui.theme.Faint
import com.cardash.dashboard.ui.theme.Good
import com.cardash.dashboard.ui.theme.Hairline
import com.cardash.dashboard.ui.theme.Muted
import com.cardash.dashboard.ui.theme.Warn
import kotlin.math.roundToInt

/**
 * Tesla-style driving scene: a 3D rear view of the car sitting on a dark road,
 * with glowing blue "Autopilot" lane lines converging to the horizon. The car is
 * tinted by the current health colour and its ground glow reflects it too.
 */
@Composable
fun TeslaCarScene(
    health: Health,
    speedKmh: Double,
    modifier: Modifier = Modifier,
) {
    val healthColor = when (health) {
        Health.GOOD -> Good
        Health.WATCH -> Warn
        Health.ISSUE -> Danger
    }
    // Lane dashes crawl toward the car; faster when moving faster (purely cosmetic).
    val transition = rememberInfiniteTransition(label = "road")
    val period = (1600 - (speedKmh.coerceIn(0.0, 160.0) * 7)).toInt().coerceAtLeast(260)
    val dash by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(period, easing = LinearEasing), RepeatMode.Restart),
        label = "dash",
    )

    Canvas(modifier) {
        val w = size.width
        val h = size.height

        // Scene backdrop — subtle horizon glow fading to near-black.
        drawRect(
            Brush.verticalGradient(
                0f to Color(0xFF12161D),
                0.55f to Color(0xFF0B0C10),
                1f to Color(0xFF070708),
            ),
        )

        val vpX = w * 0.5f
        val vpY = h * 0.20f
        val roadBottom = h * 1.02f
        val nearHalf = w * 0.60f
        val farHalf = w * 0.05f

        // Road surface trapezoid.
        drawPath(
            Path().apply {
                moveTo(vpX - farHalf, vpY)
                lineTo(vpX + farHalf, vpY)
                lineTo(vpX + nearHalf, roadBottom)
                lineTo(vpX - nearHalf, roadBottom)
                close()
            },
            Brush.verticalGradient(
                0f to Color(0xFF0E1116),
                1f to Color(0xFF16191F),
                startY = vpY,
                endY = roadBottom,
            ),
        )

        // Perspective ground stripes (crawling with speed).
        val rungs = 7
        for (i in 0 until rungs) {
            val base = (i + dash) / rungs            // 0..1 bottom->far
            val t = base * base                      // ease toward horizon
            val y = roadBottom + (vpY - roadBottom) * t
            val half = nearHalf + (farHalf - nearHalf) * t
            drawLine(
                Color.White.copy(alpha = 0.05f * (1f - t)),
                Offset(vpX - half, y),
                Offset(vpX + half, y),
                strokeWidth = (h * 0.006f) * (1f - t) + 1f,
            )
        }

        // Tesla "Autopilot" view: a translucent driving-path ribbon that fades to
        // the horizon, bounded by soft lane lines that dim with distance.
        val cutT = 0.80f
        fun laneX(side: Int, t: Float) = vpX + side * nearHalf * 0.66f * (1f - t)
        fun laneY(t: Float) = roadBottom + (vpY - roadBottom) * t

        drawPath(
            Path().apply {
                moveTo(laneX(-1, 0f), laneY(0f))
                lineTo(laneX(-1, cutT), laneY(cutT))
                lineTo(laneX(1, cutT), laneY(cutT))
                lineTo(laneX(1, 0f), laneY(0f))
                close()
            },
            Brush.verticalGradient(
                0f to Accent.copy(alpha = 0.22f),
                1f to Accent.copy(alpha = 0f),
                startY = laneY(0f),
                endY = laneY(cutT),
            ),
        )

        val laneSegs = 24
        for (side in intArrayOf(-1, 1)) {
            for (i in 0 until laneSegs) {
                val t0 = cutT * i / laneSegs
                val t1 = cutT * (i + 1) / laneSegs
                drawLine(
                    Color(0xFF8FB8FF).copy(alpha = (1f - t0 / cutT) * 0.55f),
                    Offset(laneX(side, t0), laneY(t0)),
                    Offset(laneX(side, t1), laneY(t1)),
                    strokeWidth = (w * 0.011f) * (1f - t0) + 1f,
                    cap = StrokeCap.Round,
                )
            }
        }

        // Centre dashed guide, crawling toward the viewer.
        val segs = 6
        for (i in 0 until segs) {
            val b0 = ((i + dash) / segs).coerceIn(0f, 1f)
            val b1 = (b0 + 0.45f / segs).coerceAtMost(1f)
            val t0 = b0 * b0
            val t1 = b1 * b1
            if (t0 >= cutT) continue
            drawLine(
                Color(0xFF4E9BFF).copy(alpha = 0.5f * (1f - t0)),
                Offset(vpX, laneY(t0)),
                Offset(vpX, laneY(t1)),
                strokeWidth = (w * 0.010f) * (1f - t0) + 1f,
                cap = StrokeCap.Round,
            )
        }

        // A faint "car ahead" marker up the lane.
        val aheadY = vpY + (roadBottom - vpY) * 0.30f
        val aheadHalf = farHalf + (nearHalf - farHalf) * 0.30f
        drawRoundRect(
            Color.White.copy(alpha = 0.10f),
            topLeft = Offset(vpX - aheadHalf * 0.22f, aheadY - h * 0.02f),
            size = Size(aheadHalf * 0.44f, h * 0.045f),
            cornerRadius = CornerRadius(6f, 6f),
        )

        // Ego car — rear view, near the bottom centre.
        drawCarRear(
            cx = vpX,
            groundY = h * 0.90f,
            carW = w * 0.48f,
            carH = h * 0.46f,
            health = healthColor,
        )
    }
}

/** Draws a stylised rear view of a Model-3-like car centred on [cx], tyres on [groundY]. */
private fun DrawScope.drawCarRear(cx: Float, groundY: Float, carW: Float, carH: Float, health: Color) {
    val w = carW
    val h = carH

    // Vertical anchors.
    val roofY = groundY - h * 0.90f
    val beltY = groundY - h * 0.52f   // glass meets body / tail-light line
    val haunchY = groundY - h * 0.30f // widest point
    val bumperY = groundY - h * 0.12f
    val bottomY = groundY - h * 0.02f

    // Half-widths.
    val roofHalf = w * 0.25f
    val shoulderHalf = w * 0.40f
    val haunchHalf = w * 0.50f
    val bumperHalf = w * 0.45f

    // Ground glow (health colour) + contact shadow.
    drawOval(
        health.copy(alpha = 0.20f),
        topLeft = Offset(cx - w * 0.60f, groundY - h * 0.12f),
        size = Size(w * 1.20f, h * 0.34f),
    )
    drawOval(
        Color.Black.copy(alpha = 0.50f),
        topLeft = Offset(cx - w * 0.50f, groundY - h * 0.07f),
        size = Size(w * 1.00f, h * 0.18f),
    )

    // Rear wheels peeking below the haunches.
    val wheelW = w * 0.15f
    val wheelH = h * 0.16f
    listOf(cx - haunchHalf * 0.84f - wheelW / 2f, cx + haunchHalf * 0.84f - wheelW / 2f).forEach { wx ->
        drawRoundRect(
            Color(0xFF070709),
            topLeft = Offset(wx, groundY - wheelH),
            size = Size(wheelW, wheelH),
            cornerRadius = CornerRadius(wheelW * 0.3f, wheelW * 0.3f),
        )
    }

    // Greenhouse (roof + pillars), behind the body.
    val greenhouse = Path().apply {
        moveTo(cx - shoulderHalf * 0.94f, beltY)
        quadraticBezierTo(cx - roofHalf * 1.28f, roofY + h * 0.14f, cx - roofHalf, roofY)
        quadraticBezierTo(cx - roofHalf, roofY - h * 0.01f, cx - roofHalf + w * 0.03f, roofY - h * 0.006f)
        lineTo(cx + roofHalf - w * 0.03f, roofY - h * 0.006f)
        quadraticBezierTo(cx + roofHalf, roofY - h * 0.01f, cx + roofHalf, roofY)
        quadraticBezierTo(cx + roofHalf * 1.28f, roofY + h * 0.14f, cx + shoulderHalf * 0.94f, beltY)
        close()
    }
    drawPath(
        greenhouse,
        Brush.verticalGradient(0f to Color(0xFFD9DDE3), 1f to Color(0xFFB5BAC3), startY = roofY, endY = beltY),
    )

    // Rear glass inset.
    val glass = Path().apply {
        moveTo(cx - roofHalf * 0.84f, roofY + h * 0.09f)
        lineTo(cx + roofHalf * 0.84f, roofY + h * 0.09f)
        lineTo(cx + shoulderHalf * 0.78f, beltY - h * 0.05f)
        lineTo(cx - shoulderHalf * 0.78f, beltY - h * 0.05f)
        close()
    }
    drawPath(
        glass,
        Brush.verticalGradient(0f to Color(0xFF2B3139), 1f to Color(0xFF141A20), startY = roofY, endY = beltY),
    )

    // Main body (lower) with wide haunches.
    val body = Path().apply {
        moveTo(cx - shoulderHalf, beltY - h * 0.02f)
        quadraticBezierTo(cx - haunchHalf, haunchY, cx - bumperHalf, bumperY)
        quadraticBezierTo(cx - bumperHalf, bottomY, cx - bumperHalf + w * 0.07f, bottomY)
        lineTo(cx + bumperHalf - w * 0.07f, bottomY)
        quadraticBezierTo(cx + bumperHalf, bottomY, cx + bumperHalf, bumperY)
        quadraticBezierTo(cx + haunchHalf, haunchY, cx + shoulderHalf, beltY - h * 0.02f)
        quadraticBezierTo(cx, beltY - h * 0.05f, cx - shoulderHalf, beltY - h * 0.02f)
        close()
    }
    drawPath(
        body,
        Brush.verticalGradient(
            0f to Color(0xFFEDF0F3),
            0.5f to Color(0xFFCDD2D9),
            1f to Color(0xFFABB1BA),
            startY = beltY - h * 0.05f,
            endY = bottomY,
        ),
    )
    drawPath(body, health.copy(alpha = 0.07f))

    // Continuous red tail-light bar + soft glow.
    val lightY = beltY + h * 0.055f
    val lightHalf = shoulderHalf * 0.92f
    val lightH = h * 0.05f
    drawRoundRect(
        Color(0x40FF3030),
        topLeft = Offset(cx - lightHalf, lightY - lightH),
        size = Size(lightHalf * 2f, lightH * 3f),
        cornerRadius = CornerRadius(lightH, lightH),
    )
    drawRoundRect(
        Brush.horizontalGradient(
            0f to Color(0xFFFF5252),
            0.5f to Color(0xFF7E1010),
            1f to Color(0xFFFF5252),
        ),
        topLeft = Offset(cx - lightHalf, lightY - lightH / 2f),
        size = Size(lightHalf * 2f, lightH),
        cornerRadius = CornerRadius(lightH / 2f, lightH / 2f),
    )

    // Number plate on the bumper.
    val plateW = w * 0.24f
    val plateH = h * 0.08f
    drawRoundRect(
        Color(0xFFE9EBEE),
        topLeft = Offset(cx - plateW / 2f, bumperY - plateH / 2f),
        size = Size(plateW, plateH),
        cornerRadius = CornerRadius(plateH * 0.25f, plateH * 0.25f),
    )

    // Crisp outline.
    drawPath(body, Color.Black.copy(alpha = 0.18f), style = Stroke(width = w * 0.005f))
}

/** Tesla-style horizontal power/load bar. */
@Composable
fun PowerMeter(loadPct: Double, modifier: Modifier = Modifier) {
    val frac = (loadPct / 100.0).coerceIn(0.0, 1.0).toFloat()
    val color = when {
        loadPct > 85 -> Danger
        loadPct > 60 -> Warn
        else -> Accent
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("POWER", color = Faint, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text("${loadPct.roundToInt()}%", color = Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Hairline)) {
            Box(Modifier.fillMaxWidth(frac).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        }
    }
}

/** UK-style circular speed-limit sign; pulses red when you are over the limit. */
@Composable
fun SpeedLimitSign(limit: SpeedLimit, over: Boolean, imperial: Boolean, modifier: Modifier = Modifier) {
    val number = if (imperial) (limit.kmh * 0.621371).roundToInt() else limit.kmh
    val transition = rememberInfiniteTransition(label = "sign")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (over) 1.12f else 1f,
        animationSpec = infiniteRepeatable(tween(550), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(modifier.size(60.dp).scale(pulse), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            drawCircle(if (over) Danger else Color(0xFFCC1111), radius = radius)
            drawCircle(Color.White, radius = radius * 0.72f)
        }
        Text(
            number.toString(),
            color = Color(0xFF111111),
            fontSize = if (number >= 100) 18.sp else 22.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
