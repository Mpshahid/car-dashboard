package com.cardash.dashboard.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardash.dashboard.data.MapsKey
import com.cardash.dashboard.ui.theme.Accent
import com.cardash.dashboard.ui.theme.Danger
import com.cardash.dashboard.ui.theme.Faint
import com.cardash.dashboard.ui.theme.Good
import com.cardash.dashboard.ui.theme.Muted

/** Setup ▸ Google Maps key: the key the ride replay page uses for Google's map, satellite photos and 3D view. */
@Composable
fun MapsKeySection() {
    val context = LocalContext.current
    val rev by MapsKey.rev.collectAsState()
    val saved = remember(rev) { MapsKey.saved(context).isNotBlank() }
    var input by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // text, isWarning

    Column(Modifier.fillMaxWidth()) {
        Text(
            "The ride replay page shows Google Maps, with satellite photos and a 3D view, when it has a Google Maps " +
                "API key. Without one it uses the free OpenStreetMap map.",
            color = Muted,
            fontSize = 13.sp,
        )
        Text(
            if (saved) "Key saved on this phone (encrypted). Enter a new one to replace it."
            else "No key yet. Create one at console.cloud.google.com with the Maps JavaScript API enabled.",
            color = if (saved) Good else Faint,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                placeholder = { Text("Paste Google Maps API key") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    val key = input.trim()
                    MapsKey.save(context, key)
                    input = ""
                    note = if (MapsKey.looksValid(key)) "Key saved. Reload the replay page to use it." to false
                    else "Saved, but that doesn't look like a Google API key (39 characters starting with AIza)." to true
                },
                enabled = input.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("Save") }
        }
        if (saved) {
            TextButton(onClick = { MapsKey.save(context, ""); note = "Key removed from this phone." to false }) {
                Text("Remove key", color = Danger)
            }
        }
        note?.let { (text, warn) ->
            Text(text, color = if (warn) Danger else Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Text(
            "The key stays on this phone and isn't included in backups. It only goes to the replay page on devices " +
                "that open your link (it carries the access code), and from there to Google.",
            color = Faint,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
