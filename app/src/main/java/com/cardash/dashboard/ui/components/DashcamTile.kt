package com.cardash.dashboard.ui.components

import android.Manifest
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import com.cardash.dashboard.ui.theme.Tile
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.cardash.dashboard.dashcam.DashcamEngine
import com.cardash.dashboard.ui.theme.Danger
import com.cardash.dashboard.ui.theme.Muted
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.delay

/** Live dashcam preview attached to the shared [DashcamEngine] session while on screen. */
@Composable
fun DashcamPreview(modifier: Modifier = Modifier) {
    var view by remember { mutableStateOf<PreviewView?>(null) }
    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }.also { view = it }
        },
        modifier = modifier,
    )
    DisposableEffect(view) {
        val sp = view?.surfaceProvider
        if (sp != null) DashcamEngine.showPreviewOn(sp)
        onDispose { if (sp != null) DashcamEngine.hidePreviewFrom(sp) }
    }
    // Keep a recent frame so the tile can show it while the cameras are off.
    LaunchedEffect(view) {
        val v = view ?: return@LaunchedEffect
        while (true) {
            delay(2500)
            v.bitmap?.let { DashcamEngine.lastFrame.value = it.asImageBitmap() }
        }
    }
}

/** Ticks once a second while recording; returns mm:ss since [startedAt] (elapsedRealtime). */
@Composable
fun rememberRecElapsed(recording: Boolean, startedAt: Long): String {
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(recording, startedAt) {
        while (recording) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    if (!recording) return "00:00"
    val s = ((now - startedAt) / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}

/** Small dashcam tile for the Drive screen: live preview, tap to start/stop recording. */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun DashcamTile(modifier: Modifier = Modifier) {
    val enabled by DashcamEngine.settings.enabled.collectAsState()
    if (!enabled) return
    val st by DashcamEngine.state.collectAsState()
    val perm = rememberPermissionState(Manifest.permission.CAMERA)
    val elapsed = rememberRecElapsed(st.recording, st.startedAt)
    val shape = RoundedCornerShape(14.dp)

    // Slim one-line bar while idle; grows to a full 16:9 live view while recording.
    val expanded = st.recording
    Box(
        modifier
            .fillMaxWidth()
            .animateContentSize(tween(320))
            .then(if (expanded) Modifier.aspectRatio(16f / 9f) else Modifier.height(46.dp))
            .clip(shape)
            .background(if (expanded) Color.Black else Tile)
            .border(if (expanded) 2.dp else 1.dp, if (expanded) Danger else Color(0x33FFFFFF), shape)
            .clickable {
                if (!perm.status.isGranted) perm.launchPermissionRequest() else DashcamEngine.toggle()
            },
    ) {
        if (!expanded) {
            Row(
                Modifier.fillMaxSize().padding(start = 14.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Videocam, null, tint = Muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Dashcam", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(
                        if (perm.status.isGranted) "Tap to record" else "Tap to enable",
                        color = Muted,
                        fontSize = 11.sp,
                    )
                }
                Box(
                    Modifier.size(28.dp).clip(CircleShape).border(2.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(18.dp).clip(CircleShape).background(Danger))
                }
            }
            return@Box
        }
        if (perm.status.isGranted && st.bound) {
            DashcamPreview(Modifier.fillMaxSize())
        } else if (perm.status.isGranted) {
            // Battery saver: cameras are off until you record.
            val frame by DashcamEngine.lastFrame.collectAsState()
            frame?.let {
                Image(it, null, contentScale = ContentScale.Crop, alpha = 0.45f, modifier = Modifier.fillMaxSize())
            }
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Tap to record", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text("Camera off to save battery", color = Color(0xB3FFFFFF), fontSize = 10.sp)
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.Videocam, null, tint = Muted, modifier = Modifier.size(22.dp))
                Text("Tap to enable dashcam", color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center)
            }
        }

        // Status chip (top-left).
        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x99000000))
                .padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (st.recording) Danger else Color(0xFF8A8A92)))
            Spacer(Modifier.width(5.dp))
            Text(
                if (st.recording) "REC $elapsed" else "DASHCAM",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        // Record / stop glyph (top-right; bottom-right is where the front-camera PiP sits).
        if (perm.status.isGranted) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (st.recording) {
                    Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(Danger))
                } else {
                    Box(Modifier.size(16.dp).clip(CircleShape).background(Danger))
                }
            }
        }
    }
}
