package com.cardash.dashboard.ui

import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cardash.dashboard.dashcam.DashcamEngine
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cardash.dashboard.obd.ConnectionStatus
import com.cardash.dashboard.obd.ObdManager
import com.cardash.dashboard.ui.screens.DashcamScreen
import com.cardash.dashboard.ui.screens.DriveScreen
import com.cardash.dashboard.ui.screens.SensorsScreen
import com.cardash.dashboard.ui.screens.SetupScreen
import com.cardash.dashboard.ui.screens.StatusScreen
import com.cardash.dashboard.ui.screens.TripsScreen
import com.cardash.dashboard.vm.DashboardViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

private data class Dest(val title: String, val icon: ImageVector)

private val DESTS = listOf(
    Dest("Drive", Icons.Filled.Speed),
    Dest("Dashcam", Icons.Filled.Videocam),
    Dest("Status", Icons.Filled.Insights),
    Dest("Sensors", Icons.Filled.ShowChart),
    Dest("Trips", Icons.Filled.Timeline),
    Dest("Setup", Icons.Filled.Settings),
)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun DashboardRoot() {
    val vm: DashboardViewModel = viewModel()
    val state by vm.state.collectAsState()
    val imperial by vm.imperial.collectAsState()
    val tempF by vm.tempF.collectAsState()
    val pinned by vm.pinned.collectAsState()
    val speedLimit by vm.speedLimit.collectAsState()
    val mpgHistory by vm.mpgHistory.collectAsState()
    val prefs = UiPrefs(imperial = imperial, tempF = tempF)

    val locationPermission = rememberPermissionState(android.Manifest.permission.ACCESS_FINE_LOCATION)
    val hasLocation = locationPermission.status.isGranted

    // Dashcam: one camera session for the whole app (Drive tile + Dashcam tab), bound to this activity.
    val cameraPermission = rememberPermissionState(android.Manifest.permission.CAMERA)
    val camGranted = cameraPermission.status.isGranted
    val dashcamEnabled by DashcamEngine.settings.enabled.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(camGranted, dashcamEnabled, lifecycleOwner) {
        if (camGranted && dashcamEnabled) DashcamEngine.attach(lifecycleOwner)
        onDispose { DashcamEngine.detach(lifecycleOwner) }
    }
    // Voice assistant wake word runs only while the app is on screen.
    DisposableEffect(lifecycleOwner) {
        var started = false // balance each ON_START with exactly one release
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> if (!started) {
                    started = true
                    com.cardash.dashboard.data.DriveKeeper.setVisible(true)
                    com.cardash.dashboard.ai.VoiceAssistant.setForeground(true)
                    com.cardash.dashboard.music.MusicController.refresh() // picks up newly granted access
                }
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> if (started) {
                    started = false
                    com.cardash.dashboard.data.DriveKeeper.setVisible(false)
                    com.cardash.dashboard.ai.VoiceAssistant.setForeground(false)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            if (started) {
                com.cardash.dashboard.data.DriveKeeper.setVisible(false)
                com.cardash.dashboard.ai.VoiceAssistant.setForeground(false)
            }
        }
    }

    val rootView = LocalView.current
    val orientation = LocalConfiguration.current.orientation
    LaunchedEffect(orientation) {
        DashcamEngine.setRotation(rootView.display?.rotation ?: android.view.Surface.ROTATION_0)
    }

    LaunchedEffect(Unit) {
        if (ObdManager.state.value.status is ConnectionStatus.Disconnected) vm.autoStart()
    }
    LaunchedEffect(hasLocation) {
        if (hasLocation) vm.startLocationUpdates()
    }
    // Android 13+: the "Recording your drive" notice (shown while a drive is recorded with the dashboard off
    // screen) needs notification permission. Asked once; the recording works either way.
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        val notify = rememberPermissionState(android.Manifest.permission.POST_NOTIFICATIONS)
        val ctx = androidx.compose.ui.platform.LocalContext.current
        LaunchedEffect(Unit) {
            val p = ctx.getSharedPreferences("cardash_ui", android.content.Context.MODE_PRIVATE)
            if (!notify.status.isGranted && !p.getBoolean("asked_notifications", false)) {
                kotlinx.coroutines.delay(1500)
                p.edit().putBoolean("asked_notifications", true).apply()
                notify.launchPermissionRequest()
            }
        }
    }

    var selected by remember { mutableIntStateOf(0) }
    // Google Maps opens beside this activity (split screen), so hand it our activity context.
    val activity = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(activity) {
        com.cardash.dashboard.nav.NavManager.setHost(activity)
        onDispose { com.cardash.dashboard.nav.NavManager.setHost(null) }
    }

    val content: @Composable (Modifier) -> Unit = { mod ->
        when (selected) {
            0 -> DriveScreen(state, prefs, speedLimit, mod)
            1 -> DashcamScreen(state, prefs, speedLimit, mod)
            2 -> StatusScreen(state, prefs, mpgHistory, vm::refreshFaults, vm::clearFaults, mod)
            3 -> SensorsScreen(state, prefs, pinned, vm::togglePin, mod)
            4 -> TripsScreen(state, prefs, mod)
            else -> SetupScreen(
                state = state,
                prefs = prefs,
                onDemo = vm::startDemo,
                onDisconnect = vm::disconnect,
                pairedDevices = vm::pairedDevices,
                onConnect = vm::connect,
                scanner = vm.btScanner,
                onScan = vm::scanForAdapters,
                onStopScan = vm::stopScan,
                onPair = vm::pairAndConnect,
                onSetImperial = vm::setImperial,
                onSetTempF = vm::setTempF,
                modifier = mod,
            )
        }
    }

    val wide = LocalConfiguration.current.screenWidthDp >= 720

    if (wide) {
        Row(Modifier.fillMaxSize()) {
            run {
                NavigationRail {
                    DESTS.forEachIndexed { i, d ->
                        NavigationRailItem(
                            selected = selected == i,
                            onClick = { selected = i },
                            icon = { Icon(d.icon, contentDescription = d.title) },
                            label = { Text(d.title) },
                        )
                    }
                }
            }
            Box(Modifier.fillMaxSize()) {
                content(Modifier.fillMaxSize())
                // Voice assistant card floats over every tab.
                com.cardash.dashboard.ui.components.AssistantPanel(
                    Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(top = 14.dp),
                )
            }
        }
    } else if (LocalConfiguration.current.screenWidthDp < 300) {
        // ~20% strip beside Google Maps: content on top, a single tab picker at the bottom.
        var pickerOpen by remember { androidx.compose.runtime.mutableStateOf(false) }
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxSize()) {
                content(Modifier.fillMaxSize())
                com.cardash.dashboard.ui.components.AssistantPanel(
                    Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(top = 8.dp),
                )
                // Tab menu covers the whole strip (big rows, easy to hit while driving).
                androidx.compose.animation.AnimatedVisibility(
                    visible = pickerOpen,
                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it / 6 },
                    exit = androidx.compose.animation.fadeOut(),
                ) {
                    MicroTabSheet(selected) { selected = it; pickerOpen = false }
                }
            }
            MicroTabPicker(selected, pickerOpen) { pickerOpen = !pickerOpen }
        }
    } else {
        // Narrow (e.g. the dashboard strip beside Google Maps): icon-only tabs so all six fit.
        val compact = LocalConfiguration.current.screenWidthDp < 480
        Scaffold(
            bottomBar = {
                NavigationBar(
                    modifier = if (compact) Modifier.height(64.dp) else Modifier,
                    windowInsets = androidx.compose.foundation.layout.WindowInsets(0),
                ) {
                    DESTS.forEachIndexed { i, d ->
                        NavigationBarItem(
                            selected = selected == i,
                            onClick = { selected = i },
                            icon = { Icon(d.icon, contentDescription = d.title) },
                            label = if (compact) null else ({ Text(d.title) }),
                            alwaysShowLabel = !compact,
                        )
                    }
                }
            },
        ) { inner ->
            Box(Modifier.fillMaxSize().padding(inner)) {
                content(Modifier.fillMaxSize())
                com.cardash.dashboard.ui.components.AssistantPanel(
                    Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(top = 14.dp),
                )
            }
        }
    }
}

