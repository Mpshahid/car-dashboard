package com.cardash.dashboard.data

import android.content.Context
import android.util.Log
import com.cardash.dashboard.nav.NavManager
import com.cardash.dashboard.obd.GearReading
import com.cardash.dashboard.obd.ObdManager
import com.cardash.dashboard.obd.Pid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Ride recorder: while the car is connected, one CSV row per second with GPS + every OBD reading,
 * the gear, the check-engine light and fault codes. One file per day ("2026-11-24.csv"; demo
 * sessions go to "demo-2026-11-24.csv"). Old days are deleted after [keepDays].
 * Values are raw metric units (km/h, °C, kPa…), exactly as the car reports them.
 */
object DriveLog {
    private const val TAG = "DriveLog"
    /** A GPS fix older than this isn't logged (the phone stopped sending positions, e.g. a tunnel). */
    private const val GPS_MAX_AGE_MS = 10_000L
    private val ZONE: ZoneId get() = ZoneId.systemDefault()

    /**
     * Fixed leading columns, one column per [Pid] (by enum name), then "alt" (GPS height above sea level, m).
     * "alt" is last so rows appended to a file started by an older version just carry one unread extra value.
     */
    val COLUMNS: List<String> = listOf("t", "lat", "lng", "gps_kmh", "heading", "gear", "mil", "dtc") + Pid.entries.map { it.name } + "alt"

    private lateinit var app: Context
    private var initialised = false
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private const val K_KEEP = "keep_days_v2"   // new key: everyone starts at 7 days again
    private val _keepDays = MutableStateFlow(7)
    val keepDays: StateFlow<Int> = _keepDays.asStateFlow()
    /** Rows written this app session (for the Setup screen). */
    private val _rows = MutableStateFlow(0L)
    val rowsThisSession: StateFlow<Long> = _rows.asStateFlow()

    private var writer: BufferedWriter? = null
    private var writerName: String? = null

    val dir: File get() = File(app.filesDir, "drivelog").apply { mkdirs() }
    private val prefs get() = app.getSharedPreferences("cardash_log", Context.MODE_PRIVATE)

    fun init(context: Context) {
        if (initialised) return
        initialised = true
        app = context.applicationContext
        _enabled.value = prefs.getBoolean("enabled", true)
        // The web page plays back the last 7 days, so keep at least a week (older versions offered 1 or 3 days).
        _keepDays.value = prefs.getInt(K_KEEP, 7)
        prune()
        io.launch {
            var lastFlush = System.currentTimeMillis()
            while (true) {
                delay(1000)
                runCatching { tick() }.onFailure { Log.w(TAG, "Write failed", it) }
                if (System.currentTimeMillis() - lastFlush > 10_000) {
                    lastFlush = System.currentTimeMillis()
                    runCatching { writer?.flush() }
                }
            }
        }
    }

    fun setEnabled(v: Boolean) {
        _enabled.value = v
        prefs.edit().putBoolean("enabled", v).apply()
        if (!v) io.launch { closeWriter() }
    }

    fun setKeepDays(d: Int) {
        _keepDays.value = d
        prefs.edit().putInt(K_KEEP, d).apply()
        io.launch { prune() }
    }

    private fun tick() {
        val s = ObdManager.state.value
        if (!_enabled.value || !s.connected || s.readings.isEmpty()) return
        val name = (if (s.demo) "demo-" else "") + LocalDate.now(ZONE).toString() + ".csv"
        val w = writerFor(name)
        // Only a fresh GPS fix: an old one would show the car parked while it drives on (blank = no GPS).
        val gpsFresh = android.os.SystemClock.elapsedRealtime() - NavManager.gpsFixAt <= GPS_MAX_AGE_MS
        val here = NavManager.here.value?.takeIf { gpsFresh }
        val gear = when (val g = s.gear) {
            is GearReading.Engaged -> g.gear.toString()
            GearReading.Neutral -> "N"
            else -> ""
        }
        val sb = StringBuilder(512)
        val f = java.util.Locale.US
        val demo = if (s.demo) com.cardash.dashboard.obd.DemoGps.fix else null   // demo drives a simulated loop
        sb.append(System.currentTimeMillis()).append(',')
        if (demo != null) {
            sb.append("%.6f".format(f, demo.lat)).append(',').append("%.6f".format(f, demo.lng)).append(',')
            sb.append("%.1f".format(f, demo.speedMps * 3.6)).append(',').append("%.0f".format(f, demo.bearing)).append(',')
        } else {
            sb.append(here?.latitude?.let { "%.6f".format(f, it) } ?: "").append(',')
            sb.append(here?.longitude?.let { "%.6f".format(f, it) } ?: "").append(',')
            sb.append(NavManager.gpsSpeedMps?.takeIf { gpsFresh }?.let { "%.1f".format(f, it * 3.6f) } ?: "").append(',')
            sb.append(NavManager.gpsBearing?.takeIf { gpsFresh }?.let { "%.0f".format(f, it) } ?: "").append(',')
        }
        sb.append(gear).append(',')
        sb.append(if (s.milOn) "1" else "0").append(',')
        sb.append(s.faults.joinToString(" ") { it.code })
        for (p in Pid.entries) {
            sb.append(',')
            s.readings[p]?.let { v -> if (!v.isNaN() && !v.isInfinite()) sb.append(trim(v)) }
        }
        sb.append(',')
        (demo?.altitude ?: NavManager.gpsAltitudeMsl?.takeIf { gpsFresh })?.let { sb.append("%.0f".format(f, it)) }
        sb.append('\n')
        w.write(sb.toString())
        _rows.value = _rows.value + 1
    }

