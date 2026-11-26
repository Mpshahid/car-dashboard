package com.cardash.dashboard.ui.screens

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.collectAsState
import com.cardash.dashboard.obd.BluetoothScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardash.dashboard.obd.ConnectionStatus
import com.cardash.dashboard.obd.VehicleState
import com.cardash.dashboard.ui.UiPrefs
import com.cardash.dashboard.ui.components.ConnectionPill
import com.cardash.dashboard.ui.theme.Accent
import com.cardash.dashboard.ui.theme.Faint
import com.cardash.dashboard.ui.theme.Muted
import com.cardash.dashboard.ui.theme.Tile
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState

@OptIn(ExperimentalPermissionsApi::class)
@SuppressLint("MissingPermission")
@Composable
fun SetupScreen(
    state: VehicleState,
    prefs: UiPrefs,
    onDemo: () -> Unit,
    onDisconnect: () -> Unit,
    pairedDevices: () -> List<BluetoothDevice>,
    onConnect: (BluetoothDevice) -> Unit,
    onSetImperial: (Boolean) -> Unit,
    onSetTempF: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    scanner: BluetoothScanner? = null,
    onScan: () -> Unit = {},
    onStopScan: () -> Unit = {},
    onPair: (BluetoothDevice) -> Unit = {},
) {
    val needed = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val permissions = rememberMultiplePermissionsState(needed)
    var devices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Setup", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)

        Section("Connection") {
            val live = !state.demo && state.adapterAddress != null && (
                state.status is ConnectionStatus.Connected ||
                    state.status is ConnectionStatus.Reconnecting ||
                    state.status is ConnectionStatus.Connecting
                )
            val activeAddr = if (live) state.adapterAddress else null
            CurrentAdapterCard(state, live, onDisconnect)
            if (!state.demo) {
                OutlinedButton(onClick = onDemo, modifier = Modifier.padding(top = 10.dp)) { Text("Use demo mode instead") }
            }

            if (!permissions.allPermissionsGranted) {
                Text(
                    "Allow Bluetooth so the app can find your ELM327 adapter.",
                    color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp),
                )
                Button(
                    onClick = { permissions.launchMultiplePermissionRequest() },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Allow Bluetooth") }
            } else {
                // Paired devices load by themselves; OBD adapters first.
                LaunchedEffect(Unit) { devices = pairedDevices() }
                val sorted = devices.sortedByDescending { d ->
                    val n = runCatching { d.name }.getOrNull().orEmpty().uppercase()
                    listOf("OBD", "ELM", "VLINK", "V-LINK", "VEEPEAK", "KONNWEI").any { it in n }
                }
                DeviceDropdown(
                    title = "Paired devices",
                    count = sorted.size,
                    startOpen = activeAddr == null,
                    action = {
                        Text(
                            "Refresh", color = Accent, fontSize = 13.sp,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { devices = pairedDevices() }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    },
                ) {
                    if (sorted.isEmpty()) {
                        Text("No paired devices. Scan for new ones below.", color = Faint, fontSize = 13.sp, modifier = Modifier.padding(4.dp))
                    }
                    sorted.forEach { d ->
                        DeviceRow(d, status = if (d.address == activeAddr) state.status else null) {
                            if (d.address != activeAddr) onConnect(d)
                        }
                    }
                }

                // Find and pair a new adapter without leaving the app.
                if (scanner != null) {
                    val found by scanner.found.collectAsState()
                    val scanning by scanner.scanning.collectAsState()
                    val pairing by scanner.pairing.collectAsState()
                    val msg by scanner.message.collectAsState()
                    val newOnes = found.filter { !it.bonded }
                    DeviceDropdown(
                        title = "New devices nearby",
                        count = if (newOnes.isEmpty() && !scanning) null else newOnes.size,
                        startOpen = false,
                        openWhen = scanning,
                        action = {
                            if (scanning) {
                                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.padding(end = 8.dp).size(16.dp))
                            }
                            Text(
                                if (scanning) "Stop" else "Scan", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                    .clickable { if (scanning) onStopScan() else onScan() }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        },
                    ) {
                        Text(
                            "Plug the ELM327 in with the ignition on, then tap Scan. OBD adapters are listed first.",
                            color = Faint, fontSize = 12.sp, modifier = Modifier.padding(4.dp),
                        )
                        msg?.let { Text(it, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(4.dp)) }
                        newOnes.forEach { f ->
                            FoundRow(f, isPairing = pairing == f.device.address) { onPair(f.device) }
                        }
                    }
                    // A freshly paired adapter moves into "Paired devices".
                    LaunchedEffect(found.count { it.bonded }) { devices = pairedDevices() }
                }
            }
        }

        Section("Ride recording & web replay") {
            val logOn by com.cardash.dashboard.data.DriveLog.enabled.collectAsState()
            val keep by com.cardash.dashboard.data.DriveLog.keepDays.collectAsState()
            val rows by com.cardash.dashboard.data.DriveLog.rowsThisSession.collectAsState()
            ToggleRow(
                title = "Record rides",
                subtitle = if (logOn) "Every reading + GPS, once a second while connected · $rows rows this session"
                else "Off — rides aren't saved",
                checked = logOn,
                onChange = { com.cardash.dashboard.data.DriveLog.setEnabled(it) },
            )
            Text("Keep recordings for", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 3, 7, 30).forEach { d ->
                    val on = keep == d
                    Text(
                        if (d == 1) "1 day" else "$d days",
                        color = if (on) Color.White else Muted, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp))
                            .background(if (on) Color(0x333D8BFF) else Tile)
                            .border(1.dp, if (on) Accent else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable { com.cardash.dashboard.data.DriveLog.setKeepDays(d) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
            val used = remember(rows) { com.cardash.dashboard.data.DriveLog.totalBytes() }
            var confirmDelete by remember { mutableStateOf(false) }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Using %.1f MB".format(used / 1_048_576.0), color = Faint, fontSize = 12.sp, modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = { confirmDelete = true }) { Text("Delete all", color = com.cardash.dashboard.ui.theme.Danger) }
            }
            if (confirmDelete) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text("Delete all recordings?") },
                    text = { Text("Every saved ride on this phone will be removed. This can't be undone.") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            com.cardash.dashboard.data.DriveLog.deleteAll(); confirmDelete = false
                        }) { Text("Delete", color = com.cardash.dashboard.ui.theme.Danger) }
                    },
                    dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
                )
            }

            Spacer(Modifier.height(8.dp))
            val webOn by com.cardash.dashboard.web.RideServer.running.collectAsState()
            val webErr by com.cardash.dashboard.web.RideServer.error.collectAsState()
            var codeVersion by remember { mutableStateOf(0) }
            ToggleRow(
                title = "Ride replay web page",
                subtitle = "Replay rides on a map with charts and ask the AI — scan the code or open the link on another device",
                checked = webOn,
                onChange = { com.cardash.dashboard.web.RideServer.setEnabled(it) },
            )
            webErr?.let { Text(it, color = com.cardash.dashboard.ui.theme.Danger, fontSize = 12.sp) }
            if (webOn) {
                val addrs = remember(webOn, codeVersion) { com.cardash.dashboard.web.RideServer.addresses() }
                var pick by remember(addrs) { mutableStateOf(0) }
                Column(
                    Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).background(Tile).padding(12.dp),
                ) {
                    if (addrs.isEmpty()) {
                        Text("Connect the phone to Wi-Fi (or turn on its hotspot) to get an address.", color = Muted, fontSize = 13.sp)
                    } else {
                        val (label, url) = addrs[pick.coerceIn(0, addrs.lastIndex)]
                        Text("Scan with another phone or tablet's camera to open the page", color = Muted, fontSize = 12.sp)
                        if (addrs.size > 1) {
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                addrs.forEachIndexed { i, (l, _) ->
                                    val on = i == pick
                                    Text(
                                        l,
                                        color = if (on) Color.White else Muted,
                                        fontSize = 13.sp,
                                        modifier = Modifier.clip(RoundedCornerShape(10.dp))
                                            .background(if (on) Color(0x333D8BFF) else Color(0x14FFFFFF))
                                            .clickable { pick = i }
                                            .padding(horizontal = 12.dp, vertical = 7.dp),
                                    )
                                }
                            }
                        }
                        com.cardash.dashboard.ui.components.QrCode(
                            url,
                            Modifier.padding(top = 10.dp).size(220.dp).clip(RoundedCornerShape(8.dp)).align(Alignment.CenterHorizontally),
                            description = "QR code for the ride replay page ($label)",
                        )
                        Text("Or type it into a laptop's browser ($label):", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(url, color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                        }
                        if (label == "Tailscale") {
                            Text("Works from your own devices signed in to Tailscale, even away from this Wi-Fi.", color = Faint, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                        Text(
                            "The link includes an access code, so others on the network can't open it. " +
                                "Keep the app open while you use the page.",
                            color = Faint, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    androidx.compose.material3.TextButton(onClick = {
                        com.cardash.dashboard.web.RideServer.newAccessCode(); codeVersion++
                    }) { Text("New access code") }
                }
            }
        }

        Section("Google Maps key") {
            MapsKeySection()
        }

        Section("Units") {
            ToggleRow(
                title = "Speed and distance",
                subtitle = if (prefs.imperial) "mph, miles, UK MPG" else "km/h, km, L/100km",
                checked = prefs.imperial,
                onChange = onSetImperial,
            )
            ToggleRow(
                title = "Temperature",
                subtitle = if (prefs.tempF) "Fahrenheit" else "Celsius",
                checked = prefs.tempF,
                onChange = onSetTempF,
            )
        }

        Section("Navigation (Google Maps)") {
            Text(
                "Directions open in Google Maps beside the dashboard. Save Home and Work so you can say " +
                    "\"Dash, take me home\".",
                color = Muted,
                fontSize = 13.sp,
            )
            AddressField("Home address", com.cardash.dashboard.nav.NavManager.home.collectAsState().value) {
                com.cardash.dashboard.nav.NavManager.setHome(it)
            }
            AddressField("Work address", com.cardash.dashboard.nav.NavManager.work.collectAsState().value) {
                com.cardash.dashboard.nav.NavManager.setWork(it)
            }
        }

        Section("Voice assistant") {
            AssistantSettingsSection()
        }

        Section("Dashcam") {
            DashcamSettingsSection()
        }

        Section("About") {
            Text(
                "Car Dashboard · tuned for a 2012 Vauxhall Meriva (petrol, manual). " +
                    "Gear is estimated from speed and RPM. Readings depend on what your car " +
                    "reports over OBD-II.",
                color = Muted,
                fontSize = 13.sp,
            )
        }
    }
}

