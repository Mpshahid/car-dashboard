package com.cardash.dashboard.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardash.dashboard.ai.CarAnalyzer
import com.cardash.dashboard.domain.Units
import com.cardash.dashboard.insights.CarAlert
import com.cardash.dashboard.insights.CarAlerts
import com.cardash.dashboard.insights.Insights
import com.cardash.dashboard.insights.NormalRanges
import com.cardash.dashboard.insights.Severity
import com.cardash.dashboard.obd.Pid
import com.cardash.dashboard.obd.PidGroup
import com.cardash.dashboard.obd.Sample
import com.cardash.dashboard.obd.SensorHistory
import com.cardash.dashboard.obd.VehicleState
import com.cardash.dashboard.ui.Fmt
import com.cardash.dashboard.ui.UiPrefs
import com.cardash.dashboard.ui.components.ArcGauge
import com.cardash.dashboard.ui.components.ChartLegend
import com.cardash.dashboard.ui.components.ChartSeries
import com.cardash.dashboard.ui.components.GaugeZone
import com.cardash.dashboard.ui.components.ScoreRing
import com.cardash.dashboard.ui.components.Sparkline
import com.cardash.dashboard.ui.components.TimeChart
import com.cardash.dashboard.ui.components.VizColors
import com.cardash.dashboard.ui.theme.Accent
import com.cardash.dashboard.ui.theme.Faint
import com.cardash.dashboard.ui.theme.Hairline
import com.cardash.dashboard.ui.theme.Muted
import com.cardash.dashboard.ui.theme.Surface1
import com.cardash.dashboard.ui.theme.Tile

private val CardShape = RoundedCornerShape(18.dp)

/** "85–105 °C" — the healthy range for [p] in the user's units, or null if there isn't one. */
private fun normalText(p: Pid, prefs: UiPrefs, withUnit: Boolean = true): String? {
    val r = NormalRanges.of(p) ?: return null
    val u = Fmt.unit(p, prefs)
    return "${Fmt.reading(p, r.low, prefs)}–${Fmt.reading(p, r.high, prefs)}" + if (withUnit && u.isNotBlank()) " $u" else ""
}

private fun outOfRange(p: Pid, v: Double?): Boolean {
    val r = NormalRanges.of(p) ?: return false
    return v != null && !r.contains(v)
}

/** A live chart: one unit / one axis, up to 3 series. */
private data class ChartSpec(
    val title: String,
    val pids: List<Pid>,
    val band: ClosedFloatingPointRange<Double>? = null,
    val bandLabel: String = "normal",
    val zeroLine: Boolean = false,
)

private val CHARTS = listOf(
    ChartSpec("Engine speed", listOf(Pid.ENGINE_RPM)),
    ChartSpec("Load & throttle", listOf(Pid.ENGINE_LOAD, Pid.THROTTLE)),
    ChartSpec("Temperatures", listOf(Pid.COOLANT_TEMP, Pid.OIL_TEMP, Pid.INTAKE_TEMP), 85.0..105.0, "coolant normal"),
    ChartSpec("Fuel trims", listOf(Pid.SHORT_FUEL_TRIM, Pid.LONG_FUEL_TRIM), -10.0..10.0, "±10% healthy", zeroLine = true),
    ChartSpec("Oxygen sensors", listOf(Pid.O2_B1S1_VOLT, Pid.O2_B1S2_VOLT)),
    ChartSpec("Wide-band lambda", listOf(Pid.LAMBDA_B1S1, Pid.LAMBDA_B1S1_CURRENT, Pid.COMMANDED_LAMBDA), 0.97..1.03, "stoichiometric"),
    ChartSpec("Battery / charging", listOf(Pid.MODULE_VOLTAGE), 13.5..14.7, "charging range"),
    ChartSpec("Air flow", listOf(Pid.MAF)),
    ChartSpec("Catalytic converter", listOf(Pid.CAT_TEMP_B1S1, Pid.CAT_TEMP_B1S2), 300.0..850.0, "working range"),
    ChartSpec("Manifold pressure", listOf(Pid.MAP_PRESSURE)),
)

