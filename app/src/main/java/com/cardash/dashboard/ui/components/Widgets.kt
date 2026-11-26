package com.cardash.dashboard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardash.dashboard.obd.ConnectionStatus
import com.cardash.dashboard.obd.GearReading
import com.cardash.dashboard.ui.theme.Accent
import com.cardash.dashboard.ui.theme.Danger
import com.cardash.dashboard.ui.theme.Faint
import com.cardash.dashboard.ui.theme.Good
import com.cardash.dashboard.ui.theme.Hairline
import com.cardash.dashboard.ui.theme.Muted
import com.cardash.dashboard.ui.theme.Tile
import com.cardash.dashboard.ui.theme.Warn

@Composable
fun ConnectionPill(status: ConnectionStatus, demo: Boolean) {
    val (dot, label) = when (status) {
        is ConnectionStatus.Connected ->
            (if (demo) Warn else Good) to (if (demo) "Demo mode" else "OBD live · ${status.adapter}")
        is ConnectionStatus.Connecting -> Warn to "Connecting…"
        is ConnectionStatus.Error -> Danger to "Error: ${status.message}"
        is ConnectionStatus.Reconnecting -> Warn to "Reconnecting to ${status.adapter}…"
        ConnectionStatus.Disconnected -> Faint to "Not connected"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(
            label,
            color = Muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
fun GearBadge(gear: GearReading) {
    val text = when (gear) {
        is GearReading.Engaged -> gear.gear.toString()
        GearReading.Neutral -> "N"
        GearReading.Unknown -> "–"
    }
    val shiftUp = (gear as? GearReading.Engaged)?.shiftUp == true
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (shiftUp) Warn else Tile),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                color = if (shiftUp) Color.Black else Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        if (shiftUp) {
            Text(
                "↑ shift up",
                color = Warn,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 12.dp),
            )
        } else {
            Text("gear", color = Faint, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

@Composable
fun RpmBar(rpm: Double, modifier: Modifier = Modifier, max: Double = 7000.0, redline: Double = 6000.0) {
    val frac = (rpm / max).coerceIn(0.0, 1.0).toFloat()
    val color = when {
        rpm >= redline -> Danger
        rpm >= redline * 0.85 -> Warn
        else -> Accent
    }
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Hairline),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(frac)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("${rpm.toInt()} rpm", color = Muted, fontSize = 13.sp)
            Text("${max.toInt()}", color = Faint, fontSize = 13.sp)
        }
    }
}

@Composable
fun MetricTile(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    accent: Color = Color.White,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Tile)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(label, color = Muted, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 6.dp)) {
            Text(value, color = accent, fontSize = 26.sp, fontWeight = FontWeight.Medium)
            if (unit.isNotEmpty()) {
                Text(
                    unit,
                    color = Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = Faint,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}