/** Address text box that saves when you press Done on the keyboard or leave the field. */
@Composable
private fun AddressField(label: String, saved: String, onSave: (String) -> Unit) {
    var text by remember(saved) { mutableStateOf(saved) }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    androidx.compose.material3.OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        placeholder = { Text("Street, town or postcode") },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onSave(text); focus.clearFocus() }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .onFocusChanged { if (!it.isFocused && text != saved) onSave(text) },
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Text(
            title.uppercase(),
            color = Faint,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        content()
    }
}

/** The adapter in use: name, address, live status and Disconnect — or what's happening instead. */
@Composable
private fun CurrentAdapterCard(state: VehicleState, live: Boolean, onDisconnect: () -> Unit) {
    val s = state.status
    val (dot, title, sub) = when {
        state.demo -> Triple(com.cardash.dashboard.ui.theme.Warn, "Demo mode", "Simulated data. Connect your adapter below.")
        live && s is ConnectionStatus.Connected -> Triple(com.cardash.dashboard.ui.theme.Good, s.adapter, "Connected · live data")
        live && s is ConnectionStatus.Reconnecting -> Triple(
            com.cardash.dashboard.ui.theme.Warn, s.adapter,
            "Link dropped (${s.reason}). Reconnecting automatically… try ${s.attempt}",
        )
        live && s is ConnectionStatus.Connecting -> Triple(com.cardash.dashboard.ui.theme.Warn, "Connecting…", state.adapterAddress.orEmpty())
        s is ConnectionStatus.Error -> Triple(com.cardash.dashboard.ui.theme.Danger, "Couldn't connect", s.message)
        else -> Triple(Faint, "No adapter connected", "Pick your ELM327 from the lists below.")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(com.cardash.dashboard.ui.theme.Surface1)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(dot))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = Muted, fontSize = 12.sp)
            if (live && state.adapterAddress != null) Text(state.adapterAddress, color = Faint, fontSize = 11.sp)
        }
        if (live) OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
    }
}

