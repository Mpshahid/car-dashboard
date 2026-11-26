package com.cardash.dashboard.obd

import com.cardash.dashboard.domain.GearEstimator
import com.cardash.dashboard.domain.Units
import kotlin.math.sin

/**
 * Generates a believable urban drive cycle so the app is fully usable on an
 * emulator with no adapter connected. RPM is kept consistent with the gear
 * estimator's ratios so the estimated gear tracks the simulated one.
 */
class DemoSimulator {

    private var t = 0.0
    private var speed = 0.0
    private val ratios = GearEstimator.DEFAULT_RATIOS

    fun tick(dt: Double): Map<Pid, Double> {
        t += dt
        val target = (55 + 55 * sin(t / 18.0)).coerceIn(0.0, 112.0)
        speed += (target - speed) * (dt / 3.0).coerceAtMost(1.0)
        if (speed < 0.3) speed = 0.0

        val gearIdx = gearForSpeed(speed)
        val baseRpm = if (speed < 2.0) 820.0 else ratios[gearIdx] * speed
        val rpm = (baseRpm * (1 + 0.02 * sin(t * 2.0))).coerceIn(700.0, 6500.0)
        val throttle = (((target - speed).coerceAtLeast(0.0)) * 3.0 + 12 + 8 * sin(t)).coerceIn(0.0, 100.0)
        val load = (rpm / 6500.0 * 70 + throttle * 0.3).coerceIn(0.0, 100.0)
        val maf = (2 + load / 100.0 * 28 + rpm / 6500.0 * 10).coerceIn(1.0, 160.0)
        val coolant = (89 + 3 * sin(t / 40.0)).coerceIn(84.0, 97.0)

        return mapOf(
            Pid.SPEED to speed,
            Pid.ENGINE_RPM to rpm,
            Pid.THROTTLE to throttle,
            Pid.ENGINE_LOAD to load,
            Pid.MAF to maf,
            Pid.COOLANT_TEMP to coolant,
            Pid.OIL_TEMP to coolant + 6,
            Pid.INTAKE_TEMP to 30.0 + 4 * sin(t / 30.0),
            Pid.AMBIENT_TEMP to 14.0,
            Pid.MODULE_VOLTAGE to 14.1 + 0.15 * sin(t / 5.0),
            Pid.FUEL_LEVEL to (64.0 - t * 0.02).coerceAtLeast(6.0),
            Pid.MAP_PRESSURE to (30 + load * 0.9).coerceIn(20.0, 102.0),
            Pid.TIMING_ADVANCE to (8 + 6 * sin(t)),
            Pid.SHORT_FUEL_TRIM to 2 * sin(t / 3.0),
            Pid.LONG_FUEL_TRIM to 1.5,
            Pid.RUN_TIME to t,
            Pid.DIST_SINCE_CLEAR to 1234.0,
            Pid.FUEL_RATE to Units.petrolLitresPerHour(maf),
            // Extended set — a typical petrol Meriva B with narrow-band O2 sensors.
            Pid.ABS_LOAD to load * 0.95,
            Pid.O2_B1S1_VOLT to (0.45 + 0.4 * sin(t * 4.3)).coerceIn(0.05, 0.9), // switching rich/lean
            Pid.O2_B1S2_VOLT to 0.66 + 0.03 * sin(t / 7.0),                       // steady behind the cat
            Pid.COMMANDED_LAMBDA to if (throttle > 70) 0.9 else 1.0,
            Pid.CAT_TEMP_B1S1 to 420 + load * 3.2 + 15 * sin(t / 9.0),
            Pid.CAT_TEMP_B1S2 to 380 + load * 2.6,
            Pid.REL_THROTTLE to (throttle - 9).coerceAtLeast(0.0),
            Pid.COMMANDED_THROTTLE to throttle * 0.96,
            Pid.PEDAL_D to (throttle * 0.8 + 15).coerceAtMost(100.0),
            Pid.PEDAL_E to (throttle * 0.4 + 8).coerceAtMost(100.0),
            Pid.EVAP_PURGE to (12 + 10 * sin(t / 11.0)).coerceAtLeast(0.0),
            Pid.BARO to 101.0,
            Pid.WARMUPS_SINCE_CLEAR to 118.0,
            Pid.TIME_SINCE_CLEAR to 4820.0,
            Pid.DIST_MIL_ON to 0.0,
            Pid.TIME_MIL_ON to 0.0,
        )
    }

    companion object {
        /** What a petrol Meriva B typically answers in demo mode. */
        val SUPPORTED: Set<Pid> = setOf(
            Pid.ENGINE_RPM, Pid.SPEED, Pid.ENGINE_LOAD, Pid.ABS_LOAD, Pid.TIMING_ADVANCE, Pid.RUN_TIME,
            Pid.MAF, Pid.MAP_PRESSURE, Pid.BARO, Pid.SHORT_FUEL_TRIM, Pid.LONG_FUEL_TRIM,
            Pid.O2_B1S1_VOLT, Pid.O2_B1S2_VOLT, Pid.COMMANDED_LAMBDA, Pid.FUEL_LEVEL, Pid.FUEL_RATE,
            Pid.COOLANT_TEMP, Pid.INTAKE_TEMP, Pid.OIL_TEMP, Pid.AMBIENT_TEMP, Pid.CAT_TEMP_B1S1, Pid.CAT_TEMP_B1S2,
            Pid.THROTTLE, Pid.REL_THROTTLE, Pid.COMMANDED_THROTTLE, Pid.PEDAL_D, Pid.PEDAL_E,
            Pid.EVAP_PURGE, Pid.MODULE_VOLTAGE,
            Pid.DIST_SINCE_CLEAR, Pid.DIST_MIL_ON, Pid.WARMUPS_SINCE_CLEAR, Pid.TIME_MIL_ON, Pid.TIME_SINCE_CLEAR,
        )

        val READINESS = Readiness(
            milOn = false,
            dtcCount = 0,
            monitors = listOf(
                Monitor("Misfire", true), Monitor("Fuel system", true), Monitor("Components", true),
                Monitor("Catalyst", true), Monitor("Evap system", false), Monitor("O2 sensor", true),
                Monitor("O2 sensor heater", true),
            ),
        )
    }

    private fun gearForSpeed(s: Double): Int = when {
        s < 12 -> 0
        s < 28 -> 1
        s < 50 -> 2
        s < 75 -> 3
        else -> 4
    }.coerceAtMost(ratios.size - 1)
}
