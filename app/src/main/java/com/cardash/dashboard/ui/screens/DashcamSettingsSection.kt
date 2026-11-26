package com.cardash.dashboard.ui.screens

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardash.dashboard.dashcam.DashcamEngine
import com.cardash.dashboard.dashcam.DashcamQuality
import com.cardash.dashboard.dashcam.DashcamSettings
import com.cardash.dashboard.ui.theme.Accent
import com.cardash.dashboard.ui.theme.Danger
import com.cardash.dashboard.ui.theme.Faint
import com.cardash.dashboard.ui.theme.Muted
import com.cardash.dashboard.ui.theme.Tile
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

/** Setup ▸ Dashcam: on/off, auto-record, sound, front+back, wide, quality, retention, delete all. */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun DashcamSettingsSection() {
    val s = DashcamEngine.settings
    val enabled by s.enabled.collectAsState()
    val auto by s.autoRecord.collectAsState()
    val audio by s.audio.collectAsState()
    val mic = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    val dual by s.dual.collectAsState()
    val wide by s.wide.collectAsState()
    val quality by s.quality.collectAsState()
    val retention by s.retentionDays.collectAsState()
    val cam by DashcamEngine.state.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    var lastDeleted by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) { DashcamEngine.refreshStatsAsync() }

    Column(Modifier.fillMaxWidth()) {
        ToggleRow("Dashcam", "Camera preview on Drive and the Dashcam tab", enabled) { DashcamEngine.setEnabled(it) }
        ToggleRow(
            "Auto-record when driving",
            "Starts once the car moves (2 mph), stops 10 s after the engine is switched off",
            auto,
        ) { DashcamEngine.setAutoRecord(it) }
        ToggleRow(
            "Record sound",
            when {
                audio && !mic.status.isGranted -> "Allow the microphone to record sound"
                cam.recording && audio != cam.withSound -> "Changes from the next clip"
                else -> "Microphone sound in every clip"
            },
            audio,
        ) { on ->
            DashcamEngine.setAudio(on)
            if (on && !mic.status.isGranted) mic.launchPermissionRequest()
        }
        ToggleRow(
            "Record front + back",
            when {
                !cam.bound -> "Front camera shown as a small picture-in-picture"
                cam.dualSupported -> "Front camera shown as a small picture-in-picture"
                else -> "This phone can't run both cameras at once"
            },
            dual,
        ) { DashcamEngine.setDual(it) }
        ToggleRow(
            "Wide angle",
            if (cam.zoom < 0.99f) "Using the ultra-wide lens (%.1f×)".format(cam.zoom) else "Zoom out to the widest lens available",
            wide,
        ) { DashcamEngine.setWide(it) }

        Label("Video quality")
        Chips(
            options = DashcamQuality.entries,
            selected = quality,
            text = { it.label },
            onPick = { DashcamEngine.setQuality(it) },
        )
        Text(quality.detail + " · clips are split every 5 minutes", color = Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        if (cam.lastClipMs > 30_000) {
            val min = cam.lastClipMs / 60_000.0
            Text(
                "Latest clip: %.1f min, %s (%.1f MB a minute)".format(min, formatBytes(cam.lastClipBytes), cam.lastClipBytes / 1_048_576.0 / min),
                color = Faint,
                fontSize = 12.sp,
            )
        }

        Label("Delete recordings older than")
        Chips(
            options = DashcamSettings.RETENTION_OPTIONS,
            selected = retention,
            text = { d -> if (d == 0) "Never" else if (d == 1) "1 day" else "$d days" },
            onPick = { DashcamEngine.setRetentionDays(it) },
        )

        Label("Storage")
        Text(
            "${cam.clipCount} clip${if (cam.clipCount == 1) "" else "s"} · ${formatBytes(cam.clipBytes)} in Movies/CarDashcam",
            color = Muted,
            fontSize = 13.sp,
        )
        lastDeleted?.let { Text("Deleted $it clip${if (it == 1) "" else "s"}.", color = Faint, fontSize = 12.sp) }
        OutlinedButton(
            onClick = { confirmDelete = true },
            enabled = cam.clipCount > 0,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
            modifier = Modifier.padding(top = 10.dp),
        ) { Text("Delete all recordings") }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all recordings?") },
            text = {
                Text(
                    "This permanently deletes ${cam.clipCount} dashcam clip${if (cam.clipCount == 1) "" else "s"} " +
                        "(${formatBytes(cam.clipBytes)}). A clip that is recording right now is kept. This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    DashcamEngine.deleteAllClips { lastDeleted = it }
                }) { Text("Delete", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 16.sp,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun <T> Chips(options: List<T>, selected: T, text: (T) -> String, onPick: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { opt ->
            val on = opt == selected
            Text(
                text(opt),
                color = if (on) Color.White else Muted,
                fontSize = 13.sp,
                fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) Color(0x333D8BFF) else Tile)
                    .border(1.dp, if (on) Accent else Color.Transparent, RoundedCornerShape(10.dp))
                    .clickable { onPick(opt) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

private fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    b > 0 -> "%.0f KB".format(b / 1024.0)
    else -> "0 MB"
}
