package com.cardash.dashboard.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardash.dashboard.obd.Sample
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Chart palette (dark mode, validated on the #121215 card surface — see dataviz validator). */
object VizColors {
    val series = listOf(Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70), Color(0xFFC98500))
    val grid = Color(0xFF2C2C2A)
    val axis = Color(0xFF383835)
    val muted = Color(0xFF898781)
    val inkPrimary = Color(0xFFF0EFEC)
    val inkSecondary = Color(0xFFC3C2B7)
    val band = Color(0x1A3987E5)
    // Status (reserved — always paired with an icon/label)
    val good = Color(0xFF0CA30C)
    val warning = Color(0xFFFAB219)
    val serious = Color(0xFFEC835A)
    val critical = Color(0xFFD03B3B)
}

/** One line on a time chart. Values are already in display units. */
data class ChartSeries(val name: String, val points: List<Sample>, val color: Color)

private fun niceStep(range: Double, ticks: Int): Double {
    if (range <= 0) return 1.0
    val raw = range / ticks
    val mag = 10.0.pow(floor(log10(raw)))
    val n = raw / mag
    return mag * when { n <= 1 -> 1.0; n <= 2 -> 2.0; n <= 5 -> 5.0; else -> 10.0 }
}

private fun fmt(v: Double, step: Double): String =
    if (step >= 1) "%.0f".format(v) else if (step >= 0.1) "%.1f".format(v) else "%.2f".format(v)

/**
 * Time-series line chart: last [windowS] seconds, one y-axis, recessive grid, 2dp lines,
 * optional "normal range" band, and a drag / tap crosshair with a tooltip.
 */