/** True when the Sensors tab sits in a narrow strip (e.g. ~20% beside Google Maps). */
private val LocalNarrow = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
fun SensorsScreen(
    state: VehicleState,
    prefs: UiPrefs,
    pinned: List<Pid>,
    onTogglePin: (Pid) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tick by SensorHistory.tick.collectAsState()
    val now = remember(tick) { SensorHistory.now() }
    var windowS by rememberSaveable { mutableStateOf(300f) }
    val supported = if (state.supportedPids.isEmpty()) Pid.entries.toSet() else state.supportedPids
    val alerts = remember(tick, state.milOn, state.faults, state.readiness) { CarAlerts.evaluate(state) }

    val charts = CHARTS.map { c -> c.copy(pids = c.pids.filter { it in supported }) }.filter { it.pids.isNotEmpty() } +
        pinned.filter { p -> p in supported && CHARTS.none { p in it.pids } }.map { ChartSpec(it.label + " (pinned)", listOf(it)) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val narrow = maxWidth < 360.dp
        androidx.compose.runtime.CompositionLocalProvider(LocalNarrow provides narrow) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = if (narrow) 8.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (narrow) 8.dp else 12.dp),
        ) {
            item {
                val sub = when {
                    state.demo -> "Demo data · ${supported.size} parameters"
                    state.connected -> "Live from your car · ${supported.size} parameters"
                    else -> "Not connected"
                }
                if (narrow) {
                    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        Text("Sensors", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                        Text(sub, color = Muted, fontSize = 12.sp)
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.Bottom) {
                        Text("Sensors", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(12.dp))
                        Text(sub, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 3.dp))
                    }
                }
            }

            // ---- Condition · alerts · AI
            item {
                if (wide) {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ConditionCard(state, alerts, Modifier.weight(1.1f).fillMaxHeight())
                        AlertsCard(alerts, Modifier.weight(1.3f).fillMaxHeight())
                        AiCard(Modifier.weight(1.4f).fillMaxHeight())
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ConditionCard(state, alerts, Modifier.fillMaxWidth())
                        AlertsCard(alerts, Modifier.fillMaxWidth())
                        AiCard(Modifier.fillMaxWidth())
                    }
                }
            }

            // ---- Gauges
            item { GaugesRow(state, prefs, supported, wide) }

            // ---- Trends
            item {
                @Composable
                fun WindowChips() = listOf(60f to "1 min", 300f to "5 min", 900f to "15 min").forEachIndexed { i, (w, l) ->
                    val on = windowS == w
                    Text(
                        l, color = if (on) Color.White else Muted, fontSize = 12.sp, maxLines = 1,
                        modifier = Modifier.padding(start = if (narrow && i == 0) 0.dp else 6.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (on) Color(0x333D8BFF) else Tile)
                            .border(1.dp, if (on) Accent else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable { windowS = w }.padding(horizontal = if (narrow) 9.dp else 12.dp, vertical = 6.dp),
                    )
                }
                if (narrow) {
                    Text("Live trends", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.padding(top = 6.dp)) { WindowChips() }
                } else {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Live trends", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        WindowChips()
                    }
                }
                Text("Drag or tap a chart to read exact values.", color = Faint, fontSize = 11.sp)
            }
            charts.chunked(if (wide) 2 else 1).forEach { row ->
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { spec -> ChartCard(spec, state, prefs, windowS, now, Modifier.weight(1f)) }
                        if (row.size == 1 && wide) Spacer(Modifier.weight(1f))
                    }
                }
            }

            // ---- Readiness / identity
            item { ReadinessCard(state) }

            // ---- Every parameter
            item {
                Text("All parameters", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
                Text("Session min / average / max · ★ pins a parameter's chart above", color = Faint, fontSize = 11.sp)
            }
            PidGroup.entries.forEach { group ->
                val pids = Pid.entries.filter { it.group == group && it in supported }
                if (pids.isEmpty()) return@forEach
                item { ParamGroup(group.label, pids, state, prefs, pinned, onTogglePin, windowS, wide) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
        }
    }
}