    private fun trim(v: Double): String {
        val r = Math.round(v * 1000.0) / 1000.0
        return if (r == Math.floor(r) && kotlin.math.abs(r) < 1e15) r.toLong().toString() else r.toString()
    }

    /**
     * A day file started by an older version has an older header (fewer columns). Bring it up to date so the
     * newer values in later rows (e.g. "alt") are readable; earlier rows simply leave those columns empty.
     */
    @Synchronized
    fun upgradeHeader(f: File) {
        if (!f.exists() || f.length() == 0L) return
        val header = COLUMNS.joinToString(",")
        runCatching {
            val first = f.bufferedReader().use { it.readLine() }
            if (first == null || first == header) return
            if (writerName == f.name) closeWriter()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.bufferedWriter().use { out ->
                out.write(header)
                out.write("\n")
                f.bufferedReader().useLines { lines -> lines.drop(1).forEach { out.write(it); out.write("\n") } }
            }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            Log.i(TAG, "Updated the header of ${f.name}")
        }.onFailure { Log.w(TAG, "Header update failed for ${f.name}", it) }
    }

    private fun writerFor(name: String): BufferedWriter {
        writer?.let { if (writerName == name) return it }
        closeWriter()
        val f = File(dir, name)
        upgradeHeader(f)
        val fresh = !f.exists() || f.length() == 0L
        val w = java.io.FileWriter(f, true).buffered(32 * 1024) // append: never truncate earlier rides today
        if (fresh) w.write(COLUMNS.joinToString(",") + "\n")
        writer = w
        writerName = name
        prune()
        return w
    }

    private fun closeWriter() {
        runCatching { writer?.flush(); writer?.close() }
        writer = null
        writerName = null
    }

    /** Write anything buffered to disk (e.g. before the web page reads today's file). */
    fun flush() { runCatching { writer?.flush() } }

    /** Delete day files older than [keepDays]. */
    private fun prune() {
        val cutoff = LocalDate.now(ZONE).minusDays(_keepDays.value.toLong() - 1)
        dir.listFiles()?.forEach { f ->
            val day = f.name.removePrefix("demo-").removeSuffix(".csv")
            val d = runCatching { LocalDate.parse(day) }.getOrNull() ?: return@forEach
            if (d.isBefore(cutoff) && f.name != writerName) f.delete()
        }
    }

    data class Day(val file: String, val day: String, val demo: Boolean, val bytes: Long)

    fun days(): List<Day> = dir.listFiles { f -> f.name.endsWith(".csv") }.orEmpty()
        .map { f -> Day(f.name, f.name.removePrefix("demo-").removeSuffix(".csv"), f.name.startsWith("demo-"), f.length()) }
        .sortedWith(compareByDescending<Day> { it.day }.thenBy { it.demo })

    /** The CSV for a day file name from [days], or null if it doesn't exist (no paths accepted). */
    fun file(name: String): File? {
        if (!Regex("""(demo-)?\d{4}-\d{2}-\d{2}\.csv""").matches(name)) return null
        return File(dir, name).takeIf { it.exists() }
    }

    fun totalBytes(): Long = dir.listFiles().orEmpty().sumOf { it.length() }

    fun deleteAll() {
        io.launch {
            closeWriter()
            dir.listFiles()?.forEach { it.delete() }
        }
    }
}