/** Collapsible list ("drop-down") with a title, optional count and an action on the right. */
@Composable
private fun DeviceDropdown(
    title: String,
    count: Int?,
    startOpen: Boolean,
    openWhen: Boolean = false,
    action: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    var open by androidx.compose.runtime.saveable.rememberSaveable(title) { mutableStateOf(startOpen) }
    LaunchedEffect(openWhen) { if (openWhen) open = true }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(com.cardash.dashboard.ui.theme.Surface1),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (count != null) Text("  $count", color = Faint, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            action()
            androidx.compose.material3.Icon(
                if (open) androidx.compose.material.icons.Icons.Filled.KeyboardArrowUp else androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown,
                contentDescription = if (open) "Collapse" else "Expand",
                tint = Muted,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        androidx.compose.animation.AnimatedVisibility(open) {
            Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) { content() }
        }
    }
}

@Composable
private fun DeviceRow(device: BluetoothDevice, status: ConnectionStatus? = null, onClick: () -> Unit) {
    @SuppressLint("MissingPermission")
    val name = runCatching { device.name }.getOrNull() ?: "Unknown device"
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Tile)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = Color.White, fontSize = 16.sp)
            Text(device.address, color = Faint, fontSize = 12.sp)
        }
        val (label, color) = when (status) {
            is ConnectionStatus.Connected -> "● Connected" to com.cardash.dashboard.ui.theme.Good
            is ConnectionStatus.Reconnecting -> "Reconnecting…" to com.cardash.dashboard.ui.theme.Warn
            is ConnectionStatus.Connecting -> "Connecting…" to com.cardash.dashboard.ui.theme.Warn
            else -> "Connect" to Accent
        }
        Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FoundRow(f: BluetoothScanner.Found, isPairing: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Tile)
            .clickable(enabled = !isPairing, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(f.name, color = Color.White, fontSize = 15.sp)
            Text(
                f.device.address + (if (com.cardash.dashboard.obd.BleObdConnection.isBle(f.device)) " · Bluetooth LE, no PIN" else "") +
                    if (f.likelyObd) " · looks like an OBD adapter" else "",
                color = if (f.likelyObd) Accent else Faint,
                fontSize = 12.sp,
            )
        }
        Text(
            when {
                isPairing -> "Pairing…"
                f.bonded || com.cardash.dashboard.obd.BleObdConnection.isBle(f.device) -> "Connect"
                else -> "Pair & connect"
            },
            color = Accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
internal fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            Text(subtitle, color = Muted, fontSize = 13.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
