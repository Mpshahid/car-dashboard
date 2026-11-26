package com.cardash.dashboard.insights

import com.cardash.dashboard.obd.Pid

/**
 * Typical healthy range for a warm petrol engine (metric units, as the car reports them).
 * Rules of thumb for a 2012 Meriva B, not manufacturer limits. [note] adds context such as idle values.
 */
data class NormalRange(val low: Double, val high: Double, val note: String? = null) {
    fun contains(v: Double) = v in low..high
}

object NormalRanges {
    private val ranges: Map<Pid, NormalRange> = mapOf(
        Pid.ENGINE_RPM to NormalRange(650.0, 6000.0, "idle 700–900"),
        Pid.ENGINE_LOAD to NormalRange(15.0, 85.0, "idle 20–35%"),
        Pid.ABS_LOAD to NormalRange(15.0, 95.0),
        Pid.TIMING_ADVANCE to NormalRange(0.0, 40.0, "idle 5–15°"),
        Pid.MAF to NormalRange(2.0, 120.0, "idle 2–5 g/s"),
        Pid.MAP_PRESSURE to NormalRange(20.0, 102.0, "idle 25–40 kPa"),
        Pid.BARO to NormalRange(90.0, 105.0),
        Pid.FUEL_PRESSURE to NormalRange(250.0, 450.0),
        Pid.SHORT_FUEL_TRIM to NormalRange(-10.0, 10.0),
        Pid.LONG_FUEL_TRIM to NormalRange(-10.0, 10.0),
        Pid.O2_B1S1_VOLT to NormalRange(0.1, 0.9, "should keep switching"),
        Pid.O2_B1S2_VOLT to NormalRange(0.5, 0.9, "should stay steady"),
        Pid.LAMBDA_B1S1 to NormalRange(0.97, 1.03),
        Pid.LAMBDA_B1S1_CURRENT to NormalRange(0.97, 1.03),
        Pid.COMMANDED_LAMBDA to NormalRange(0.85, 1.05),
        Pid.FUEL_RATE to NormalRange(0.5, 15.0, "idle ~0.8 L/h"),
        Pid.COOLANT_TEMP to NormalRange(85.0, 105.0, "once warm"),
        Pid.INTAKE_TEMP to NormalRange(-10.0, 60.0),
        Pid.OIL_TEMP to NormalRange(80.0, 120.0, "once warm"),
        Pid.CAT_TEMP_B1S1 to NormalRange(300.0, 850.0),
        Pid.CAT_TEMP_B1S2 to NormalRange(250.0, 800.0),
        Pid.MODULE_VOLTAGE to NormalRange(13.5, 14.7, "engine off 12.4–12.8 V"),
        Pid.FUEL_LEVEL to NormalRange(12.0, 100.0),
        Pid.EGR_ERROR to NormalRange(-10.0, 10.0),
        Pid.EVAP_PURGE to NormalRange(0.0, 60.0),
    )

    fun of(pid: Pid): NormalRange? = ranges[pid]
}