/** Bottom bar for a very narrow window: shows the current tab; tap to open / close the tab menu. */
@Composable
private fun MicroTabPicker(selected: Int, open: Boolean, onToggle: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(com.cardash.dashboard.ui.theme.Surface1)
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        val d = DESTS[selected]
        Icon(d.icon, contentDescription = null, tint = com.cardash.dashboard.ui.theme.Accent)
        Text(
            d.title,
            color = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.weight(1f).padding(start = 10.dp),
        )
        Icon(
            if (open) Icons.Filled.Close else Icons.Filled.ArrowDropUp,
            contentDescription = if (open) "Close tabs" else "Choose tab",
            tint = androidx.compose.ui.graphics.Color.White,
        )
    }
}

/** Full-cover tab menu for the narrow strip: one large row per tab, current one highlighted. */
@Composable
private fun MicroTabSheet(selected: Int, onSelect: (Int) -> Unit) {
    androidx.compose.foundation.layout.Column(
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(0xFF0B0C0F))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) {} // swallow taps so nothing underneath reacts
            .padding(10.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
    ) {
        DESTS.forEachIndexed { i, d ->
            val on = i == selected
            androidx.compose.foundation.layout.Row(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    .background(if (on) androidx.compose.ui.graphics.Color(0x333D8BFF) else com.cardash.dashboard.ui.theme.Surface1)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Icon(
                    d.icon,
                    contentDescription = null,
                    tint = if (on) com.cardash.dashboard.ui.theme.Accent else androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(26.dp),
                )
                Text(
                    d.title,
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(start = 14.dp),
                )
            }
        }
    }
}
