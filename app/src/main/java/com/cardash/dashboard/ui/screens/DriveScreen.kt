package com.cardash.dashboard.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.filled.Map
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
import com.cardash.dashboard.insights.Health
import com.cardash.dashboard.insights.Insights
import com.cardash.dashboard.nav.SpeedLimit
import com.cardash.dashboard.obd.GearReading
import com.cardash.dashboard.obd.Pid
import com.cardash.dashboard.ui.theme.Accent
import com.cardash.dashboard.ui.theme.Tile
import com.cardash.dashboard.obd.VehicleState
import com.cardash.dashboard.ui.Fmt
import com.cardash.dashboard.ui.UiPrefs
import com.cardash.dashboard.ui.components.ConnectionPill
import com.cardash.dashboard.ui.components.DashcamTile
import com.cardash.dashboard.ui.components.PhoneStatusRow
import com.cardash.dashboard.ui.components.GearBadge
import com.cardash.dashboard.ui.components.RpmBar
import com.cardash.dashboard.ui.components.SpeedLimitSign
import com.cardash.dashboard.ui.theme.Danger
import com.cardash.dashboard.ui.theme.Faint
import com.cardash.dashboard.ui.theme.Good
import com.cardash.dashboard.ui.theme.Hairline
import com.cardash.dashboard.ui.theme.Muted
import com.cardash.dashboard.ui.theme.Surface1
import com.cardash.dashboard.ui.theme.Warn
import kotlinx.coroutines.delay

private data class Reading(val label: String, val value: String, val unit: String)


/**
 * Navigation runs in the real Google Maps app, side by side with the dashboard.
 * Narrow (beside Google Maps, ~1/4 of the screen): a compact cluster on top, panels below.
 * Wide (dashboard full screen): panels on the left, big gauges and an "Open Google Maps" button.
 */
@Composable
fun DriveScreen(
    state: VehicleState,
    prefs: UiPrefs,
    speedLimit: SpeedLimit?,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Color(0xFF060607))
            .windowInsetsPadding(
                androidx.compose.foundation.layout.WindowInsets.displayCutout
                    .union(androidx.compose.foundation.layout.WindowInsets.statusBars)
                    .only(androidx.compose.foundation.layout.WindowInsetsSides.Top),
            ),
    ) {
        if (maxWidth < 300.dp) {
            // ~20% of the screen beside Google Maps: everything stacked, nothing side by side.
            Column(Modifier.fillMaxSize()) {
                MicroCluster(state, prefs, speedLimit, Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 6.dp))
                ObdPanel(state, prefs, Modifier.fillMaxWidth().weight(1f), tight = true)
            }
        } else if (maxWidth < 600.dp) {
            Column(Modifier.fillMaxSize()) {
                StripCluster(state, prefs, speedLimit, Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 10.dp))
                ObdPanel(state, prefs, Modifier.fillMaxWidth().weight(1f))
            }
        } else {
            val panelWidth = (maxWidth * 0.32f).coerceIn(280.dp, 380.dp)
            Row(Modifier.fillMaxSize()) {
                ObdPanel(state, prefs, Modifier.width(panelWidth).fillMaxHeight())
                BigCluster(state, prefs, speedLimit, Modifier.weight(1f).fillMaxHeight().padding(8.dp))
            }
        }
    }
}

/** Live figures shared by both clusters. */
private class Live(state: VehicleState, prefs: UiPrefs, limit: SpeedLimit?) {
    private val kmh = state.value(Pid.SPEED) ?: 0.0
    val speed = Fmt.speedValue(kmh, prefs.imperial)
    val unit = Fmt.speedUnit(prefs.imperial)
    val over = limit != null && kmh > limit.kmh + 4
    val rpm = state.value(Pid.ENGINE_RPM) ?: 0.0
    val rpmFrac = (rpm / 7000.0).coerceIn(0.0, 1.0).toFloat()
    val rpmColor = when {
        rpm >= 6000 -> Danger
        rpm >= 4500 -> Warn
        else -> Accent
    }
    val gear = when (val g = state.gear) {
        is GearReading.Engaged -> g.gear.toString()
        GearReading.Neutral -> "N"
        GearReading.Unknown -> "–"
    }
}

/**
 * Very narrow header (~20% of the screen), two short rows:
 *   [ 44 mph            (4) ]
 *   [ mic  ▬▬▬▬ 2869   map ]
 */
@Composable
private fun MicroCluster(state: VehicleState, prefs: UiPrefs, limit: SpeedLimit?, modifier: Modifier = Modifier) {
    val l = Live(state, prefs, limit)
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Surface1)
            .border(0.5.dp, Hairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SpeedText(l, 38)
            Spacer(Modifier.weight(1f))
            if (limit != null) {
                SpeedLimitSign(limit, l.over, prefs.imperial)
                Spacer(Modifier.width(6.dp))
            }
            GearChip(l.gear)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            com.cardash.dashboard.ui.components.AssistantMicButton(compact = true)
            com.cardash.dashboard.ui.components.MicMuteButton(sizeDp = 30)
            RpmInline(l, Modifier.weight(1f).padding(horizontal = 6.dp))
            MapsButton(36)
        }
    }
}