/* ------------------------------------------------------------------ condition */

private fun scoreColor(score: Int) = when {
    score >= 75 -> VizColors.good
    score >= 55 -> VizColors.warning
    else -> VizColors.critical
}

@Composable
private fun ConditionCard(state: VehicleState, alerts: List<CarAlert>, modifier: Modifier) {
    val report = Insights.health(state)
    val crit = alerts.count { it.severity == Severity.CRITICAL }
    val warn = alerts.count { it.severity == Severity.WARNING }
    val info = alerts.count { it.severity == Severity.INFO }
    val score = (report.score - crit * 15 - warn * 5).coerceIn(0, 100)
    Card(modifier) {
        Text("Car condition", color = Muted, fontSize = 12.sp)
        // Verdict on its own line so it never wraps mid-word in a narrow card.
        Text(
            when { crit > 0 -> "Needs attention"; warn > 0 -> "Keep an eye on it"; else -> report.label },
            color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        @Composable
        fun Ring() = Box(Modifier.size(78.dp), contentAlignment = Alignment.Center) {
            ScoreRing(score, scoreColor(score), Modifier.size(78.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$score", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                Text("/ 100", color = Faint, fontSize = 10.sp)
            }
        }
        @Composable
        fun Counts() = Column {
            CountChip(Icons.Filled.Error, VizColors.critical, "$crit critical")
            CountChip(Icons.Filled.Warning, VizColors.warning, "$warn warning")
            CountChip(Icons.Filled.Info, VizColors.inkSecondary, "$info note${if (info == 1) "" else "s"}")
        }
        if (LocalNarrow.current) {
            Column(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Ring()
                Spacer(Modifier.height(10.dp))
                Counts()
            }
        } else {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Ring()
                Spacer(Modifier.width(12.dp))
                Counts()
            }
        }
        if (state.demo) Text("Demo mode — simulated data", color = Faint, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun CountChip(icon: ImageVector, tint: Color, text: String) {
    Row(Modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, color = VizColors.inkSecondary, fontSize = 12.sp, maxLines = 1, softWrap = false)
    }
}

/* ------------------------------------------------------------------ alerts */

@Composable
private fun AlertsCard(alerts: List<CarAlert>, modifier: Modifier) {
    Card(modifier) {
        Text("Alerts", color = Muted, fontSize = 12.sp)
        if (alerts.isEmpty()) {
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, tint = VizColors.good, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("All clear", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("Nothing needs attention right now.", color = Muted, fontSize = 12.sp)
                }
            }
        } else {
            Column(Modifier.padding(top = 8.dp).heightIn(max = 210.dp).verticalScroll(rememberScrollState())) {
                alerts.forEach { a -> AlertRow(a) }
            }
        }
    }
}

private fun sevIcon(s: Severity) = when (s) {
    Severity.CRITICAL -> Icons.Filled.Error to VizColors.critical
    Severity.WARNING -> Icons.Filled.Warning to VizColors.warning
    Severity.INFO -> Icons.Filled.Info to VizColors.inkSecondary
}

@Composable
private fun AlertRow(a: CarAlert) {
    var open by remember(a.title) { mutableStateOf(a.severity == Severity.CRITICAL) }
    val (icon, tint) = sevIcon(a.severity)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open = !open }.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, a.severity.label, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(a.severity.label.uppercase(), color = tint, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            Text(a.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = if (LocalNarrow.current) 2 else 1, overflow = TextOverflow.Ellipsis)
        }
        if (open) {
            Text(a.detail, color = VizColors.inkSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 24.dp, top = 3.dp))
            Text("→ ${a.action}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 24.dp, top = 2.dp))
        }
    }
}

/* ------------------------------------------------------------------ AI */

