package com.cardash.dashboard.ai

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.cardash.dashboard.dashcam.DashcamEngine
import com.cardash.dashboard.insights.Insights
import com.cardash.dashboard.music.MusicController
import com.cardash.dashboard.nav.NavManager
import com.cardash.dashboard.nav.SpeedLimitManager
import com.cardash.dashboard.obd.GearReading
import com.cardash.dashboard.obd.ObdManager
import com.cardash.dashboard.obd.Pid
import com.cardash.dashboard.ui.Fmt
import com.cardash.dashboard.ui.UiPrefs
import org.json.JSONObject

/** Provider-neutral tool definition (JSON-schema properties). */
data class AiTool(
    val name: String,
    val description: String,
    val properties: Map<String, Map<String, Any>> = emptyMap(),
    val required: List<String> = emptyList(),
)

/** What the assistant can do in the car, and the code that does it. */
class AssistantTools(private val context: Context) {

    val all = listOf(
        AiTool(
            "navigate_to",
            "Start turn-by-turn driving directions in the Google Maps app (it opens beside the dashboard). " +
                "Destination: an address, a business or place name, a postcode, or \"home\" / \"work\" (saved in Setup). " +
                "For \"the nearest X\" pass e.g. \"nearest petrol station\" and Google Maps picks it.",
            mapOf("destination" to mapOf("type" to "string", "description" to "Where to go")),
            listOf("destination"),
        ),
        AiTool(
            "find_nearby",
            "Show places near the car in the Google Maps app (e.g. \"petrol station\", \"parking\", \"EV charger\"). " +
                "The driver picks one there; you can't read the results back.",
            mapOf("query" to mapOf("type" to "string", "description" to "What to look for")),
            listOf("query"),
        ),
        AiTool(
            "dashcam",
            "Control the dashcam (front + rear cameras with the speed overlay burned in).",
            mapOf("action" to mapOf("type" to "string", "enum" to listOf("start", "stop", "status"))),
            listOf("action"),
        ),
        AiTool(
            "music",
            "Control music in the phone's music app (YouTube Music, Spotify…): play/resume, pause, next, previous, " +
                "status (what's playing), or search_and_play an artist, song, album, playlist or genre.",
            mapOf(
                "action" to mapOf("type" to "string", "enum" to listOf("play", "pause", "next", "previous", "status", "search_and_play")),
                "query" to mapOf("type" to "string", "description" to "What to play, for search_and_play"),
            ),
            listOf("action"),
        ),
        AiTool(
            "car_status",
            "Live car data from the OBD-II adapter (speed, RPM, gear, temperatures, fuel, battery voltage, fuel trims, " +
                "economy, trip), the health check, stored fault codes, the speed limit, and the phone's battery.",
        ),
    )