/**
 * Narrow strip header, one line plus a thin RPM bar:
 *   [ mic  44 mph  (4)        (30)  map ]
 *   [ ▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬ 2869 rpm ]
 */
@Composable
private fun StripCluster(state: VehicleState, prefs: UiPrefs, limit: SpeedLimit?, modifier: Modifier = Modifier) {
    val l = Live(state, prefs, limit)
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Surface1)
            .border(0.5.dp, Hairline, RoundedCornerShape(16.dp))
            .padding(start = 4.dp, end = 10.dp, top = 2.dp, bottom = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            com.cardash.dashboard.ui.components.AssistantMicButton(compact = true)
            com.cardash.dashboard.ui.components.MicMuteButton(sizeDp = 32)
            Spacer(Modifier.width(10.dp))
            SpeedText(l, 40)
            Spacer(Modifier.width(12.dp))
            GearChip(l.gear)
            Spacer(Modifier.weight(1f))
            if (limit != null) {
                SpeedLimitSign(limit, l.over, prefs.imperial)
                Spacer(Modifier.width(8.dp))
            }
            MapsButton(40)
        }
        RpmInline(l, Modifier.fillMaxWidth().padding(start = 8.dp))
    }
}

@Composable
private fun SpeedText(l: Live, sizeSp: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            l.speed.toString(),
            color = if (l.over) Danger else Color.White,
            fontSize = sizeSp.sp,
            fontWeight = FontWeight.Light,
            maxLines = 1,
        )
        Text(l.unit, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(start = 3.dp, bottom = (sizeSp / 5).dp))
    }
}

/** Small gear chip: just the gear letter/number in a rounded square. */
@Composable
private fun GearChip(gear: String) {
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Tile),
        contentAlignment = Alignment.Center,
    ) {
        Text(gear, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }
}

/** Thin RPM bar with the number on its right. */
@Composable
private fun RpmInline(l: Live, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x22FFFFFF))) {
            Box(Modifier.fillMaxWidth(l.rpmFrac).height(4.dp).clip(RoundedCornerShape(2.dp)).background(l.rpmColor))
        }
        Text(
            "${l.rpm.toInt()}",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 8.dp),
        )
        Text(" rpm", color = Faint, fontSize = 10.sp)
    }
}

/** Full-screen dashboard: large speed, gear and RPM, plus the button that opens Google Maps beside it. */
@Composable
private fun BigCluster(state: VehicleState, prefs: UiPrefs, limit: SpeedLimit?, modifier: Modifier = Modifier) {
    val l = Live(state, prefs, limit)
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF0C0D10))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            com.cardash.dashboard.ui.components.AssistantMicButton()
            com.cardash.dashboard.ui.components.MicMuteButton(sizeDp = 40)
            Spacer(Modifier.weight(1f))
            ClockChip()
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(l.speed.toString(), color = if (l.over) Danger else Color.White, fontSize = 96.sp, fontWeight = FontWeight.Light)
            Text(l.unit, color = Muted, fontSize = 20.sp, modifier = Modifier.padding(start = 8.dp, bottom = 20.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (limit != null) SpeedLimitSign(limit, l.over, prefs.imperial)
            GearBox(l.gear, big = true)
        }
        Spacer(Modifier.height(14.dp))
        RpmLine(l, Modifier.fillMaxWidth(0.6f))
        Spacer(Modifier.weight(1f).heightIn(min = 16.dp))
        if (com.cardash.dashboard.nav.NavManager.googleMapsInstalled()) {
            androidx.compose.material3.Button(
                onClick = { com.cardash.dashboard.nav.NavManager.openGoogleMaps(context) },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Accent),
            ) {
                Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open Google Maps", fontSize = 16.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Opens beside the dashboard. Drag the divider so the map gets about three quarters of the screen.",
                color = Faint,
                fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        } else {
            Text("Install Google Maps for navigation.", color = Muted, fontSize = 14.sp)
        }
    }
}

