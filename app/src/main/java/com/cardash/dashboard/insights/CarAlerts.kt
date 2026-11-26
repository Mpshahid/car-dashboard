package com.cardash.dashboard.insights

import com.cardash.dashboard.obd.Pid
import com.cardash.dashboard.obd.SensorHistory
import com.cardash.dashboard.obd.VehicleState
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Severity(val label: String) { CRITICAL("Critical"), WARNING("Warning"), INFO("Note") }

/** One thing worth the driver's attention, with what it means and what to do. */
data class CarAlert(
    val severity: Severity,
    val system: String,
    val title: String,
    val detail: String,
    val action: String,
    val pid: Pid? = null,
)

/**
 * Rule-based alerts from live OBD data and recent trends (petrol engine, 2012 Meriva B).
 * Thresholds are conservative rules of thumb, not manufacturer limits.
 */
object CarAlerts {

    fun evaluate(s: VehicleState): List<CarAlert> {
        val out = ArrayList<CarAlert>()
        val rpm = s.value(Pid.ENGINE_RPM) ?: 0.0
        val running = rpm > 400
        val runTime = s.value(Pid.RUN_TIME) ?: 0.0

        // ---- Faults / check-engine light
        if (s.milOn) out += CarAlert(
            Severity.CRITICAL, "Diagnostics", "Check-engine light is on",
            "The engine computer has flagged a fault${if (s.faults.isNotEmpty()) ": ${s.faults.joinToString { it.code }}" else ""}.",
            "Read the codes on the Status tab and get it checked soon. If it flashes, ease off — that usually means misfiring.",
        ) else if (s.faults.isNotEmpty()) out += CarAlert(
            Severity.WARNING, "Diagnostics", "${s.faults.size} fault code(s) stored",
            s.faults.joinToString { "${it.code} ${it.description}" },
            "See the Status tab for details. Pending codes can clear themselves if the fault doesn't come back.",
        )

        // ---- Coolant
        s.value(Pid.COOLANT_TEMP)?.let { c ->
            when {
                c >= 110 -> out += CarAlert(Severity.CRITICAL, "Cooling", "Engine overheating (${c.roundToInt()}°C)",
                    "Coolant is far above the normal ~90°C.", "Pull over safely, switch off and let it cool. Don't open the coolant cap while hot.", Pid.COOLANT_TEMP)
                c >= 104 -> out += CarAlert(Severity.WARNING, "Cooling", "Running hot (${c.roundToInt()}°C)",
                    "Higher than normal; can happen in traffic on hot days.", "Watch it. If it keeps climbing, check the coolant level and radiator fan.", Pid.COOLANT_TEMP)
                running && runTime > 900 && c < 70 -> out += CarAlert(Severity.WARNING, "Cooling", "Engine not warming up (${c.roundToInt()}°C after ${(runTime / 60).roundToInt()} min)",
                    "A thermostat stuck open keeps the engine cold, raising fuel use and wear.", "Have the thermostat checked.", Pid.COOLANT_TEMP)
            }
        }

        // ---- Oil
        s.value(Pid.OIL_TEMP)?.let { o ->
            if (o >= 135) out += CarAlert(Severity.CRITICAL, "Lubrication", "Oil very hot (${o.roundToInt()}°C)", "Oil above ~130°C breaks down quickly.", "Ease off and let the engine cool.", Pid.OIL_TEMP)
            else if (o >= 120) out += CarAlert(Severity.WARNING, "Lubrication", "Oil hot (${o.roundToInt()}°C)", "Higher than usual.", "Drive gently until it drops.", Pid.OIL_TEMP)
        }

        // ---- Battery / charging
        s.value(Pid.MODULE_VOLTAGE)?.let { v ->
            when {
                running && v < 12.6 -> out += CarAlert(Severity.CRITICAL, "Electrical", "Not charging (${"%.1f".format(v)} V)",
                    "With the engine running the alternator should give about 13.5–14.7 V.", "Check the alternator and drive belt soon — the battery is draining.", Pid.MODULE_VOLTAGE)
                running && v < 13.2 -> out += CarAlert(Severity.WARNING, "Electrical", "Weak charging (${"%.1f".format(v)} V)",
                    "Lower than normal while running.", "Have the alternator and battery tested.", Pid.MODULE_VOLTAGE)
                v > 15.2 -> out += CarAlert(Severity.WARNING, "Electrical", "Over-charging (${"%.1f".format(v)} V)",
                    "Too high a voltage can damage the battery and electronics.", "Have the voltage regulator / alternator checked.", Pid.MODULE_VOLTAGE)
                !running && v < 12.2 -> out += CarAlert(Severity.WARNING, "Electrical", "Battery low (${"%.1f".format(v)} V)",
                    "A healthy resting battery reads about 12.6 V.", "Charge the battery or have it tested before it lets you down.", Pid.MODULE_VOLTAGE)
            }
        }

        // ---- Fuel trims (lean / rich)
        val ltft = s.value(Pid.LONG_FUEL_TRIM)
        val stftAvg = SensorHistory.recentMean(Pid.SHORT_FUEL_TRIM, 60f)
        val total = (ltft ?: 0.0) + (stftAvg ?: 0.0)
        if (running && (ltft != null || stftAvg != null)) {
            val lean = total > 0
            val mag = abs(total)
            val what = if (lean) "lean (adding fuel)" else "rich (removing fuel)"
            val causes = if (lean) "Common causes: an air leak after the air-flow sensor, a dirty MAF sensor, weak fuel pressure."
            else "Common causes: a leaking injector, a stuck purge valve, a faulty O2 sensor."
            if (mag >= 20) out += CarAlert(Severity.CRITICAL, "Fuel system", "Running very $what (${mag.roundToInt()}%)", causes, "Get it diagnosed — it can damage the catalytic converter.", Pid.LONG_FUEL_TRIM)
            else if (mag >= 10) out += CarAlert(Severity.WARNING, "Fuel system", "Running $what (${mag.roundToInt()}%)", causes, "Worth checking at the next service.", Pid.LONG_FUEL_TRIM)
        }

        // ---- O2 sensor health (narrow-band front sensor should switch when warm)
        if (running && (s.value(Pid.COOLANT_TEMP) ?: 0.0) > 75) {
            SensorHistory.recentSpread(Pid.O2_B1S1_VOLT, 30f)?.let { spread ->
                if (spread < 0.15) out += CarAlert(Severity.WARNING, "Emissions", "Front O2 sensor looks lazy",
                    "It should swing between ~0.1 and ~0.9 V; it's barely moving (${"%.2f".format(spread)} V).", "Have the upstream O2 sensor checked.", Pid.O2_B1S1_VOLT)
            }
        }

        // ---- Catalyst
        s.value(Pid.CAT_TEMP_B1S1)?.let { t ->
            if (t >= 950) out += CarAlert(Severity.CRITICAL, "Emissions", "Catalytic converter very hot (${t.roundToInt()}°C)", "Usually caused by misfires or running rich.", "Ease off and get it checked to avoid damaging the converter.", Pid.CAT_TEMP_B1S1)
            else if (t >= 850) out += CarAlert(Severity.WARNING, "Emissions", "Catalytic converter hot (${t.roundToInt()}°C)", "Higher than typical cruising temperature.", "Avoid sustained high load for a while.", Pid.CAT_TEMP_B1S1)
        }

        // ---- Intake air
        s.value(Pid.INTAKE_TEMP)?.let { i ->
            if (i >= 60) out += CarAlert(Severity.INFO, "Air intake", "Hot intake air (${i.roundToInt()}°C)", "Heat-soak after slow traffic reduces power a little.", "Nothing to do — it drops once moving.", Pid.INTAKE_TEMP)
        }

        // ---- Fuel
        s.value(Pid.FUEL_LEVEL)?.let { f ->
            if (f < 6) out += CarAlert(Severity.CRITICAL, "Fuel", "Fuel nearly empty (${f.roundToInt()}%)", "Running dry can damage the fuel pump.", "Refuel now — say \"Dash, find petrol\".", Pid.FUEL_LEVEL)
            else if (f < 12) out += CarAlert(Severity.WARNING, "Fuel", "Fuel low (${f.roundToInt()}%)", "About ${(f / 100 * Insights.TANK_LITRES).roundToInt()} L left.", "Plan a fuel stop.", Pid.FUEL_LEVEL)
        }

        // ---- Over-revving
        if (rpm > 6200) out += CarAlert(Severity.INFO, "Engine", "High revs (${rpm.roundToInt()} rpm)", "Near the rev limiter.", "Change up a gear.", Pid.ENGINE_RPM)

        // ---- MOT readiness
        s.readiness?.incomplete?.takeIf { it.isNotEmpty() }?.let { inc ->
            out += CarAlert(Severity.INFO, "Readiness", "${inc.size} emissions self-test(s) not finished",
                inc.joinToString { it.name } + " — normal after a battery disconnect or clearing codes.",
                "A mix of town and steady motorway driving lets them complete before an MOT.")
        }

        return out.sortedBy { it.severity.ordinal }
    }
}
