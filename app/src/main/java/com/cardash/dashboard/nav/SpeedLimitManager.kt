package com.cardash.dashboard.nav

import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.roundToInt

/** A posted speed limit for the current road. */
data class SpeedLimit(val kmh: Int, val mph: Boolean)

/**
 * Looks up the posted speed limit for the current location from OpenStreetMap
 * (Overpass API, free, no key). Throttled so it queries at most every ~15s or
 * after moving ~50 m. Fails quietly when a road has no maxspeed tag.
 */
object SpeedLimitManager {

    private val _limit = MutableStateFlow<SpeedLimit?>(null)
    val limit: StateFlow<SpeedLimit?> = _limit.asStateFlow()

    private var lastLoc: LatLng? = null
    private var lastTime = 0L
    @Volatile private var inFlight = false

    fun update(scope: CoroutineScope, loc: LatLng) {
        if (inFlight) return
        val now = System.currentTimeMillis()
        val moved = lastLoc?.let { GeoUtils.distance(it, loc) } ?: Double.MAX_VALUE
        if (now - lastTime < 15000 && moved < 50) return
        inFlight = true
        lastLoc = loc
        lastTime = now
        scope.launch(Dispatchers.IO) {
            try {
                val query = "[out:json];way(around:30,${loc.latitude},${loc.longitude})[\"maxspeed\"];out tags 1;"
                val url = "https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(query, "UTF-8")
                val resp = Http.get(url, mapOf("User-Agent" to "CarDashboard/1.0"))
                if (resp.ok) {
                    val elements = JSONObject(resp.body).optJSONArray("elements")
                    val tag = if (elements != null && elements.length() > 0) {
                        elements.getJSONObject(0).optJSONObject("tags")?.optString("maxspeed")
                    } else null
                    parse(tag)?.let { _limit.value = it }
                }
            } catch (_: Exception) {
                // ignore: speed limit is best-effort
            } finally {
                inFlight = false
            }
        }
    }

    private fun parse(raw: String?): SpeedLimit? {
        if (raw.isNullOrBlank()) return null
        val mph = raw.contains("mph", ignoreCase = true)
        val num = Regex("\\d+").find(raw)?.value?.toIntOrNull() ?: return null
        val kmh = if (mph) (num * 1.60934).roundToInt() else num
        return SpeedLimit(kmh, mph)
    }

    fun clear() {
        _limit.value = null
    }
}
