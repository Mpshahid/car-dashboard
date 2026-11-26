package com.cardash.dashboard.ai

import android.content.Context
import com.cardash.dashboard.insights.CarAlerts
import com.cardash.dashboard.insights.Insights
import com.cardash.dashboard.obd.ObdManager
import com.cardash.dashboard.obd.Pid
import com.cardash.dashboard.obd.SensorHistory
import com.cardash.dashboard.ui.Fmt
import com.cardash.dashboard.ui.UiPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** One point the AI makes about the car. */
data class AiFinding(val severity: String, val system: String, val title: String, val detail: String, val action: String)

data class AiAnalysis(
    val score: Int?,
    val verdict: String,
    val summary: String,
    val findings: List<AiFinding>,
    val provider: String,
    val at: Long = System.currentTimeMillis(),
)

/**
 * Sends a snapshot of the car (live readings, session min/avg/max, alerts, fault codes,
 * readiness, trip) to the chosen AI provider and parses a structured health report back.
 */
object CarAnalyzer {
    sealed interface State {
        data object Idle : State
        data object Working : State
        data class Done(val result: AiAnalysis) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    fun analyze(context: Context) {
        val settings = VoiceAssistant.settings
        val p = settings.provider.value
        val provider = providerFor(settings, p) ?: run {
            _state.value = State.Failed("Add a ${p.label} API key in Setup ▸ Voice assistant to use AI analysis.")
            return
        }
        job?.cancel()
        _state.value = State.Working
        job = scope.launch {
            _state.value = try {
                val raw = provider.complete(SYSTEM, snapshot(context).toString())
                State.Done(parse(raw, p.label))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                State.Failed(e.message ?: "Analysis failed")
            }
        }
    }

    private fun snapshot(context: Context): JSONObject {
        val s = ObdManager.state.value
        val prefsRaw = context.getSharedPreferences("cardash", Context.MODE_PRIVATE)
        val ui = UiPrefs(imperial = prefsRaw.getBoolean("imperial", true), tempF = prefsRaw.getBoolean("tempF", false))
        val o = JSONObject()
        o.put("vehicle", "2012 Vauxhall Meriva B, petrol, manual, UK")
        o.put("data_source", if (s.demo) "DEMO MODE — simulated data, not a real car" else if (s.connected) "Live OBD-II via ELM327" else "Not connected")
        s.vin?.let { o.put("vin", it) }
        val params = JSONArray()
        Pid.entries.filter { it in s.supportedPids }.forEach { p ->
            val v = s.value(p) ?: return@forEach
            val st = SensorHistory.stats(p)
            params.put(
                JSONObject()
                    .put("name", p.label)
                    .put("unit", Fmt.unit(p, ui))
                    .put("now", Fmt.reading(p, v, ui))
                    .apply {
                        if (st != null && st.count > 3) {
                            put("session_min", Fmt.reading(p, st.min, ui))
                            put("session_avg", Fmt.reading(p, st.avg, ui))
                            put("session_max", Fmt.reading(p, st.max, ui))
                        }
                    },
            )
        }
        o.put("parameters", params)
        o.put("rule_based_alerts", JSONArray(CarAlerts.evaluate(s).map { "${it.severity.label}: ${it.title} — ${it.detail}" }))
        o.put("fault_codes", JSONArray(s.faults.map { "${it.code} ${it.description} (${it.status})" }))
        o.put("check_engine_light", s.milOn)
        s.readiness?.let { r ->
            o.put("readiness_monitors", JSONArray(r.monitors.map { "${it.name}: ${if (it.complete) "complete" else "incomplete"}" }))
        }
        o.put("trip", "${"%.1f".format(Fmt.distValue(s.trip.distanceKm, ui.imperial))} ${Fmt.distUnit(ui.imperial)} in ${Fmt.duration(s.trip.durationSec)}")
        o.put("session_minutes", (SensorHistory.now() / 60).toInt())
        o.put("simple_health_score", Insights.health(s).score)
        return o
    }

    private fun parse(raw: String, provider: String): AiAnalysis {
        val json = runCatching {
            val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
            JSONObject(raw.substring(start, end + 1))
        }.getOrNull() ?: return AiAnalysis(null, "Analysis", raw.take(1200), emptyList(), provider)
        val findings = json.optJSONArray("findings")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { f ->
                    AiFinding(
                        f.optString("severity", "info").lowercase(),
                        f.optString("system"),
                        f.optString("title"),
                        f.optString("detail"),
                        f.optString("action"),
                    )
                }
            }
        }.orEmpty()
        return AiAnalysis(
            score = if (json.has("score")) json.optInt("score").coerceIn(0, 100) else null,
            verdict = json.optString("verdict", "Analysis"),
            summary = json.optString("summary"),
            findings = findings,
            provider = provider,
        )
    }

    private val SYSTEM = """
        You are an experienced car mechanic and diagnostics engineer. You receive a JSON snapshot of a car's
        OBD-II data: current values, session min/avg/max, rule-based alerts, fault codes, readiness monitors.
        Analyse the car's condition like a technician reading live data: compare values to normal ranges for a
        warm petrol engine (coolant ~85-105°C, charging 13.5-14.7 V while running, combined fuel trims within
        ±10%, front narrow-band O2 switching 0.1-0.9 V, rear O2 steady ~0.6-0.8 V, catalyst 300-850°C),
        look for relationships between parameters (e.g. trims vs MAF/MAP, O2 front vs rear for catalyst
        efficiency, voltage vs RPM), and say what is healthy too. Be specific and practical for a UK driver.
        If the data source is DEMO MODE, say clearly that the figures are simulated. Don't invent readings that
        aren't in the data. Reply with ONLY a JSON object, no prose around it:
        {"score": 0-100, "verdict": "2-4 words", "summary": "2-3 plain sentences",
         "findings": [{"severity": "critical|warning|info|good", "system": "e.g. Cooling",
                       "title": "short", "detail": "what the data shows and why it matters",
                       "action": "what to do"}]}
        Give 3-8 findings, most important first.
    """.trimIndent()
}