@Composable
fun TimeChart(
    series: List<ChartSeries>,
    unit: String,
    windowS: Float,
    nowS: Float,
    modifier: Modifier = Modifier,
    band: ClosedFloatingPointRange<Double>? = null,
    bandLabel: String = "normal",
    zeroLine: Boolean = false,
    /** False: the band only shades the visible range (wide "normal" ranges won't flatten the line). */
    bandExpandsAxis: Boolean = true,
) {
    val measurer = rememberTextMeasurer()
    var touchX by remember { mutableStateOf<Float?>(null) }
    var tapTick by remember { mutableStateOf(0) }
    LaunchedEffect(tapTick) { if (tapTick > 0) { delay(2500); touchX = null } }

    val labelStyle = TextStyle(color = VizColors.muted, fontSize = 10.sp)
    val tipStyle = TextStyle(color = VizColors.inkPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    val tipMuted = TextStyle(color = VizColors.inkSecondary, fontSize = 10.sp)

    Canvas(
        modifier
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { touchX = it.x },
                    onDragEnd = { touchX = null },
                    onDragCancel = { touchX = null },
                    onHorizontalDrag = { c, _ -> touchX = c.position.x },
                )
            }
            .pointerInput(Unit) { detectTapGestures { touchX = it.x; tapTick++ } },
    ) {
        val left = 40.dp.toPx(); val right = 8.dp.toPx()
        // Room above the plot for the unit label, so it never sits on the top axis number.
        val top = (if (unit.isNotBlank()) 22 else 8).dp.toPx(); val bottom = 18.dp.toPx()
        val w = size.width - left - right
        val h = size.height - top - bottom
        val t0 = nowS - windowS

        val all = series.flatMap { it.points }.filter { it.t >= t0 }
        if (all.isEmpty()) {
            drawText(measurer, "Waiting for data…", Offset(left, top + h / 2 - 8.dp.toPx()), labelStyle)
            return@Canvas
        }
        var lo = all.minOf { it.v }.toDouble()
        var hi = all.maxOf { it.v }.toDouble()
        if (bandExpandsAxis) band?.let { lo = min(lo, it.start); hi = max(hi, it.endInclusive) }
        if (zeroLine) { lo = min(lo, 0.0); hi = max(hi, 0.0) }
        if (hi - lo < 1e-6) { hi += 1; lo -= 1 }
        val step = niceStep(hi - lo, 3)
        lo = floor(lo / step) * step
        hi = ceil(hi / step) * step

        fun x(t: Float) = left + (t - t0) / windowS * w
        fun y(v: Double) = top + h - ((v - lo) / (hi - lo) * h).toFloat()

        // Normal-range band
        band?.let {
            val yTop = y(min(it.endInclusive, hi)); val yBot = y(max(it.start, lo))
            if (yBot > yTop) {
                drawRect(VizColors.band, Offset(left, yTop), Size(w, yBot - yTop))
                drawText(measurer, bandLabel, Offset(left + 4.dp.toPx(), yTop + 2.dp.toPx()), labelStyle)
            }
        }
        // Grid + y labels
        var g = lo
        while (g <= hi + step / 2) {
            val gy = y(g)
            drawLine(if (zeroLine && abs(g) < step / 2) VizColors.axis else VizColors.grid, Offset(left, gy), Offset(left + w, gy), 1f)
            val txt = fmt(g, step)
            val m = measurer.measure(txt, labelStyle)
            drawText(m, topLeft = Offset(left - m.size.width - 6.dp.toPx(), gy - m.size.height / 2))
            g += step
        }
        // Baseline + time labels
        drawLine(VizColors.axis, Offset(left, top + h), Offset(left + w, top + h), 1f)
        val wl = if (windowS >= 60) "-${(windowS / 60).toInt()} min" else "-${windowS.toInt()} s"
        drawText(measurer, wl, Offset(left, top + h + 3.dp.toPx()), labelStyle)
        val nowM = measurer.measure("now", labelStyle)
        drawText(nowM, topLeft = Offset(left + w - nowM.size.width, top + h + 3.dp.toPx()))
        drawText(measurer, unit, Offset(2.dp.toPx(), 0f), labelStyle)

        // Lines
        series.forEach { s ->
            val pts = s.points.filter { it.t >= t0 }
            if (pts.size < 2) return@forEach
            val path = Path()
            pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.t), y(p.v.toDouble())) else path.lineTo(x(p.t), y(p.v.toDouble())) }
            drawPath(path, s.color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            val last = pts.last()
            drawCircle(Color(0xFF121215), 5.dp.toPx(), Offset(x(last.t), y(last.v.toDouble())))
            drawCircle(s.color, 3.5.dp.toPx(), Offset(x(last.t), y(last.v.toDouble())))
        }

        // Crosshair + tooltip
        touchX?.let { tx ->
            val cx = tx.coerceIn(left, left + w)
            val t = t0 + (cx - left) / w * windowS
            drawLine(VizColors.inkSecondary.copy(alpha = 0.6f), Offset(cx, top), Offset(cx, top + h), 1.dp.toPx())
            val rows = series.mapNotNull { s ->
                val p = s.points.filter { it.t >= t0 }.minByOrNull { abs(it.t - t) } ?: return@mapNotNull null
                drawCircle(Color(0xFF121215), 6.dp.toPx(), Offset(x(p.t), y(p.v.toDouble())))
                drawCircle(s.color, 4.dp.toPx(), Offset(x(p.t), y(p.v.toDouble())))
                Triple(s, p, fmt(p.v.toDouble(), step / 10))
            }
            if (rows.isNotEmpty()) {
                val ago = (nowS - t).toInt().coerceAtLeast(0)
                val head = measurer.measure(if (ago == 0) "now" else "${ago / 60}:%02d ago".format(ago % 60), tipMuted)
                val lines = rows.map { (s, _, v) -> s to measurer.measure("${s.name}  $v $unit", tipStyle) }
                val bw = max(head.size.width, lines.maxOf { it.second.size.width + 14.dp.toPx().toInt() }) + 16.dp.toPx()
                val bh = head.size.height + lines.sumOf { it.second.size.height } + 12.dp.toPx()
                val bx = if (cx + 10.dp.toPx() + bw < size.width) cx + 10.dp.toPx() else cx - 10.dp.toPx() - bw
                drawRoundRect(Color(0xF21B1B20), Offset(bx, top), Size(bw, bh), CornerRadius(8.dp.toPx()))
                var yy = top + 6.dp.toPx()
                drawText(head, topLeft = Offset(bx + 8.dp.toPx(), yy)); yy += head.size.height
                lines.forEach { (s, m) ->
                    drawCircle(s.color, 3.5.dp.toPx(), Offset(bx + 11.dp.toPx(), yy + m.size.height / 2))
                    drawText(m, topLeft = Offset(bx + 20.dp.toPx(), yy)); yy += m.size.height
                }
            }
        }
    }
}

