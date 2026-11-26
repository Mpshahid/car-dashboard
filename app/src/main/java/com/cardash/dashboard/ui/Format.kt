package com.cardash.dashboard.ui

import com.cardash.dashboard.domain.Units
import com.cardash.dashboard.obd.Pid
import kotlin.math.roundToInt

/** User display preferences. UK defaults: mph / miles, Celsius. */
data class UiPrefs(
    val imperial: Boolean = true,
    val tempF: Boolean = false,
)

object Fmt {
    fun speedValue(kmh: Double, imperial: Boolean): Int =
        (if (imperial) Units.kmhToMph(kmh) else kmh).roundToInt()

    fun speedUnit(imperial: Boolean) = if (imperial) "mph" else "km/h"
    fun tempUnit(f: Boolean) = if (f) "°F" else "°C"
    fun distValue(km: Double, imperial: Boolean) = if (imperial) Units.kmToMiles(km) else km
    fun distUnit(imperial: Boolean) = if (imperial) "mi" else "km"

    /** Economy string in the preferred unit (UK MPG or L/100km). */
    fun economy(l100: Double, imperial: Boolean): String {
        if (l100 <= 0.0) return "--"
        return if (imperial) Units.l100ToMpgUk(l100).roundToInt().toString()
        else "%.1f".format(l100)
    }

    fun economyUnit(imperial: Boolean) = if (imperial) "mpg" else "L/100km"

    fun reading(pid: Pid, v: Double, prefs: UiPrefs): String = when {
        pid == Pid.SPEED -> speedValue(v, prefs.imperial).toString()
        pid.isTemperature -> (if (prefs.tempF) Units.cToF(v) else v).roundToInt().toString()
        pid.decimals > 0 -> "%.${pid.decimals}f".format(v)
        else -> v.roundToInt().toString()
    }

    /** Value converted to the user's display units (for charts and statistics). */
    fun displayValue(pid: Pid, v: Double, prefs: UiPrefs): Double = when {
        pid == Pid.SPEED && prefs.imperial -> Units.kmhToMph(v)
        pid.isTemperature && prefs.tempF -> Units.cToF(v)
        else -> v
    }

    fun unit(pid: Pid, prefs: UiPrefs): String = when {
        pid == Pid.SPEED -> speedUnit(prefs.imperial)
        pid.isTemperature -> tempUnit(prefs.tempF)
        else -> pid.unit
    }

    fun duration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Distance to the next maneuver, in the preferred unit. */
    fun navDistance(meters: Int, imperial: Boolean): String {
        if (imperial) {
            val yards = meters * 1.09361
            return if (yards < 800) "${(yards / 10).roundToInt() * 10} yd"
            else "%.1f mi".format(meters / 1609.34)
        }
        return if (meters < 950) "$meters m" else "%.1f km".format(meters / 1000.0)
    }

    fun etaText(seconds: Int): String {
        val m = (seconds / 60.0).roundToInt()
        return if (m < 60) "$m min" else "%d h %02d min".format(m / 60, m % 60)
    }
}
