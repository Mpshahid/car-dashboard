package com.cardash.dashboard.domain

import com.cardash.dashboard.obd.GearReading
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/**
 * Works out which gear a manual gearbox is in from engine speed ÷ road speed: each gear turns the engine a fixed
 * number of rpm per km/h.
 *
 * The figures are measured from the driver's own 2012 Vauxhall Meriva B 1.4 Turbo (120 PS), which has a five-speed
 * manual: on the steady stretches of a 55-minute drive the ratio stays within about 2 % of them. (An earlier
 * six-speed table read rolling in neutral at about 1,000 rpm as a 6th gear.)
 *
 * With the clutch down, or in neutral, the engine idles while the car rolls on, so the ratio drifts across the other
 * gears' figures as the car slows (coasting to a stop used to count 5-4-3-2-1). So:
 *  - at idle speed a gear only counts if the rpm followed the road speed over the last couple of seconds;
 *  - a new gear shows once two readings in a row agree, and readings that keep disagreeing show neutral, so the
 *    moment of a gear change doesn't flash a wrong gear;
 *  - a clear match well above idle straight after neutral or the clutch (pulling away, a gear change) shows at once:
 *    1st in particular is often held too briefly to be seen twice.
 *
 * The ride-replay page works recorded drives out the same way (gearTracker() in assets/web/index.html): keep the two
 * in step.
 */
class GearEstimator(private val ratios: List<Double> = DEFAULT_RATIOS) {

    private class Sample(val at: Long, val kmh: Double, val rpm: Double)

    private val history = ArrayDeque<Sample>()
    private var shown = 0               // gear on display, 0 = neutral
    private var candidate = -1          // the latest reading that disagrees with the display, and since when
    private var candidateAt = 0L
    private var disagreeSince = -1L     // when readings started disagreeing with the display
    private var idleRpm = 850.0         // learned while the car stands still
    private var last: GearReading = GearReading.Neutral

    /** [atMs] comes from a steady clock (elapsedRealtime), so readings can be compared over time. */
    fun estimate(speedKmh: Double, rpm: Double, atMs: Long): GearReading {
        val prev = history.lastOrNull()
        if (prev != null && prev.kmh == speedKmh && prev.rpm == rpm) return last   // the same reading again (not refreshed)
        val (raw, sure) = classify(speedKmh, rpm, atMs)
        history.addLast(Sample(atMs, speedKmh, rpm))
        while (atMs - history.first().at > HISTORY_MS) history.removeFirst()
        when {
            raw == shown -> { disagreeSince = -1; candidate = -1 }
            // A clear gear straight after neutral or the clutch (a gear change) shows at once.
            sure && (shown == 0 || candidate == 0) -> { shown = raw; disagreeSince = -1; candidate = -1 }
            else -> {
                if (disagreeSince < 0) disagreeSince = atMs
                if (raw != candidate) { candidate = raw; candidateAt = atMs }
                if (atMs - candidateAt >= CONFIRM_MS) {
                    shown = raw; disagreeSince = -1; candidate = -1            // the same new value, 0.4 s apart
                } else if (atMs - disagreeSince >= CONFIRM_MS) {
                    shown = 0; disagreeSince = -1                              // still disagreeing, and with each other
                }
            }
        }
        last = if (shown == 0) GearReading.Neutral
        else GearReading.Engaged(shown, shiftUp = rpm > SHIFT_UP_RPM && shown < ratios.size)
        return last
    }

    /** Forget the drive so far (a new connection, or demo mode), but keep the learned idle speed. */
    fun reset() {
        history.clear()
        shown = 0
        candidate = -1
        disagreeSince = -1
        last = GearReading.Neutral
    }

    /** The gear these readings point to (0 = neutral), and whether it's a clear match well above idle speed. */
    private fun classify(kmh: Double, rpm: Double, at: Long): Pair<Int, Boolean> {
        if (rpm < 500.0) return 0 to false                                   // engine off
        if (kmh < 3.0) {
            if (kmh < 0.5 && rpm < 1600.0) idleRpm += (rpm - idleRpm) * 0.1  // standing still: learn the idle speed
            return 0 to false
        }
        val r = rpm / kmh
        var best = 0
        for (i in ratios.indices) if (abs(ln(r / ratios[i])) < abs(ln(r / ratios[best]))) best = i
        val off = abs(r / ratios[best] - 1)
        if (off > 0.07 + 0.6 / kmh) return 0 to false     // no gear (speed comes in whole km/h, so allow more when slow)
        val gear = best + 1
        if (rpm > max(IDLE_FLOOR, idleRpm + IDLE_MARGIN)) return gear to (off <= 0.05)
        // The engine is at idle speed, as it is with the clutch down: the gear only counts if the rpm followed the speed.
        val then = history.firstOrNull { at - it.at in LOOK_MIN..LOOK_MAX && it.kmh >= 3.0 && it.rpm >= 500.0 }
            ?: return (if (shown == gear) gear else 0) to false
        val dv = ln(kmh / then.kmh)
        val dr = ln(rpm / then.rpm)
        if (abs(kmh - then.kmh) >= 2.0) return (if (abs(dr - dv) < 0.5 * abs(dv)) gear else 0) to false
        if (abs(dr) > 0.06) return 0 to false                                // the rpm moved on its own: the clutch
        return (if (shown == gear) gear else 0) to false
    }

    companion object {
        /** rpm per km/h in gears 1-5: Meriva B 1.4 Turbo 120 PS, five-speed manual, measured on the driver's car. */
        val DEFAULT_RATIOS = listOf(129.5, 66.9, 44.8, 31.95, 25.36)
        const val SHIFT_UP_RPM = 3200.0
        private const val IDLE_FLOOR = 1150.0    // at or below this the engine may just be idling
        private const val IDLE_MARGIN = 300.0    // rolling with the clutch down, it idles a little above standing still
        private const val HISTORY_MS = 3_000L
        private const val LOOK_MIN = 800L        // compare with a reading 0.8-2.6 s old
        private const val LOOK_MAX = 2_600L
        private const val CONFIRM_MS = 400L
    }
}
