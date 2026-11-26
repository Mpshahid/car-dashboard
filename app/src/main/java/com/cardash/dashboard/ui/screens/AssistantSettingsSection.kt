package com.cardash.dashboard.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardash.dashboard.ai.AiProvider
import com.cardash.dashboard.ai.VoskWake.Status as VoskStatus
import com.cardash.dashboard.ai.VoiceAssistant
import com.cardash.dashboard.ai.providerFor
import com.cardash.dashboard.ui.theme.Accent
import com.cardash.dashboard.ui.theme.Danger
import com.cardash.dashboard.ui.theme.Faint
import com.cardash.dashboard.ui.theme.Good
import com.cardash.dashboard.ui.theme.Muted
import com.cardash.dashboard.ui.theme.Tile
import kotlinx.coroutines.launch

/** Setup ▸ Voice assistant: AI provider, key, model, wake word. */
@Composable
fun AssistantSettingsSection() {
    val s = VoiceAssistant.settings
    val provider by s.provider.collectAsState()
    val wake by s.wakeWord.collectAsState()
    val speak by s.speak.collectAsState()
    @Suppress("UNUSED_VARIABLE") val rev by s.rev.collectAsState() // recompose after saves
    val scope = rememberCoroutineScope()

    var keyInput by remember(provider) { mutableStateOf("") }
    var modelInput by remember(provider, rev) { mutableStateOf(s.model(provider)) }
    var urlInput by remember(provider, rev) { mutableStateOf(s.baseUrl(provider)) }
    var models by remember(provider) { mutableStateOf<List<String>>(emptyList()) }
    var note by remember(provider) { mutableStateOf<Pair<String, Boolean>?>(null) } // text, isError

    Column(Modifier.fillMaxWidth()) {
        Text(
            "Tap the mic on the Drive map (or say \"Dash\"). Simple commands work offline — " +
                "\"take me home\", \"start recording\", \"what's my coolant temperature\". Everything else " +
                "goes to the AI you choose here.",
            color = Muted,
            fontSize = 13.sp,
        )

        Label("AI provider")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiProvider.entries.forEach { p ->
                val on = p == provider
                Text(
                    p.label + if (s.ready(p)) " ✓" else "",
                    color = if (on) Color.White else Muted,
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on) Color(0x333D8BFF) else Tile)
                        .border(1.dp, if (on) Accent else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable { s.setProvider(p) }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }

        Label("${provider.label} API key")
        Text(
            if (s.apiKey(provider).isNotBlank()) "Key saved on this phone (encrypted). Enter a new one to replace it."
            else "No key yet. ${provider.keyHint}",
            color = if (s.apiKey(provider).isNotBlank()) Good else Faint,
            fontSize = 12.sp,
        )
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                singleLine = true,
                placeholder = { Text("Paste API key") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { s.setApiKey(provider, keyInput); keyInput = ""; note = "Key saved." to false },
                enabled = keyInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("Save") }
        }

        if (provider != AiProvider.CLAUDE) {
            Label("Address (base URL)")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    singleLine = true,
                    placeholder = { Text("https://…/v1") },
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { s.setBaseUrl(provider, urlInput) }, modifier = Modifier.padding(start = 8.dp)) { Text("Save") }
            }
        }

        Label("Model")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = modelInput,
                onValueChange = { modelInput = it },
                singleLine = true,
                placeholder = { Text("model id") },
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { s.setModel(provider, modelInput); note = "Model set to $modelInput." to false }, modifier = Modifier.padding(start = 8.dp)) { Text("Save") }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val p = providerFor(s, provider)
                    ?: run { note = "Save an API key first." to true; return@OutlinedButton }
                note = "Loading models…" to false
                scope.launch {
                    runCatching { p.listModels() }
                        .onSuccess { models = it; note = (if (it.isEmpty()) "No models returned." else "${it.size} models — tap one to use it.") to it.isEmpty() }
                        .onFailure { note = (it.message ?: "Couldn't list models") to true }
                }
            }) { Text("List models") }
            Button(
                onClick = { VoiceAssistant.handle("Introduce yourself in one short sentence and tell me the car's coolant temperature.") },
                enabled = s.ready(provider),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
            ) { Text("Test") }
        }
        note?.let { (t, err) -> Text(t, color = if (err) Danger else Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
        if (models.isNotEmpty()) {
            Column(
                Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Tile)
                    .verticalScroll(rememberScrollState()),
            ) {
                models.forEach { m ->
                    Text(
                        m,
                        color = if (m == s.model(provider)) Accent else Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().clickable {
                            s.setModel(provider, m); modelInput = m; note = "Model set to $m." to false
                        }.padding(horizontal = 12.dp, vertical = 9.dp),
                    )
                }
            }
        }

        Label("Listening")
        ToggleRow(
            "Wake word \"Dash\"",
            "Say \"Dash\" and it listens for a command for 10 seconds. Nothing is acted on until you say it.",
            wake,
        ) { s.setWakeWord(it) }
        VoskModelRow()
        ToggleRow("Speak replies", "Read answers aloud", speak) { s.setSpeak(it) }

        Text(
            "Your request and, when needed, live car data and nearby place names are sent to the provider you " +
                "choose. Keys never leave this phone except to that provider.",
            color = Faint,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Offline "Dash" detection model (Vosk): download / progress / ready / delete. */
@Composable
private fun VoskModelRow() {
    val v by com.cardash.dashboard.ai.VoskWake.state.collectAsState()
    Column(
        Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Tile)
            .padding(14.dp),
    ) {
        Text("Offline wake word (recommended)", color = Color.White, fontSize = 15.sp)
        Text(
            when (v.status) {
                VoskStatus.READY -> "Installed. Listening for \"Dash\" happens only on this phone — no audio is sent anywhere."
                VoskStatus.DOWNLOADING -> "Downloading… ${(v.progress * 100).toInt()}%"
                VoskStatus.UNPACKING -> "Unpacking…"
                VoskStatus.LOADING -> "Loading…"
                VoskStatus.ERROR -> v.message ?: "Something went wrong."
                VoskStatus.NOT_INSTALLED -> "Without it, listening for \"Dash\" uses Google's online speech service, which " +
                    "streams mic audio while the app is open and misses the word more often. " +
                    "One-time download: ${com.cardash.dashboard.ai.VoskWake.MODEL_SIZE_MB} MB (Vosk English model, alphacephei.com)."
            },
            color = when (v.status) { VoskStatus.READY -> Good; VoskStatus.ERROR -> Danger; else -> Muted },
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (v.status == VoskStatus.DOWNLOADING) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { v.progress },
                color = Accent,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (v.status) {
                VoskStatus.NOT_INSTALLED, VoskStatus.ERROR -> Button(
                    onClick = { com.cardash.dashboard.ai.VoskWake.download() },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                ) { Text("Download (${com.cardash.dashboard.ai.VoskWake.MODEL_SIZE_MB} MB)") }
                VoskStatus.READY -> OutlinedButton(onClick = { com.cardash.dashboard.ai.VoskWake.delete() }) { Text("Remove") }
                else -> Unit
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
}
