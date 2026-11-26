package com.cardash.dashboard.web

import android.content.Context
import android.util.Log
import com.cardash.dashboard.ai.VoiceAssistant
import com.cardash.dashboard.ai.providerFor
import com.cardash.dashboard.data.DriveLog
import com.cardash.dashboard.insights.NormalRanges
import com.cardash.dashboard.nav.NavManager
import com.cardash.dashboard.obd.GearReading
import com.cardash.dashboard.obd.ObdManager
import com.cardash.dashboard.obd.Pid
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * "Ride replay" web page served by the phone on the local network (port 8080).
 * Open http://<phone-ip>:8080/?k=<code> on a laptop on the same Wi-Fi to replay recorded rides
 * (map, timeline, charts, events) and ask the AI about them. Every request needs the access
 * code shown in Setup, so other people on the network can't read the car's data or use the AI key.
 */
object RideServer {
    private const val TAG = "RideServer"
    const val PORT = 8080

    /** The page's 3D car (.glb) for Google's 3D map, served at /car-model-<version>.glb. */
    @Volatile private var carModel: ByteArray? = null
    @Volatile private var carModelVersion = 0
    private val CAR_GLB = Regex("""/car-model-\d{1,9}\.glb""")

    private lateinit var app: Context
    private var initialised = false
    private var server: Http? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val prefs get() = app.getSharedPreferences("cardash_web", Context.MODE_PRIVATE)

    /** Access code required on every request. */
    val code: String
        get() = prefs.getString("code", null) ?: newCode().also { prefs.edit().putString("code", it).apply() }

    private fun newCode(): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val r = SecureRandom()
        return (1..8).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    fun init(context: Context) {
        if (initialised) return
        initialised = true
        app = context.applicationContext
        if (prefs.getBoolean("enabled", false)) start()
    }

    fun setEnabled(v: Boolean) {
        prefs.edit().putBoolean("enabled", v).apply()
        if (v) start() else stop()
    }

    fun newAccessCode() {
        prefs.edit().putString("code", newCode()).apply()
    }

    private fun start() {
        if (server != null) return
        try {
            server = Http().also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
            _running.value = true
            _error.value = null
            Log.i(TAG, "Serving on port $PORT")
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't start", e)
            server = null
            _running.value = false
            _error.value = e.message ?: "Couldn't start the web page"
        }
    }

    private fun stop() {
        runCatching { server?.stop() }
        server = null
        _running.value = false
    }

    /** Addresses a laptop on the same network can use (Wi-Fi, or the phone's own hotspot). */
    fun urls(): List<String> = addresses().map { it.second }