/** Legend row: colour dot + name + current value (identity is never colour alone). */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun ChartLegend(items: List<Triple<Color, String, String>>, modifier: Modifier = Modifier) {
    // Each entry stays on one line; entries wrap to the next line when the card is narrow.
    androidx.compose.foundation.layout.FlowRow(
        modifier,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
    ) {
        items.forEach { (c, name, value) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                Spacer(Modifier.width(5.dp))
                Text(name, color = VizColors.inkSecondary, fontSize = 11.sp, maxLines = 1, softWrap = false)
                Spacer(Modifier.width(4.dp))
                Text(value, color = VizColors.inkPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** Tiny trend line for table rows. */
@Composable
fun Sparkline(points: List<Sample>, modifier: Modifier = Modifier, color: Color = VizColors.series[0]) {
    Canvas(modifier) {
        if (points.size < 2) {
            drawLine(VizColors.grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
            return@Canvas
        }
        val t0 = points.first().t; val t1 = points.last().t
        val lo = points.minOf { it.v }; val hi = points.maxOf { it.v }
        val span = if (hi - lo < 1e-6f) 1f else hi - lo
        val path = Path()
        points.forEachIndexed { i, p ->
            val px = (p.t - t0) / (t1 - t0).coerceAtLeast(0.001f) * size.width
            val py = size.height - (p.v - lo) / span * (size.height - 2f) - 1f
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, color, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Zone of a gauge drawn in a status colour (always paired with a text label by the caller). */
data class GaugeZone(val from: Double, val to: Double, val color: Color)

/** 240° arc gauge with optional warning / critical zones on the track. */
@Composable
fun ArcGauge(
    value: Double?,
    min: Double,
    max: Double,
    modifier: Modifier = Modifier,
    zones: List<GaugeZone> = emptyList(),
    fill: Color = VizColors.series[0],
) {
    Canvas(modifier) {
        val stroke = 7.dp.toPx()
        val d = min(size.width, size.height * 1.25f) - stroke
        val tl = Offset((size.width - d) / 2, stroke / 2 + 2.dp.toPx())
        val arcSize = Size(d, d)
        val start = 150f; val sweep = 240f
        drawArc(VizColors.grid, start, sweep, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        zones.forEach { z ->
            val a = ((z.from - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
            val b = ((z.to - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
            if (b > a) drawArc(z.color.copy(alpha = 0.35f), start + sweep * a, sweep * (b - a), false, tl, arcSize, style = Stroke(stroke))
        }
        if (value != null) {
            val f = ((value - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
            val c = zones.firstOrNull { value >= it.from && value <= it.to }?.color ?: fill
            if (f > 0.002f) drawArc(c, start, sweep * f, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

/** Score ring 0..100 (status colour by band; the caller shows the label). */
@Composable
fun ScoreRing(score: Int, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 9.dp.toPx()
        val d = min(size.width, size.height) - stroke
        val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
        drawArc(VizColors.grid, -90f, 360f, false, tl, Size(d, d), style = Stroke(stroke))
        drawArc(color, -90f, 360f * score / 100f, false, tl, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
    }
}