    suspend fun execute(name: String, argsJson: String): String = try {
        val a = runCatching { JSONObject(argsJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        when (name) {
            "navigate_to" -> navigateTo(a.optString("destination"))
            "find_nearby" -> findNearby(a.optString("query"))
            "dashcam" -> dashcam(a.optString("action"))
            "car_status" -> carStatus()
            "music" -> music(a.optString("action"), a.optString("query"))
            else -> "Unknown tool $name"
        }
    } catch (e: Exception) {
        "Error: ${e.message ?: e.javaClass.simpleName}"
    }

    // ------------------------------------------------------------ actions (also used by offline phrases)

    fun navigateTo(dest: String): String {
        val d = dest.trim()
        if (d.isEmpty()) return "No destination given."
        if (!NavManager.googleMapsInstalled()) return "Google Maps isn't installed on this phone."
        val (target, label) = when (d.lowercase()) {
            "home" -> NavManager.home.value.ifBlank { return "Home isn't set yet. Add your home address in Setup." } to "home"
            "work" -> NavManager.work.value.ifBlank { return "Work isn't set yet. Add your work address in Setup." } to "work"
            else -> d to d
        }
        return if (NavManager.navigate(target)) "Starting directions to $label in Google Maps."
        else "I couldn't open Google Maps."
    }

    fun findNearby(query: String): String {
        val q = query.trim().ifEmpty { return "Nothing to search for." }
        if (!NavManager.googleMapsInstalled()) return "Google Maps isn't installed on this phone."
        return if (NavManager.searchNearby(q)) "Showing $q near you in Google Maps." else "I couldn't open Google Maps."
    }

    fun music(action: String, query: String = ""): String {
        val m = MusicController
        if (action != "search_and_play" && action != "play" && !m.hasAccess()) return m.status()
        return when (action) {
            "play" -> if (query.isNotBlank()) m.playSearch(query) else { m.play(); "Resuming music." }
            "pause" -> { m.pause(); "Music paused." }
            "next" -> { m.next(); "Next track." }
            "previous" -> { m.previous(); "Previous track." }
            "search_and_play" -> m.playSearch(query)
            else -> m.status()
        }
    }

    fun dashcam(action: String): String {
        val st = DashcamEngine.state.value
        return when (action) {
            "start" -> { DashcamEngine.startNow(); "Dashcam recording started." }
            "stop" -> { DashcamEngine.stopNow(); "Dashcam recording stopped and saved." }
            else -> if (st.recording) "Recording now (${if (st.dualActive) "front and back" else "back camera"})." else
                "Not recording. ${st.clipCount} clips saved."
        }
    }

    fun carStatus(): String {
        val s = ObdManager.state.value
        val ui = prefs()
        val o = JSONObject()
        o.put("connection", when {
            s.demo -> "DEMO MODE (simulated data, not the real car)"
            s.connected -> "connected to the car"
            else -> "not connected to the car"
        })
        val readings = JSONObject()
        Pid.entries.forEach { p -> s.value(p)?.let { readings.put(p.label, Fmt.reading(p, it, ui) + " " + Fmt.unit(p, ui)) } }
        o.put("readings", readings)
        o.put("gear", when (val g = s.gear) { is GearReading.Engaged -> g.gear.toString(); GearReading.Neutral -> "neutral"; else -> "unknown" })
        val h = Insights.health(s)
        o.put("health", "${h.label} (${h.score}/100)")
        o.put("health_checks", h.checks.joinToString("; ") { "${it.name}: ${it.status} — ${it.detail}" })
        o.put("fault_codes", if (s.faults.isEmpty()) "none" else s.faults.joinToString("; ") { "${it.code} ${it.description} (${it.status})" })
        o.put("check_engine_light", s.milOn)
        o.put("trip", "${"%.1f".format(Fmt.distValue(s.trip.distanceKm, ui.imperial))} ${Fmt.distUnit(ui.imperial)}, " +
            "avg ${Fmt.speedValue(s.trip.avgSpeedKmh, ui.imperial)} ${Fmt.speedUnit(ui.imperial)}, " +
            "economy now ${Fmt.economy(s.trip.instantL100, ui.imperial)} ${Fmt.economyUnit(ui.imperial)}")
        SpeedLimitManager.limit.value?.let { o.put("speed_limit", "${if (ui.imperial) Math.round(it.kmh / 1.609344 / 5.0) * 5 else it.kmh} ${Fmt.speedUnit(ui.imperial)}") }
        phoneBattery()?.let { o.put("phone_battery", it) }
        o.put("vehicle", "2012 Vauxhall Meriva B, petrol, manual, ~54 L tank")
        return o.toString()
    }

    private fun phoneBattery(): String? {
        val i: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val pct = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10
        return "$pct%, ${t}°C"
    }

    fun prefs(): UiPrefs {
        val p = context.getSharedPreferences("cardash", Context.MODE_PRIVATE)
        return UiPrefs(imperial = p.getBoolean("imperial", true), tempF = p.getBoolean("tempF", false))
    }
}
