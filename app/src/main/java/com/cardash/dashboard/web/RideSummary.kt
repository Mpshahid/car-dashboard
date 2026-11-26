package com.cardash.dashboard.web

import com.cardash.dashboard.data.DriveLog
import com.cardash.dashboard.insights.NormalRanges
import com.cardash.dashboard.obd.Pid
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Compact description of a recorded ride slice for the AI: stats, events, codes, and a 30 s timeline. */
object RideSummary {
    private val timeOnly = SimpleDateFormat("HH:mm:ss", Locale.UK)
    private val dayClock = SimpleDateFormat("EEE d MMM HH:mm:ss", Locale.UK)
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.UK)

    fun build(file: File, from: Long, to: Long): String = build(listOf(file), from, to)

    /** [files] are day recordings in date order (e.g. the web page's "Last 7 days"); rows from all of them are used. */
    fun build(files: List<File>, from: Long, to: Long): String {
        val header = DriveLog.COLUMNS
        val ix = header.withIndex().associate { (i, n) -> n to i }
        val rows = ArrayList<List<String>>()
        for (file in files) {
            DriveLog.upgradeHeader(file)   // older days get the current columns, so every row lines up with [header]
            file.bufferedReader().useLines { lines ->
                lines.drop(1).forEach { line ->
                    val r = line.split(',')
                    val t = r.getOrNull(0)?.toLongOrNull() ?: return@forEach
                    if (t in from..to) rows.add(r)
                }
            }
        }
        if (rows.isEmpty()) return "No data in the selected time range."

        val pids = Pid.entries.associateBy { it.name }
        fun num(r: List<String>, k: String) = ix[k]?.let { r.getOrNull(it) }?.toDoubleOrNull()
            ?.takeUnless { v -> pids[k]?.let { Pid.isNoReading(it, v) } == true }
        val t0 = rows.first()[0].toLong(); val t1 = rows.last()[0].toLong()
        // Over several days the times carry the day too.
        val clock = if (day.format(Date(t0)) == day.format(Date(t1))) timeOnly else dayClock

        val out = JSONObject()
        out.put("start", clock.format(Date(t0)))
        out.put("end", clock.format(Date(t1)))
        out.put("duration_min", "%.1f".format((t1 - t0) / 60000.0))
        out.put("samples", rows.size)

        // Distance and moving time from the car's speed.
        var km = 0.0; var moving = 0.0; var prevT = t0
        rows.forEach { r ->
            val t = r[0].toLong(); val dt = ((t - prevT) / 1000.0).coerceIn(0.0, 5.0); prevT = t
            val v = num(r, Pid.SPEED.name) ?: return@forEach
            km += v * dt / 3600.0
            if (v > 2) moving += dt
        }
        out.put("distance_km", "%.1f".format(km))
        out.put("moving_min", "%.1f".format(moving / 60.0))

        // Per-sensor stats with time spent outside the normal range.
        val stats = JSONObject()
        Pid.entries.forEach { p ->
            val vals = rows.mapNotNull { num(it, p.name) }
            if (vals.isEmpty()) return@forEach
            val o = JSONObject()
                .put("min", r3(vals.min())).put("avg", r3(vals.average())).put("max", r3(vals.max())).put("unit", p.unit)
            NormalRanges.of(p)?.let { n ->
                val outside = vals.count { it < n.low || it > n.high }
                o.put("normal", "${n.low}–${n.high}${n.note?.let { " ($it)" } ?: ""}")
                if (outside > 0) o.put("outside_normal_pct", "%.0f".format(100.0 * outside / vals.size))
            }
            stats.put(p.label, o)
        }
        out.put("sensors", stats)

        // Events: check-engine light, fault codes, hot coolant, high revs, low charging voltage.
        val events = JSONArray()
        var lastMil = "0"; var lastDtc = ""
        rows.forEach { r ->
            val t = clock.format(Date(r[0].toLong()))
            val mil = ix["mil"]?.let { r.getOrNull(it) }.orEmpty()
            val dtc = ix["dtc"]?.let { r.getOrNull(it) }.orEmpty()
            if (mil != lastMil) { events.put("$t check-engine light ${if (mil == "1") "ON" else "off"}"); lastMil = mil }
            if (dtc != lastDtc) { events.put("$t fault codes: ${dtc.ifBlank { "none" }}"); lastDtc = dtc }
        }
        fun firstOver(k: Pid, limit: Double, what: String) {
            rows.firstOrNull { (num(it, k.name) ?: Double.NEGATIVE_INFINITY) > limit }?.let {
                events.put("${clock.format(Date(it[0].toLong()))} $what (${num(it, k.name)})")
            }
        }
        firstOver(Pid.COOLANT_TEMP, 105.0, "coolant above 105 °C")
        firstOver(Pid.ENGINE_RPM, 5000.0, "engine above 5000 rpm")
        rows.firstOrNull { val v = num(it, Pid.MODULE_VOLTAGE.name); val rpm = num(it, Pid.ENGINE_RPM.name) ?: 0.0; v != null && rpm > 500 && v < 13.0 }
            ?.let { events.put("${clock.format(Date(it[0].toLong()))} charging voltage below 13 V with engine running (${num(it, Pid.MODULE_VOLTAGE.name)})") }
        out.put("events", events)

        // Coarse timeline of the key signals: every 30 s, or sparser for long stretches (a week of driving) so it all fits.
        val keys = listOf(Pid.SPEED, Pid.ENGINE_RPM, Pid.ENGINE_LOAD, Pid.COOLANT_TEMP, Pid.SHORT_FUEL_TRIM, Pid.LONG_FUEL_TRIM, Pid.MODULE_VOLTAGE)
        val stepSec = maxOf(30, rows.size / 200)
        val tl = StringBuilder("time," + keys.joinToString(",") { it.name.lowercase() } + "\n")
        var next = t0
        rows.forEach { r ->
            val t = r[0].toLong()
            if (t >= next) {
                tl.append(clock.format(Date(t)))
                keys.forEach { k -> tl.append(',').append(num(r, k.name)?.let { r3(it) } ?: "") }
                tl.append('\n')
                next = t + stepSec * 1000L
            }
        }
        return out.toString(1) + "\n\nTimeline (every $stepSec s):\n" + tl.toString().take(14_000)
    }

    private fun r3(v: Double): Any = if (v == Math.floor(v)) v.toLong() else Math.round(v * 100.0) / 100.0
}