@Composable
private fun MapsButton(sizeDp: Int = 44) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Box(
        Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Tile)
            .clickable { com.cardash.dashboard.nav.NavManager.openGoogleMaps(context) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Map, contentDescription = "Open Google Maps", tint = Accent, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun GearBox(gear: String, big: Boolean) {
    Column(
        Modifier
            .size(if (big) 64.dp else 54.dp)
            .clip(RoundedCornerShape(if (big) 18.dp else 14.dp))
            .background(Tile),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(gear, color = Color.White, fontSize = if (big) 28.sp else 24.sp, fontWeight = FontWeight.Medium)
        Text("GEAR", color = Faint, fontSize = if (big) 10.sp else 8.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RpmLine(l: Live, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            Text("${l.rpm.toInt()}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(" rpm", color = Faint, fontSize = 12.sp)
        }
        Spacer(Modifier.height(5.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x22FFFFFF))) {
            Box(Modifier.fillMaxWidth(l.rpmFrac).height(6.dp).clip(RoundedCornerShape(3.dp)).background(l.rpmColor))
        }
    }
}

/** Panel of phone status, dashcam, music, health and every live OBD reading, grouped into cards. */
@Composable
private fun ObdPanel(
    state: VehicleState,
    prefs: UiPrefs,
    modifier: Modifier = Modifier,
    tight: Boolean = false,
) {
    val report = Insights.health(state)

    fun pid(p: Pid): Reading {
        val v = state.value(p)
        return Reading(p.label, if (v != null) Fmt.reading(p, v, prefs) else "--", Fmt.unit(p, prefs))
    }

    val runSec = state.value(Pid.RUN_TIME)
    val trip = state.trip
    val sinceStart = listOf(
        Reading("Run time", runSec?.let { Fmt.duration(it.toLong()) } ?: "--", ""),
        pid(Pid.DIST_SINCE_CLEAR),
        Reading("Trip distance", "%.1f".format(Fmt.distValue(trip.distanceKm, prefs.imperial)), Fmt.distUnit(prefs.imperial)),
        Reading("Avg speed", Fmt.speedValue(trip.avgSpeedKmh, prefs.imperial).toString(), Fmt.speedUnit(prefs.imperial)),
        Reading("Economy", Fmt.economy(trip.instantL100, prefs.imperial), Fmt.economyUnit(prefs.imperial)),
    )

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (tight) 6.dp else 16.dp, vertical = if (tight) 10.dp else 14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ConnectionPill(state.status, state.demo)
        }

        // Phone: battery · temperature · internet speed.
        Spacer(Modifier.height(10.dp))
        PhoneStatusRow()

        // Live dashcam (tap to start/stop recording).
        Spacer(Modifier.height(12.dp))
        DashcamTile(Modifier.fillMaxWidth())

        // Now playing (YouTube Music, Spotify, …).
        Spacer(Modifier.height(10.dp))
        com.cardash.dashboard.ui.components.MusicCard()

        Spacer(Modifier.height(16.dp))
        HealthRow(report.worst, report.label, report.score)

        Spacer(Modifier.height(18.dp))
        ReadingCard("Engine", listOf(
            pid(Pid.ENGINE_RPM), pid(Pid.ENGINE_LOAD), pid(Pid.THROTTLE),
            pid(Pid.TIMING_ADVANCE), pid(Pid.MAF), pid(Pid.MAP_PRESSURE),
        ))
        ReadingCard("Temperatures", listOf(
            pid(Pid.COOLANT_TEMP), pid(Pid.INTAKE_TEMP), pid(Pid.OIL_TEMP), pid(Pid.AMBIENT_TEMP),
        ))
        ReadingCard("Fuel & air", listOf(
            pid(Pid.FUEL_LEVEL), pid(Pid.FUEL_RATE), pid(Pid.SHORT_FUEL_TRIM), pid(Pid.LONG_FUEL_TRIM),
        ))
        ReadingCard("Electrical", listOf(pid(Pid.MODULE_VOLTAGE)))
        ReadingCard("Since start", sinceStart)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HealthRow(health: Health, label: String, score: Int) {
    val dot = when (health) {
        Health.GOOD -> Good
        Health.WATCH -> Warn
        Health.ISSUE -> Danger
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 9.dp))
        Text(" · $score/100", color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun ReadingCard(title: String, rows: List<Reading>) {
    // Tap the heading to fold / unfold this section.
    var open by androidx.compose.runtime.saveable.rememberSaveable(title) { mutableStateOf(true) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title.uppercase(), color = Faint, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Icon(
            if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            if (open) "Collapse $title" else "Expand $title",
            tint = Faint,
            modifier = Modifier.size(18.dp),
        )
    }
    AnimatedVisibility(open) { ReadingRows(rows) }
    Spacer(Modifier.height(if (open) 14.dp else 4.dp))
}

@Composable
private fun ReadingRows(rows: List<Reading>) {
    Column(Modifier.clip(RoundedCornerShape(14.dp)).background(Surface1)) {
        rows.forEachIndexed { i, r ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                // Long labels shorten with … rather than pushing the value off a narrow screen.
                Text(
                    r.label, color = Muted, fontSize = 13.sp, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(r.value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    if (r.unit.isNotEmpty()) {
                        Text(r.unit, color = Faint, fontSize = 11.sp, modifier = Modifier.padding(start = 3.dp, bottom = 1.dp))
                    }
                }
            }
            if (i < rows.lastIndex) {
                Box(Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(1.dp).background(Hairline))
            }
        }
    }
}


@Composable
private fun ClockChip(modifier: Modifier = Modifier) {
    val clock = rememberClock()
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x99090A0C))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(clock, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun rememberClock(): String {
    var now by remember { mutableStateOf(clockString()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = clockString()
            delay(20_000)
        }
    }
    return now
}

private fun clockString(): String =
    java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date())