    /**
     * (label, link) for each way to reach the page: "Wi-Fi", "Hotspot", "Tailscale" (the phone's
     * tailnet address, reachable from the user's own devices anywhere) or "Network".
     */
    fun addresses(): List<Pair<String, String>> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { ni.name to it } }
            .mapNotNull { (n, a) ->
                val b = a.address
                val tailnet = (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xFF) in 64..127
                val label = when {
                    tailnet -> "Tailscale"
                    !a.isSiteLocalAddress -> return@mapNotNull null
                    n.startsWith("wlan") -> "Wi-Fi"
                    n.startsWith("ap") || n.startsWith("swlan") -> "Hotspot"
                    else -> "Network"
                }
                label to "http://${a.hostAddress}:$PORT/?k=$code"
            }
            .sortedBy { (label, _) -> listOf("Wi-Fi", "Hotspot", "Network", "Tailscale").indexOf(label) }
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------------ HTTP

    private class Http : NanoHTTPD(PORT) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            // The 3D car for Google's photorealistic map. Google only loads models from the page's own site,
            // so the page uploads it (below) and Google fetches it here. It's just the car's shape: no access code needed.
            if (session.method == Method.GET && CAR_GLB.matches(uri)) {
                val b = carModel ?: return text(Response.Status.NOT_FOUND, "text/plain", "No car model yet")
                return newFixedLengthResponse(Response.Status.OK, "model/gltf-binary", b.inputStream(), b.size.toLong())
                    .also { it.addHeader("Cache-Control", "max-age=86400") }
            }
            // The detailed 3D model of the car (made in Blender from photos of it), which the page loads and recolours.
            if (session.method == Method.GET && uri == "/meriva.glb") {
                val b = merivaModel ?: return text(Response.Status.NOT_FOUND, "text/plain", "No model")
                return newFixedLengthResponse(Response.Status.OK, "model/gltf-binary", b.inputStream(), b.size.toLong())
                    .also { it.addHeader("Cache-Control", "max-age=86400") }
            }
            val key = session.parameters["k"]?.firstOrNull() ?: session.headers["x-key"]
            if (key != code) {
                return text(Response.Status.FORBIDDEN, "text/html",
                    "<html><body style='font-family:sans-serif;background:#111;color:#eee;padding:40px'>" +
                        "<h2>Car Dashboard</h2><p>Open the link shown in the app (Setup → Ride replay web page). " +
                        "It includes the access code.</p></body></html>")
            }
            return try {
                when {
                    uri == "/" || uri == "/index.html" -> asset("web/index.html", "text/html")
                    uri == "/api/days" -> json(days())
                    uri == "/api/meta" -> json(meta())
                    uri == "/api/live" -> json(live())
                    uri == "/api/day" -> day(session.parameters["f"]?.firstOrNull().orEmpty())
                    uri == "/api/ask" && session.method == Method.POST -> ask(session)
                    uri == "/api/carmodel" && session.method == Method.POST -> carModelUpload(session)
                    else -> text(Response.Status.NOT_FOUND, "text/plain", "Not found")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Request failed: $uri", e)
                text(Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "Error")
            }
        }

        private val merivaModel: ByteArray? by lazy {
            runCatching { app.assets.open("web/meriva.glb").use { it.readBytes() } }.getOrNull()
        }

        private fun text(status: Response.Status, mime: String, body: String) =
            newFixedLengthResponse(status, "$mime; charset=utf-8", body).also { it.addHeader("Cache-Control", "no-store") }

        private fun json(o: Any) = text(Response.Status.OK, "application/json", o.toString())

        private fun asset(path: String, mime: String): Response {
            val bytes = app.assets.open(path).use { it.readBytes() }
            return newFixedLengthResponse(Response.Status.OK, "$mime; charset=utf-8", bytes.inputStream(), bytes.size.toLong())
                .also { it.addHeader("Cache-Control", "no-store") }
        }

        private fun day(name: String): Response {
            DriveLog.flush()
            val f = DriveLog.file(name) ?: return text(Response.Status.NOT_FOUND, "text/plain", "No such day")
            DriveLog.upgradeHeader(f)
            return newFixedLengthResponse(Response.Status.OK, "text/csv; charset=utf-8", f.inputStream(), f.length())
                .also { it.addHeader("Cache-Control", "no-store") }
        }

        /** Keep the page's .glb car (raw body, at most 4 MB) and tell it where Google can fetch it. */
        private fun carModelUpload(session: IHTTPSession): Response {
            val len = session.headers["content-length"]?.toIntOrNull()
                ?: return text(Response.Status.BAD_REQUEST, "text/plain", "Missing length")
            if (len < 12 || len > 4_000_000) return text(Response.Status.BAD_REQUEST, "text/plain", "Bad size")
            val buf = ByteArray(len)
            var off = 0
            val input = session.inputStream
            while (off < len) {
                val n = input.read(buf, off, len - off)
                if (n < 0) break
                off += n
            }
            // A binary glTF starts with the magic "glTF".
            if (off != len || buf[0] != 'g'.code.toByte() || buf[1] != 'l'.code.toByte() || buf[2] != 'T'.code.toByte() || buf[3] != 'F'.code.toByte()) {
                return text(Response.Status.BAD_REQUEST, "text/plain", "Not a .glb model")
            }
            carModel = buf
            val v = ++carModelVersion
            return json(JSONObject().put("url", "/car-model-$v.glb"))
        }

        private fun ask(session: IHTTPSession): Response {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val body = JSONObject(files["postData"] ?: "{}")
            val answer = runBlocking { askAi(body) }
            return json(JSONObject().put("answer", answer))
        }
    }

    // ------------------------------------------------------------------ API bodies

    private fun days() = JSONArray().apply {
        DriveLog.days().forEach { d ->
            put(JSONObject().put("file", d.file).put("day", d.day).put("demo", d.demo).put("bytes", d.bytes))
        }
    }

    /** Column metadata so the page can label values and flag readings outside the normal range. */
    private fun meta() = JSONObject().apply {
        val cols = JSONArray()
        Pid.entries.forEach { p ->
            val o = JSONObject()
                .put("key", p.name).put("label", p.label).put("unit", p.unit)
                .put("group", p.group.label).put("decimals", p.decimals).put("temp", p.isTemperature)
            NormalRanges.of(p)?.let { r ->
                o.put("low", r.low).put("high", r.high)
                r.note?.let { o.put("note", it) }
            }
            cols.put(o)
        }
        put("pids", cols)
        put("vehicle", "2012 Vauxhall Meriva B · petrol · manual")
        // The page draws Google's map, satellite and 3D views with the driver's own Google Maps key, saved in Setup.
        // Only requests that carry the access code get here, so the key goes to the driver's own devices only.
        // No key: the page uses OpenStreetMap.
        val key = com.cardash.dashboard.data.MapsKey.saved(app)
        if (key.isNotBlank()) put("mapsKey", key)
        val p = app.getSharedPreferences("cardash", Context.MODE_PRIVATE)
        put("imperial", p.getBoolean("imperial", true))
        put("tempF", p.getBoolean("tempF", false))
    }

    private fun live() = JSONObject().apply {
        val s = ObdManager.state.value
        put("connected", s.connected)
        put("demo", s.demo)
        put("t", System.currentTimeMillis())
        // Demo mode drives a simulated loop, so the page has a moving car to follow.
        val demoFix = if (s.demo) com.cardash.dashboard.obd.DemoGps.fix else null
        if (demoFix != null) put("lat", demoFix.lat).put("lng", demoFix.lng).put("heading", demoFix.bearing).put("alt", demoFix.altitude)
        // Only a fresh GPS position (an old one would show the car parked while it drives on).
        else NavManager.here.value?.takeIf { android.os.SystemClock.elapsedRealtime() - NavManager.gpsFixAt <= 10_000 }?.let {
            put("lat", it.latitude).put("lng", it.longitude)
            NavManager.gpsAltitudeMsl?.let { a -> put("alt", a) }
        }
        put("gear", when (val g = s.gear) { is GearReading.Engaged -> g.gear.toString(); GearReading.Neutral -> "N"; else -> "" })
        put("mil", s.milOn)
        put("dtc", JSONArray(s.faults.map { "${it.code} ${it.description}" }))
        put("readings", JSONObject().apply { s.readings.forEach { (p, v) -> put(p.name, v) } })
        // The trip so far (kept by the phone since it connected to the car), for the page's summary card.
        if (s.connected) put("trip", JSONObject()
            .put("km", s.trip.distanceKm).put("sec", s.trip.durationSec).put("avgKmh", s.trip.avgSpeedKmh)
            .put("maxKmh", s.trip.maxSpeedKmh).put("fuelL", s.trip.fuelUsedL))
    }

    /** Summarise the requested slice of a recorded day and ask the configured AI about it. */
    private suspend fun askAi(req: JSONObject): String {
        val settings = runCatching { VoiceAssistant.settings }.getOrNull() ?: return "The AI isn't ready yet — open the app first."
        val provider = providerFor(settings) ?: return "No AI provider is set up. Add an API key in the app: Setup → Voice assistant."
        // One day file, or several when the page plays back "Last 7 days".
        val names = req.optJSONArray("files")?.let { a -> (0 until minOf(a.length(), 31)).map { a.optString(it) } }
            ?.takeIf { it.isNotEmpty() } ?: listOf(req.optString("file"))
        val files = names.mapNotNull { DriveLog.file(it) }.distinctBy { it.name }.sortedBy { it.name.removePrefix("demo-") }
        if (files.isEmpty()) return "That day's recording wasn't found."
        val from = req.optLong("from", 0L)
        val to = req.optLong("to", Long.MAX_VALUE)
        val question = req.optString("question").ifBlank { "Analyse this ride: how is the car doing, and is anything wrong?" }
        val summary = RideSummary.build(files, from, to)
        val system = """
            You are an experienced car mechanic reviewing a recorded ride from the driver's OBD-II logger.
            Car: 2012 Vauxhall Meriva B, petrol, manual, UK. Values are metric as reported by the car (km/h, °C, kPa, g/s, %, V).
            You get per-sensor statistics (min / avg / max, time outside the normal range), events, fault codes and a
            30-second timeline. Answer the driver's question directly in plain English. Point to times (HH:MM:SS) and
            readings as evidence. Say how confident you are, what to check next, and when to see a garage. Use short
            paragraphs or a few bullets; no tables. If the data can't answer the question, say so.
        """.trimIndent()
        return runCatching { provider.complete(system, "Question: $question\n\nRide data:\n$summary") }
            .getOrElse { "The AI couldn't answer: ${it.message}" }
    }
}