@Composable
private fun AiCard(modifier: Modifier) {
    val context = LocalContext.current
    val st by CarAnalyzer.state.collectAsState()
    Card(modifier) {
        val narrow = LocalNarrow.current
        @Composable
        fun AnalyseButton(mod: Modifier) = Text(
            if (st is CarAnalyzer.State.Working) "Analysing…" else if (st is CarAnalyzer.State.Done) "Re-analyse" else "Analyse car",
            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = mod.clip(RoundedCornerShape(12.dp)).background(Accent)
                .clickable(enabled = st !is CarAnalyzer.State.Working) { CarAnalyzer.analyze(context) }
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AutoAwesome, null, tint = Accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("AI analysis", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            if (!narrow) AnalyseButton(Modifier)
        }
        if (narrow) AnalyseButton(Modifier.padding(top = 8.dp).fillMaxWidth())
        when (val s = st) {
            CarAnalyzer.State.Idle -> Text(
                "A mechanic's read of every live value, your session's min/avg/max, alerts, fault codes and readiness — " +
                    "using the AI provider set in Setup.",
                color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp),
            )
            CarAnalyzer.State.Working -> Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("Reading the car's data…", color = Muted, fontSize = 13.sp)
            }
            is CarAnalyzer.State.Failed -> Text(s.message, color = VizColors.serious, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
            is CarAnalyzer.State.Done -> {
                val r = s.result
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    r.score?.let { sc ->
                        Text("$sc", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                        Text("/100", color = Faint, fontSize = 11.sp, modifier = Modifier.padding(start = 2.dp, end = 10.dp, top = 6.dp))
                    }
                    Text(r.verdict, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
                if (r.summary.isNotBlank()) Text(r.summary, color = VizColors.inkSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                Column(Modifier.padding(top = 6.dp).heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                    r.findings.forEach { f ->
                        val (icon, tint) = when (f.severity) {
                            "critical" -> Icons.Filled.Error to VizColors.critical
                            "warning" -> Icons.Filled.Warning to VizColors.warning
                            "good" -> Icons.Filled.CheckCircle to VizColors.good
                            else -> Icons.Filled.Info to VizColors.inkSecondary
                        }
                        Row(Modifier.padding(vertical = 4.dp)) {
                            Icon(icon, f.severity, tint = tint, modifier = Modifier.size(15.dp).padding(top = 1.dp))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("${f.system} · ${f.title}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                if (f.detail.isNotBlank()) Text(f.detail, color = VizColors.inkSecondary, fontSize = 11.sp)
                                if (f.action.isNotBlank()) Text("→ ${f.action}", color = Muted, fontSize = 11.sp)
                            }
                        }
                    }
                }
                Text("by ${r.provider}", color = Faint, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/* ------------------------------------------------------------------ gauges */

private data class GaugeSpec(val pid: Pid, val label: String, val min: Double, val max: Double, val warn: Double? = null, val crit: Double? = null, val lowCrit: Double? = null)

private val GAUGES = listOf(
    GaugeSpec(Pid.ENGINE_RPM, "RPM", 0.0, 7000.0, warn = 5500.0, crit = 6200.0),
    GaugeSpec(Pid.SPEED, "Speed", 0.0, 200.0),
    GaugeSpec(Pid.ENGINE_LOAD, "Load", 0.0, 100.0),
    GaugeSpec(Pid.THROTTLE, "Throttle", 0.0, 100.0),
    GaugeSpec(Pid.COOLANT_TEMP, "Coolant", 40.0, 130.0, warn = 104.0, crit = 110.0),
    GaugeSpec(Pid.MODULE_VOLTAGE, "Battery", 10.0, 16.0, warn = 15.0, crit = 15.3, lowCrit = 12.0),
)

@Composable
private fun GaugesRow(state: VehicleState, prefs: UiPrefs, supported: Set<Pid>, wide: Boolean) {
    val list = GAUGES.filter { it.pid in supported }
    val narrow = LocalNarrow.current
    val rows = if (wide) listOf(list) else list.chunked(if (narrow) 2 else 3)
    Column(verticalArrangement = Arrangement.spacedBy(if (narrow) 8.dp else 12.dp)) {
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(if (narrow) 8.dp else 12.dp)) {
                r.forEach { g -> GaugeTile(g, state, prefs, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

@Composable
private fun GaugeTile(g: GaugeSpec, state: VehicleState, prefs: UiPrefs, modifier: Modifier) {
    val v = state.value(g.pid)
    val zones = buildList {
        if (g.warn != null) add(GaugeZone(g.warn, g.crit ?: g.max, VizColors.warning))
        if (g.crit != null) add(GaugeZone(g.crit, g.max, VizColors.critical))
        if (g.lowCrit != null) add(GaugeZone(g.min, g.lowCrit, VizColors.critical))
    }
    val status = when {
        v == null -> null
        (g.crit != null && v >= g.crit) || (g.lowCrit != null && v <= g.lowCrit) -> "HIGH".takeIf { g.crit != null && v >= g.crit } ?: "LOW"
        g.warn != null && v >= g.warn -> "HIGH"
        else -> null
    }
    // Gauge scale in display units for speed (mph) — other gauges are drawn in their raw unit.
    val (gv, gmin, gmax) = if (g.pid == Pid.SPEED && prefs.imperial) Triple(v?.let { Units.kmhToMph(it) }, 0.0, 130.0) else Triple(v, g.min, g.max)
    Column(
        modifier.clip(CardShape).background(Surface1).padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
            ArcGauge(gv, gmin, gmax, Modifier.fillMaxWidth().height(80.dp), zones = if (g.pid == Pid.SPEED) emptyList() else zones)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 10.dp)) {
                Text(v?.let { Fmt.reading(g.pid, it, prefs) } ?: "--", color = Color.White, fontSize = if (LocalNarrow.current) 18.sp else 22.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(Fmt.unit(g.pid, prefs), color = Faint, fontSize = 10.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(g.label, color = VizColors.inkSecondary, fontSize = 12.sp, maxLines = 1)
            if (status != null) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Filled.Warning, null, tint = VizColors.warning, modifier = Modifier.size(12.dp))
                Text(status, color = VizColors.warning, fontSize = 10.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 2.dp))
            }
        }
        // Healthy range for this reading.
        normalText(g.pid, prefs)?.let {
            if (LocalNarrow.current) {
                Text("normal", color = Faint, fontSize = 9.sp)
                Text(it, color = Faint, fontSize = 10.sp, maxLines = 1, softWrap = false, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            } else {
                Text("normal $it", color = Faint, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/* ------------------------------------------------------------------ charts */

@Composable
private fun ChartCard(spec: ChartSpec, state: VehicleState, prefs: UiPrefs, windowS: Float, now: Float, modifier: Modifier) {
    val unit = Fmt.unit(spec.pids.first(), prefs)
    val series = spec.pids.mapIndexed { i, p ->
        val pts = SensorHistory.window(p, windowS).map { Sample(it.t, Fmt.displayValue(p, it.v.toDouble(), prefs).toFloat()) }
        ChartSeries(p.label, pts, VizColors.series[i % VizColors.series.size])
    }
    // Shaded band: the chart's own band, else the parameter's normal range (single-series charts).
    val first = spec.pids.first()
    val rawBand = spec.band ?: if (spec.pids.size == 1) NormalRanges.of(first)?.let { it.low..it.high } else null
    val band = rawBand?.let { b ->
        if (first.isTemperature && prefs.tempF) Units.cToF(b.start)..Units.cToF(b.endInclusive) else b
    }
    val bandLabel = if (spec.band != null) spec.bandLabel else "normal"
    Card(modifier) {
        Text(spec.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        if (series.size >= 2) {
            ChartLegend(spec.pids.mapIndexed { i, p ->
                Triple(VizColors.series[i], p.label, state.value(p)?.let { "${Fmt.reading(p, it, prefs)} ${Fmt.unit(p, prefs)}".trim() } ?: "--")
            })
        } else {
            Text(state.value(first)?.let { "now ${Fmt.reading(first, it, prefs)} ${Fmt.unit(first, prefs)}".trim() } ?: "--", color = VizColors.inkPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        // Min / max of what the chart is showing, and the healthy range, per series.
        spec.pids.forEachIndexed { i, p ->
            val raw = SensorHistory.window(p, windowS) // raw metric values; Fmt converts to display units
            val window = if (raw.isEmpty()) "--" else
                "min ${Fmt.reading(p, raw.minOf { it.v }.toDouble(), prefs)} · max ${Fmt.reading(p, raw.maxOf { it.v }.toDouble(), prefs)}"
            Text(
                buildString {
                    if (series.size >= 2) append("${p.label}: ")
                    append(window)
                    normalText(p, prefs)?.let { append("  ·  normal $it") }
                    NormalRanges.of(p)?.note?.let { append(" ($it)") }
                },
                color = Muted, fontSize = 11.sp, maxLines = if (LocalNarrow.current) 3 else 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        TimeChart(
            series, unit, windowS, now, Modifier.fillMaxWidth().height(if (LocalNarrow.current) 120.dp else 150.dp),
            band = band, bandLabel = bandLabel, zeroLine = spec.zeroLine,
            bandExpandsAxis = spec.band != null, // automatic normal bands shouldn't squash the line
        )
    }
}

/* ------------------------------------------------------------------ readiness */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadinessCard(state: VehicleState) {
    val r = state.readiness
    Card(Modifier.fillMaxWidth()) {
        val (icon, tint, label) = if (state.milOn) Triple(Icons.Filled.Error, VizColors.critical, "Check-engine light ON")
        else Triple(Icons.Filled.CheckCircle, VizColors.good, "Check-engine light off")
        if (LocalNarrow.current) {
            Text("Emissions readiness (MOT)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp))
                Text(label, color = VizColors.inkSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 5.dp))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Emissions readiness (MOT)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp))
                Text(label, color = VizColors.inkSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 5.dp))
            }
        }
        if (r == null) {
            Text("Your car hasn't reported its self-test status yet.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        } else {
            Text(
                if (r.incomplete.isEmpty()) "All ${r.monitors.size} self-tests complete — ready for an emissions test."
                else "${r.incomplete.size} of ${r.monitors.size} self-tests still running. They complete with normal mixed driving.",
                color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
            )
            FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                r.monitors.forEach { m ->
                    Row(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(Tile).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (m.complete) Icons.Filled.CheckCircle else Icons.Filled.HourglassTop, null,
                            tint = if (m.complete) VizColors.good else VizColors.warning, modifier = Modifier.size(14.dp),
                        )
                        Text(m.name, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
                        Text(if (m.complete) " · ready" else " · not yet", color = Muted, fontSize = 11.sp)
                    }
                }
            }
        }
        Text(
            "VIN: ${state.vin ?: if (state.demo) "— (demo)" else "not reported"}",
            color = Faint, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/* ------------------------------------------------------------------ parameter table */

@Composable
private fun ParamGroup(
    title: String,
    pids: List<Pid>,
    state: VehicleState,
    prefs: UiPrefs,
    pinned: List<Pid>,
    onTogglePin: (Pid) -> Unit,
    windowS: Float,
    wide: Boolean,
) {
    Column(Modifier.fillMaxWidth().clip(CardShape).background(Surface1)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), color = Faint, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (wide) {
                Text("NORMAL", color = Faint, fontSize = 10.sp, modifier = Modifier.width(96.dp))
                listOf("NOW", "MIN", "AVG", "MAX").forEach {
                    Text(it, color = Faint, fontSize = 10.sp, modifier = Modifier.width(66.dp))
                }
                Spacer(Modifier.width(96.dp + 34.dp))
            }
        }
        pids.forEachIndexed { i, p ->
            if (i > 0) Box(Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(1.dp).background(Hairline))
            ParamRow(p, state, prefs, p in pinned, { onTogglePin(p) }, windowS, wide)
        }
    }
}

@Composable
private fun ParamRow(p: Pid, state: VehicleState, prefs: UiPrefs, isPinned: Boolean, onPin: () -> Unit, windowS: Float, wide: Boolean) {
    val v = state.value(p)
    val st = SensorHistory.stats(p)
    val unit = Fmt.unit(p, prefs)
    fun f(x: Double?) = x?.let { Fmt.reading(p, it, prefs) } ?: "--"
    val narrow = LocalNarrow.current
    if (narrow) {
        // Narrow strip: name · value · star on top, the full-width details line underneath.
        val off = outOfRange(p, v)
        Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    p.label, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (off) Icon(Icons.Filled.Warning, "Outside normal range", tint = VizColors.warning, modifier = Modifier.size(12.dp).padding(end = 2.dp))
                Text("${f(v)} $unit".trim(), color = if (off) VizColors.warning else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onPin), contentAlignment = Alignment.Center) {
                    Icon(
                        if (isPinned) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        if (isPinned) "Unpin" else "Pin",
                        tint = if (isPinned) Accent else Faint,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
            val details = listOfNotNull(
                normalText(p, prefs)?.let { "normal $it" },
                NormalRanges.of(p)?.note,
                st?.let { "min ${f(it.min)} · avg ${f(it.avg)} · max ${f(it.max)}" },
            )
            details.forEach { Text(it, color = Faint, fontSize = 10.sp, modifier = Modifier.padding(end = 8.dp)) }
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(start = if (narrow) 10.dp else 14.dp, end = if (narrow) 2.dp else 14.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        val normal = normalText(p, prefs)
        val off = outOfRange(p, v)
        Column(Modifier.weight(1f)) {
            Text(p.label, color = Color.White, fontSize = 13.sp)
            NormalRanges.of(p)?.note?.let { Text(it, color = Faint, fontSize = 10.sp) }
            if (!wide) {
                Text(
                    buildString {
                        normal?.let { append("normal $it") }
                        if (st != null) { if (isNotEmpty()) append("  ·  "); append("min ${f(st.min)} · avg ${f(st.avg)} · max ${f(st.max)}") }
                    },
                    color = Faint, fontSize = 10.sp,
                )
            }
        }
        // Current value — flagged (icon + colour) when outside the normal range.
        @Composable
        fun Now(mod: Modifier) = Row(mod, verticalAlignment = Alignment.CenterVertically) {
            if (off) Icon(Icons.Filled.Warning, "Outside normal range", tint = VizColors.warning, modifier = Modifier.size(12.dp).padding(end = 2.dp))
            Text("${f(v)} $unit".trim(), color = if (off) VizColors.warning else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        if (wide) {
            Text(normal ?: "—", color = Muted, fontSize = 12.sp, modifier = Modifier.width(96.dp))
            Now(Modifier.width(66.dp))
            Text(f(st?.min), color = Muted, fontSize = 12.sp, modifier = Modifier.width(66.dp))
            Text(f(st?.avg), color = Muted, fontSize = 12.sp, modifier = Modifier.width(66.dp))
            Text(f(st?.max), color = Muted, fontSize = 12.sp, modifier = Modifier.width(66.dp))
        } else {
            Now(Modifier.padding(end = if (narrow) 2.dp else 8.dp))
        }
        if (!narrow) Sparkline(SensorHistory.window(p, windowS), Modifier.width(if (wide) 96.dp else 64.dp).height(22.dp))
        Box(Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onPin), contentAlignment = Alignment.Center) {
            Icon(
                if (isPinned) Icons.Filled.Star else Icons.Outlined.StarBorder,
                if (isPinned) "Unpin" else "Pin",
                tint = if (isPinned) Accent else Faint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/* ------------------------------------------------------------------ bits */

@Composable
private fun Card(modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.clip(CardShape).background(Surface1).padding(if (LocalNarrow.current) 10.dp else 14.dp)) { content() }
}
