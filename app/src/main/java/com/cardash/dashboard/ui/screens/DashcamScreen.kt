package com.cardash.dashboard.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardash.dashboard.dashcam.DashcamEngine
import com.cardash.dashboard.nav.SpeedLimit
import com.cardash.dashboard.obd.Pid
import com.cardash.dashboard.obd.VehicleState
import com.cardash.dashboard.ui.Fmt
import com.cardash.dashboard.ui.UiPrefs
import com.cardash.dashboard.ui.components.ConnectionPill
import com.cardash.dashboard.ui.components.DashcamPreview
import com.cardash.dashboard.ui.components.GearBadge
import com.cardash.dashboard.ui.components.SpeedLimitSign
import com.cardash.dashboard.ui.components.rememberRecElapsed
import com.cardash.dashboard.ui.theme.Accent
import com.cardash.dashboard.ui.theme.Danger
import com.cardash.dashboard.ui.theme.Muted
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Full-screen dashcam view on the shared [DashcamEngine] session: live preview with an
 * on-screen HUD (the same telemetry is burned into the recording by the engine).
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun DashcamScreen(
    state: VehicleState,
    prefs: UiPrefs,
    speedLimit: SpeedLimit?,
    modifier: Modifier = Modifier,
) {
    val cameraPermission = rememberPermissionState(android.Manifest.permission.CAMERA)
    val enabled by DashcamEngine.settings.enabled.collectAsState()
    Box(modifier.fillMaxSize().background(Color.Black)) {
        when {
            !enabled -> Gate("Dashcam is turned off", "Turn it on in Setup ▸ Dashcam.", null) {}
            cameraPermission.status.isGranted -> {
                // Live view only while this tab is open (battery saver keeps cameras off elsewhere).
                DisposableEffect(Unit) {
                    DashcamEngine.setLive(true)
                    onDispose { DashcamEngine.setLive(false) }
                }
                DashcamPreview(Modifier.fillMaxSize())
                DashcamHud(state, prefs, speedLimit)
            }
            else -> Gate(
                "Dashcam",
                "Allow camera access to use your phone as a dashcam with a live speed and telemetry overlay.",
                "Enable camera",
            ) { cameraPermission.launchPermissionRequest() }
        }
    }
}

@Composable
private fun DashcamHud(state: VehicleState, prefs: UiPrefs, speedLimit: SpeedLimit?) {
    val cam by DashcamEngine.state.collectAsState()
    val quality by DashcamEngine.settings.quality.collectAsState()
    val auto by DashcamEngine.settings.autoRecord.collectAsState()
    val speed = Fmt.speedValue(state.value(Pid.SPEED) ?: 0.0, prefs.imperial)
    val rpm = (state.value(Pid.ENGINE_RPM) ?: 0.0).roundToInt()
    val coolant = state.value(Pid.COOLANT_TEMP)
    val (dateStr, timeStr) = rememberDateTime()
    val elapsed = rememberRecElapsed(cam.recording, cam.startedAt)

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth().height(130.dp).align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent))),
        )
        Box(
            Modifier.fillMaxWidth().height(210.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))),
        )

        // Top-left: clock + optional speed-limit sign.
        Row(Modifier.align(Alignment.TopStart).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(timeStr, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Medium)
                Text(dateStr, color = Color(0xCCFFFFFF), fontSize = 13.sp)
            }
            if (speedLimit != null) {
                Spacer(Modifier.width(14.dp))
                SpeedLimitSign(speedLimit, over = false, imperial = prefs.imperial)
            }
        }

        // Top-centre: what the camera is doing.
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (cam.dualActive) Chip("FRONT + BACK")
            if (cam.zoom < 0.99f) Chip("WIDE %.1f×".format(cam.zoom))
            Chip(quality.label.uppercase())
            if (auto) Chip("AUTO")
        }

        // Top-right: REC badge while recording, else the connection pill.
        Box(Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 18.dp)) {
            if (cam.recording) RecBadge(elapsed) else ConnectionPill(state.status, state.demo)
        }

        // Bottom-left: hero speed + gear + small telemetry.
        Column(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 26.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(speed.toString(), color = Color.White, fontSize = 68.sp, fontWeight = FontWeight.Light)
                Text(
                    Fmt.speedUnit(prefs.imperial),
                    color = Color(0xCCFFFFFF),
                    fontSize = 15.sp,
                    modifier = Modifier.padding(start = 6.dp, bottom = 14.dp),
                )
                Spacer(Modifier.width(16.dp))
                Box(Modifier.padding(bottom = 8.dp)) { GearBadge(state.gear) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                HudStat("RPM", if (rpm > 0) rpm.toString() else "--")
                HudStat(
                    "COOLANT",
                    coolant?.let { Fmt.reading(Pid.COOLANT_TEMP, it, prefs) + Fmt.unit(Pid.COOLANT_TEMP, prefs) } ?: "--",
                )
            }
        }

        // Right edge, vertically centred: record / stop.
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 20.dp)) {
            RecordButton(cam.recording) { DashcamEngine.toggle() }
        }

        cam.error?.let { err ->
            Text(
                "Camera: $err",
                color = Danger,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }
    }
}

@Composable
private fun Chip(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x80000000))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun HudStat(label: String, value: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(5.dp))
        Text(label, color = Color(0x99FFFFFF), fontSize = 10.sp, modifier = Modifier.padding(bottom = 2.dp))
    }
}

@Composable
private fun RecBadge(elapsed: String) {
    val transition = rememberInfiniteTransition(label = "rec")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "blink",
    )
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x99000000))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Danger.copy(alpha = alpha)))
        Spacer(Modifier.width(8.dp))
        Text("REC $elapsed", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RecordButton(isRecording: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(70.dp)
            .clip(CircleShape)
            .border(3.dp, Color.White, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isRecording) {
            Box(Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(Danger))
        } else {
            Box(Modifier.size(54.dp).clip(CircleShape).background(Danger))
        }
    }
}

@Composable
private fun Gate(title: String, body: String, button: String?, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Videocam, contentDescription = null, tint = Muted, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Text(body, color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
        if (button != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text(button) }
        }
    }
}

@Composable
private fun rememberDateTime(): Pair<String, String> {
    var now by remember { mutableStateOf(dateTimeStrings()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = dateTimeStrings()
            delay(10_000)
        }
    }
    return now
}

private fun dateTimeStrings(): Pair<String, String> {
    val d = Date()
    return SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(d) to
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(d)
}
