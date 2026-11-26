package com.cardash.dashboard.ui.components

import android.Manifest
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardash.dashboard.ai.VoiceAssistant
import com.cardash.dashboard.ai.VoiceAssistant.Phase
import com.cardash.dashboard.ui.theme.Accent
import com.cardash.dashboard.ui.theme.Danger
import com.cardash.dashboard.ui.theme.Muted
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

/** Round mic button: tap to talk to Dash (asks for the microphone the first time). */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun AssistantMicButton(modifier: Modifier = Modifier, compact: Boolean = false) {
    val outer = if (compact) 54.dp else 76.dp
    val inner = if (compact) 40.dp else 52.dp
    val st by VoiceAssistant.state.collectAsState()
    val muted = !VoiceAssistant.settings.wakeWord.collectAsState().value
    val mic = rememberPermissionState(Manifest.permission.RECORD_AUDIO) { granted -> if (granted) VoiceAssistant.listen() }
    val active = st.phase == Phase.LISTENING || st.phase == Phase.THINKING || st.phase == Phase.SPEAKING
    // Ring that grows with your voice; a gentle breathing pulse while waiting for you to speak.
    val level by animateFloatAsState(st.level, tween(90), label = "level")
    val pulse = rememberInfiniteTransition(label = "mic")
    val breathe by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "b")
    val listening = st.phase == Phase.LISTENING
    val ring = when {
        listening -> 1f + 0.12f * breathe + 0.55f * level
        st.wakeListening && !active -> 1f + 0.35f * level
        else -> 1f
    }

    Box(modifier.size(outer), contentAlignment = Alignment.Center) {
    if (ring > 1.01f) {
        Box(
            Modifier
                .size(inner)
                .scale(ring)
                .clip(CircleShape)
                .background(if (listening) Color(0x553D8BFF) else Color(0x402FD27A)),
        )
    }
    Box(
        Modifier
            .size(inner)
            .clip(CircleShape)
            .background(if (active) Accent else Color(0xE6141518))
            .border(1.dp, if (active) Accent else Color(0x33FFFFFF), CircleShape)
            .clickable {
                when {
                    active -> VoiceAssistant.cancel()
                    !mic.status.isGranted -> mic.launchPermissionRequest()
                    else -> {
                        if (muted) VoiceAssistant.setMuted(false) // tapping the mic un-mutes
                        VoiceAssistant.listen()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            when { active -> Icons.Filled.Close; muted -> Icons.Filled.MicOff; else -> Icons.Filled.Mic },
            if (muted) "Mic muted, tap to talk" else "Voice assistant",
            tint = if (muted && !active) Color(0xFF8A8F99) else Color.White,
            modifier = Modifier.size(if (compact) 20.dp else 24.dp),
        )
        if (st.wakeListening && !active) {
            Box(Modifier.align(Alignment.TopEnd).padding(if (compact) 3.dp else 6.dp).size(8.dp).clip(CircleShape).background(Color(0xFF2FD27A)))
        }
    }
    }
}

/** Mute switch for the mic: red when muted (Dash isn't listening at all). */
@Composable
fun MicMuteButton(modifier: Modifier = Modifier, sizeDp: Int = 36) {
    val muted = !VoiceAssistant.settings.wakeWord.collectAsState().value
    Box(
        modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(if (muted) Color(0x33E24B4A) else Color(0xE6141518))
            .border(1.dp, if (muted) Color(0xFFE24B4A) else Color(0x33FFFFFF), CircleShape)
            .clickable { VoiceAssistant.setMuted(!muted) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
            contentDescription = if (muted) "Unmute mic" else "Mute mic",
            tint = if (muted) Color(0xFFE24B4A) else Color(0xFFB0B4BC),
            modifier = Modifier.size((sizeDp * 0.5f).dp),
        )
    }
}

/** Five bars that bounce with the live mic level. */
@Composable
private fun VoiceBars(level: Float) {
    val l by animateFloatAsState(level, tween(80), label = "bars")
    val t = rememberInfiniteTransition(label = "wave")
    val phase by t.animateFloat(0f, 6.283f, infiniteRepeatable(tween(700)), label = "p")
    Row(Modifier.size(34.dp), horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        repeat(5) { i ->
            val wobble = (kotlin.math.sin(phase + i * 1.1f) + 1f) / 2f
            val h = 6f + 22f * (0.15f + 0.85f * l) * (0.45f + 0.55f * wobble)
            Box(Modifier.width(3.dp).height(h.dp).clip(RoundedCornerShape(2.dp)).background(Accent))
        }
    }
}

/** Floating card showing what Dash heard, what it's doing, and its reply. */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun AssistantPanel(modifier: Modifier = Modifier) {
    val st by VoiceAssistant.state.collectAsState()
    val mic = rememberPermissionState(Manifest.permission.RECORD_AUDIO) { granted -> if (granted) VoiceAssistant.listen() }
    LaunchedEffect(st.needsMicPermission) {
        if (st.needsMicPermission && !mic.status.isGranted) mic.launchPermissionRequest()
    }
    AnimatedVisibility(st.phase != Phase.IDLE, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Row(
            Modifier
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xF2141518))
                .border(0.5.dp, if (st.phase == Phase.ERROR) Danger else Color(0x33FFFFFF), RoundedCornerShape(22.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0x333D8BFF)), contentAlignment = Alignment.Center) {
                when (st.phase) {
                    Phase.THINKING -> CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    Phase.LISTENING -> VoiceBars(st.level)
                    else -> Icon(Icons.Filled.AutoAwesome, null, tint = Accent, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f, fill = false)) {
                val top = when (st.phase) {
                    Phase.LISTENING -> if (st.heard.isBlank()) "Listening…" else st.heard
                    else -> st.heard
                }
                if (top.isNotBlank()) {
                    Text(
                        "“$top”".takeIf { st.phase != Phase.LISTENING || st.heard.isNotBlank() } ?: top,
                        color = if (st.phase == Phase.LISTENING) Color.White else Muted,
                        fontSize = if (st.phase == Phase.LISTENING) 16.sp else 13.sp,
                        maxLines = 2,
                    )
                }
                when (st.phase) {
                    Phase.THINKING -> Text(st.detail.ifBlank { "Thinking…" }, color = Color.White, fontSize = 15.sp)
                    Phase.SPEAKING, Phase.ERROR -> Text(
                        st.reply,
                        color = if (st.phase == Phase.ERROR) Danger else Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 4,
                    )
                    else -> Unit
                }
            }
            Spacer(Modifier.width(10.dp))
            Icon(
                Icons.Filled.Close,
                "Close",
                tint = Muted,
                modifier = Modifier.size(18.dp).clickable { VoiceAssistant.cancel() },
            )
        }
    }
}
