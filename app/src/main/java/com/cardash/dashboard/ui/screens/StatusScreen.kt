package com.cardash.dashboard.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardash.dashboard.insights.Health
import com.cardash.dashboard.insights.Insights
import com.cardash.dashboard.insights.SystemCheck
import com.cardash.dashboard.obd.VehicleState
import com.cardash.dashboard.ui.Fmt
import com.cardash.dashboard.ui.UiPrefs
import com.cardash.dashboard.ui.theme.Accent
import com.cardash.dashboard.ui.theme.Danger
import com.cardash.dashboard.ui.theme.Faint
import com.cardash.dashboard.ui.theme.Good
import com.cardash.dashboard.ui.theme.Hairline
import com.cardash.dashboard.ui.theme.Muted
import com.cardash.dashboard.ui.theme.Tile
import com.cardash.dashboard.ui.theme.Warn
import kotlin.math.roundToInt

private fun healthColor(h: Health) = when (h) {
    Health.GOOD -> Good
    Health.WATCH -> Warn
    Health.ISSUE -> Danger
}

@Composable
fun StatusScreen(
    state: VehicleState,
    prefs: UiPrefs,
    mpgHistory: List<Float>,
    onScan: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val report = Insights.health(state)
    var confirmClear by remember { mutableStateOf(false) }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Status", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)

        // Health hero
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp)
                .clip(RoundedCornerShape(16.dp)).background(Tile).padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HealthRing(report.score, healthColor(report.worst), Modifier.size(96.dp))
            Column(Modifier.padding(start = 20.dp)) {
                Text("Car health", color = Muted, fontSize = 13.sp)
                Text(report.label, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                Text("Live from OBD", color = Faint, fontSize = 13.sp)
            }
        }

        // System checks
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            report.checks.forEach { CheckRow(it) }
        }

        // Efficiency
        Text("EFFICIENCY", color = Faint, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 24.dp, bottom = 10.dp))
        val avgL100 = if (state.trip.distanceKm > 0.1) state.trip.fuelUsedL / state.trip.distanceKm * 100.0 else 0.0
        val rangeKm = Insights.projectedRangeKm(state)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MiniStat("Now", "${Fmt.economy(state.trip.instantL100, prefs.imperial)} ${Fmt.economyUnit(prefs.imperial)}", Modifier.weight(1f))
            MiniStat("Trip avg", "${Fmt.economy(avgL100, prefs.imperial)} ${Fmt.economyUnit(prefs.imperial)}", Modifier.weight(1f))
            MiniStat(
                "Range",
                if (rangeKm != null) "%.0f %s".format(Fmt.distValue(rangeKm, prefs.imperial), Fmt.distUnit(prefs.imperial)) else "--",
                Modifier.weight(1f),
            )
        }
        if (mpgHistory.size > 2) {
            Box(Modifier.fillMaxWidth().height(90.dp).padding(top = 12.dp)
                .clip(RoundedCornerShape(14.dp)).background(Tile).padding(12.dp)) {
                Sparkline(mpgHistory, Modifier.fillMaxWidth().height(66.dp))
            }
        }

        // Diagnostics
        Text("DIAGNOSTICS", color = Faint, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 24.dp, bottom = 10.dp))
        val milOn = state.milOn || state.faults.isNotEmpty()
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(if (milOn) Color(0xFF2A1518) else Color(0xFF10261A)).padding(16.dp),
        ) {
            Text(
                if (milOn) "Check engine light on" else "No fault codes",
                color = if (milOn) Danger else Good, fontSize = 16.sp, fontWeight = FontWeight.Medium,
            )
            Text(
                if (milOn) "${state.faults.size} code(s) stored" else "All systems nominal",
                color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp),
            )
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onScan, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Scan now") }
            OutlinedButton(onClick = { confirmClear = true }, enabled = state.faults.isNotEmpty()) { Text("Clear codes") }
        }
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.faults.forEach { code ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tile).padding(14.dp)) {
                    Text(code.code, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(code.description, color = Muted, fontSize = 13.sp)
                }
            }
        }
        Spacer16()
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear fault codes?") },
            text = { Text("This erases stored codes and turns off the check-engine light. If a fault is still present it will return.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text("Clear", color = Danger) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Spacer16() = Box(Modifier.height(16.dp))

@Composable
private fun CheckRow(check: SystemCheck) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tile).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(healthColor(check.status)))
        Text(check.name, color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(start = 12.dp).weight(1f))
        Text(check.detail, color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Tile).padding(14.dp)) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun HealthRing(score: Int, color: Color, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val stroke = size.minDimension * 0.11f
            val inset = stroke / 2f
            val arcSize = androidx.compose.ui.geometry.Size(size.minDimension - stroke, size.minDimension - stroke)
            val topLeft = Offset((size.width - arcSize.width) / 2f, (size.height - arcSize.height) / 2f)
            drawArc(Hairline, -90f, 360f, false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(color, -90f, 360f * (score / 100f), false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text("$score", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Sparkline(values: List<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val maxV = (values.maxOrNull() ?: 1f).coerceAtLeast(1f)
        val minV = values.minOrNull() ?: 0f
        val range = (maxV - minV).coerceAtLeast(1f)
        val stepX = size.width / (values.size - 1)
        var prev = Offset(0f, size.height - (values[0] - minV) / range * size.height)
        for (i in 1 until values.size) {
            val x = stepX * i
            val y = size.height - (values[i] - minV) / range * size.height
            val cur = Offset(x, y)
            drawLine(Accent, prev, cur, strokeWidth = 3f, cap = StrokeCap.Round)
            prev = cur
        }
    }
}
