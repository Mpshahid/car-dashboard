package com.cardash.dashboard.insights

import com.cardash.dashboard.obd.Pid
import com.cardash.dashboard.obd.VehicleState
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Health { GOOD, WATCH, ISSUE }

data class SystemCheck(val name: String, val status: Health, val detail: String)

data class HealthReport(
    val score: Int,
    val label: String,
    val checks: List<SystemCheck>,
) {
    val worst: Health
        get() = when {
            checks.any { it.status == Health.ISSUE } -> Health.ISSUE
            checks.any { it.status == Health.WATCH } -> Health.WATCH
            else -> Health.GOOD
        }
}

/** Derives car-health and efficiency insights from the live OBD data. */
object Insights {
    const val TANK_LITRES = 54.0 // Vauxhall Meriva B, approx

    fun health(state: VehicleState): HealthReport {
        val checks = mutableListOf<SystemCheck>()
        var score = 100

        state.value(Pid.COOLANT_TEMP)?.let { c ->
            val (s, d) = when {
                c > 110 -> Health.ISSUE to "Overheating (${c.roundToInt()}°C)"
                c > 103 -> Health.WATCH to "Running hot (${c.roundToInt()}°C)"
                c < 70 -> Health.WATCH to "Warming up (${c.roundToInt()}°C)"
                else -> Health.GOOD to "Normal (${c.roundToInt()}°C)"
            }
            checks += SystemCheck("Engine temperature", s, d); score -= penalty(s)
        }

        state.value(Pid.MODULE_VOLTAGE)?.let { v ->
            val running = (state.value(Pid.ENGINE_RPM) ?: 0.0) > 500
            val (s, d) = when {
                v < 11.8 -> Health.ISSUE to "Low (${"%.1f".format(v)} V)"
                !running && v < 12.4 -> Health.WATCH to "Weak (${"%.1f".format(v)} V)"
                v > 15.0 -> Health.WATCH to "Overcharging (${"%.1f".format(v)} V)"
                else -> Health.GOOD to "Healthy (${"%.1f".format(v)} V)"
            }
            checks += SystemCheck("Battery / charging", s, d); score -= penalty(s)
        }

        val stft = state.value(Pid.SHORT_FUEL_TRIM)
        val ltft = state.value(Pid.LONG_FUEL_TRIM)
        if (stft != null || ltft != null) {
            val worst = maxOf(abs(stft ?: 0.0), abs(ltft ?: 0.0))
            val (s, d) = when {
                worst > 20 -> Health.ISSUE to "Trims off (${worst.roundToInt()}%)"
                worst > 10 -> Health.WATCH to "Trims high (${worst.roundToInt()}%)"
                else -> Health.GOOD to "Running clean"
            }
            checks += SystemCheck("Fuel system", s, d); score -= penalty(s)
        }

        val faultStatus = if (state.milOn || state.faults.isNotEmpty()) Health.ISSUE else Health.GOOD
        val faultDetail = if (state.faults.isNotEmpty()) "${state.faults.size} fault code(s)" else "No fault codes"
        checks += SystemCheck("Emissions / faults", faultStatus, faultDetail); score -= penalty(faultStatus)

        score = score.coerceIn(0, 100)
        val label = when {
            score >= 90 -> "Excellent"
            score >= 75 -> "Good"
            score >= 55 -> "Fair"
            else -> "Needs attention"
        }
        return HealthReport(score, label, checks)
    }

    private fun penalty(s: Health) = when (s) {
        Health.GOOD -> 0
        Health.WATCH -> 12
        Health.ISSUE -> 30
    }

    /** Projected driving range (km) from fuel level, tank size and recent economy. */
    fun projectedRangeKm(state: VehicleState): Double? {
        val fuelPct = state.value(Pid.FUEL_LEVEL) ?: return null
        val litres = TANK_LITRES * fuelPct / 100.0
        val l100 = if (state.trip.distanceKm > 0.5) {
            state.trip.fuelUsedL / state.trip.distanceKm * 100.0
        } else {
            state.trip.instantL100
        }
        if (l100 <= 0.0) return null
        return litres / l100 * 100.0
    }
}
