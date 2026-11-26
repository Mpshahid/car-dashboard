package com.cardash.dashboard.obd

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One stored sample: seconds since the session started, and the raw (metric) value. */
data class Sample(val t: Float, val v: Float)

/** Session statistics for one parameter (raw metric units). */
data class PidStats(val min: Double, val max: Double, val avg: Double, val count: Int, val last: Double)

/**
 * Rolling history (last 15 minutes at ≤1 sample/s) and session statistics for every
 * parameter — feeds the Sensors tab charts, the statistics table, the alerts and the AI analysis.
 */
object SensorHistory {
    private const val MAX_SAMPLES = 900
    private const val MIN_GAP_S = 1.0f

    private val lock = Any()
    private var start = SystemClock.elapsedRealtime()
    private val series = HashMap<Pid, ArrayDeque<Sample>>()
    private val stats = HashMap<Pid, DoubleArray>() // min, max, sum, count, last

    /** Bumped once per second of new data so the UI can redraw. */
    private val _tick = MutableStateFlow(0L)
    val tick: StateFlow<Long> = _tick.asStateFlow()
    private var lastTickAt = 0f

    fun clear() = synchronized(lock) {
        series.clear(); stats.clear()
        start = SystemClock.elapsedRealtime()
        lastTickAt = 0f
        _tick.value = 0L
    }

    fun now(): Float = (SystemClock.elapsedRealtime() - start) / 1000f

    fun record(readings: Map<Pid, Double>) {
        val t = now()
        synchronized(lock) {
            for ((pid, v) in readings) {
                if (v.isNaN() || v.isInfinite()) continue
                val s = stats.getOrPut(pid) { doubleArrayOf(v, v, 0.0, 0.0, v) }
                if (v < s[0]) s[0] = v
                if (v > s[1]) s[1] = v
                s[2] += v; s[3] += 1.0; s[4] = v

                val q = series.getOrPut(pid) { ArrayDeque() }
                if (q.isEmpty() || t - q.last().t >= MIN_GAP_S) {
                    q.addLast(Sample(t, v.toFloat()))
                    while (q.size > MAX_SAMPLES) q.removeFirst()
                }
            }
        }
        if (t - lastTickAt >= 1f) { lastTickAt = t; _tick.value = _tick.value + 1 }
    }

    /** Samples for [pid] within the last [windowS] seconds. */
    fun window(pid: Pid, windowS: Float): List<Sample> = synchronized(lock) {
        val q = series[pid] ?: return emptyList()
        val from = now() - windowS
        q.filter { it.t >= from }
    }

    fun stats(pid: Pid): PidStats? = synchronized(lock) {
        val s = stats[pid] ?: return null
        val n = s[3].toInt()
        if (n == 0) null else PidStats(s[0], s[1], s[2] / n, n, s[4])
    }

    /** Mean of the last [windowS] seconds (null if no data). */
    fun recentMean(pid: Pid, windowS: Float): Double? {
        val w = window(pid, windowS)
        return if (w.isEmpty()) null else w.sumOf { it.v.toDouble() } / w.size
    }

    /** Spread (max-min) of the last [windowS] seconds — e.g. a stuck O2 sensor shows ~0. */
    fun recentSpread(pid: Pid, windowS: Float): Double? {
        val w = window(pid, windowS)
        if (w.size < 5) return null
        return (w.maxOf { it.v } - w.minOf { it.v }).toDouble()
    }
}
