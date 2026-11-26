package com.cardash.dashboard.obd

/** How the Sensors tab groups parameters. */
enum class PidGroup(val label: String) {
    ENGINE("Engine"),
    AIR_FUEL("Air & fuel"),
    TEMPERATURES("Temperatures"),
    DRIVER("Throttle & pedal"),
    EMISSIONS("Emissions"),
    ELECTRICAL("Electrical"),
    TRIP("Distance & time"),
}

private fun u16(it: IntArray) = it[0] * 256 + it[1]
private fun s16(it: IntArray) = u16(it).let { v -> if (v >= 0x8000) v - 0x10000 else v }

/**
 * Standard OBD-II (SAE J1979) Mode 01 parameters the dashboard reads.
 * Each entry knows how to turn raw data bytes into a real value. Only the ones the car
 * reports as supported (PID bitmasks 0100/0120/0140/0160) are polled.
 */
enum class Pid(
    val mode: Int,
    val code: Int,
    val bytes: Int,
    val label: String,
    val unit: String,
    val min: Double,
    val max: Double,
    val group: PidGroup,
    val decimals: Int = 0,
    val isTemperature: Boolean = false,
    /** Polled rarely (slow-changing counters / ambient values). */
    val rare: Boolean = false,
    val parse: (IntArray) -> Double,
) {
    // ---- Engine
    ENGINE_RPM(1, 0x0C, 2, "Engine RPM", "rpm", 0.0, 7000.0, PidGroup.ENGINE, parse = { u16(it) / 4.0 }),
    SPEED(1, 0x0D, 1, "Speed", "km/h", 0.0, 220.0, PidGroup.ENGINE, parse = { it[0].toDouble() }),
    ENGINE_LOAD(1, 0x04, 1, "Engine load", "%", 0.0, 100.0, PidGroup.ENGINE, parse = { it[0] * 100.0 / 255.0 }),
    ABS_LOAD(1, 0x43, 2, "Absolute load", "%", 0.0, 200.0, PidGroup.ENGINE, parse = { u16(it) * 100.0 / 255.0 }),
    TIMING_ADVANCE(1, 0x0E, 1, "Timing adv", "°", -64.0, 64.0, PidGroup.ENGINE, decimals = 1, parse = { it[0] / 2.0 - 64.0 }),
    TORQUE_DEMANDED(1, 0x61, 1, "Torque demanded", "%", -125.0, 130.0, PidGroup.ENGINE, parse = { it[0] - 125.0 }),
    TORQUE_ACTUAL(1, 0x62, 1, "Torque actual", "%", -125.0, 130.0, PidGroup.ENGINE, parse = { it[0] - 125.0 }),
    TORQUE_REF(1, 0x63, 2, "Reference torque", "Nm", 0.0, 1000.0, PidGroup.ENGINE, rare = true, parse = { u16(it).toDouble() }),
    RUN_TIME(1, 0x1F, 2, "Run time", "s", 0.0, 65535.0, PidGroup.TRIP, rare = true, parse = { u16(it).toDouble() }),

    // ---- Air & fuel
    MAF(1, 0x10, 2, "Air flow", "g/s", 0.0, 300.0, PidGroup.AIR_FUEL, decimals = 1, parse = { u16(it) / 100.0 }),
    MAP_PRESSURE(1, 0x0B, 1, "Manifold", "kPa", 0.0, 255.0, PidGroup.AIR_FUEL, parse = { it[0].toDouble() }),
    BARO(1, 0x33, 1, "Barometric", "kPa", 0.0, 255.0, PidGroup.AIR_FUEL, rare = true, parse = { it[0].toDouble() }),
    FUEL_PRESSURE(1, 0x0A, 1, "Fuel pressure", "kPa", 0.0, 765.0, PidGroup.AIR_FUEL, parse = { it[0] * 3.0 }),
    FUEL_RAIL_PRESSURE(1, 0x23, 2, "Fuel rail pressure", "kPa", 0.0, 655350.0, PidGroup.AIR_FUEL, parse = { u16(it) * 10.0 }),
    SHORT_FUEL_TRIM(1, 0x06, 1, "S. fuel trim", "%", -100.0, 100.0, PidGroup.AIR_FUEL, parse = { (it[0] - 128) * 100.0 / 128.0 }),
    LONG_FUEL_TRIM(1, 0x07, 1, "L. fuel trim", "%", -100.0, 100.0, PidGroup.AIR_FUEL, parse = { (it[0] - 128) * 100.0 / 128.0 }),
    O2_B1S1_VOLT(1, 0x14, 2, "O2 sensor 1 (front)", "V", 0.0, 1.275, PidGroup.AIR_FUEL, decimals = 2, parse = { it[0] / 200.0 }),
    O2_B1S2_VOLT(1, 0x15, 2, "O2 sensor 2 (rear)", "V", 0.0, 1.275, PidGroup.AIR_FUEL, decimals = 2, parse = { it[0] / 200.0 }),
    LAMBDA_B1S1(1, 0x24, 4, "Lambda (wide-band)", "λ", 0.0, 2.0, PidGroup.AIR_FUEL, decimals = 2, parse = { u16(it) * 2.0 / 65535.0 }),
    LAMBDA_B1S1_CURRENT(1, 0x34, 4, "Lambda (wide-band, current)", "λ", 0.0, 2.0, PidGroup.AIR_FUEL, decimals = 2, parse = { u16(it) / 32768.0 }),
    COMMANDED_LAMBDA(1, 0x44, 2, "Commanded lambda", "λ", 0.0, 2.0, PidGroup.AIR_FUEL, decimals = 2, parse = { u16(it) / 32768.0 }),
    FUEL_LEVEL(1, 0x2F, 1, "Fuel level", "%", 0.0, 100.0, PidGroup.AIR_FUEL, rare = true, parse = { it[0] * 100.0 / 255.0 }),
    FUEL_RATE(1, 0x5E, 2, "Fuel rate", "L/h", 0.0, 60.0, PidGroup.AIR_FUEL, decimals = 1, parse = { u16(it) / 20.0 }),

    // ---- Temperatures
    COOLANT_TEMP(1, 0x05, 1, "Coolant", "°C", -40.0, 140.0, PidGroup.TEMPERATURES, isTemperature = true, parse = { it[0] - 40.0 }),
    INTAKE_TEMP(1, 0x0F, 1, "Intake air", "°C", -40.0, 120.0, PidGroup.TEMPERATURES, isTemperature = true, parse = { it[0] - 40.0 }),
    OIL_TEMP(1, 0x5C, 1, "Oil temp", "°C", -40.0, 160.0, PidGroup.TEMPERATURES, isTemperature = true, parse = { it[0] - 40.0 }),
    AMBIENT_TEMP(1, 0x46, 1, "Outside", "°C", -40.0, 80.0, PidGroup.TEMPERATURES, isTemperature = true, rare = true, parse = { it[0] - 40.0 }),
    CAT_TEMP_B1S1(1, 0x3C, 2, "Catalyst (front)", "°C", -40.0, 1100.0, PidGroup.TEMPERATURES, isTemperature = true, parse = { u16(it) / 10.0 - 40.0 }),
    CAT_TEMP_B1S2(1, 0x3E, 2, "Catalyst (rear)", "°C", -40.0, 1100.0, PidGroup.TEMPERATURES, isTemperature = true, parse = { u16(it) / 10.0 - 40.0 }),

    // ---- Throttle & pedal
    THROTTLE(1, 0x11, 1, "Throttle", "%", 0.0, 100.0, PidGroup.DRIVER, parse = { it[0] * 100.0 / 255.0 }),
    REL_THROTTLE(1, 0x45, 1, "Relative throttle", "%", 0.0, 100.0, PidGroup.DRIVER, parse = { it[0] * 100.0 / 255.0 }),
    THROTTLE_B(1, 0x47, 1, "Throttle B", "%", 0.0, 100.0, PidGroup.DRIVER, parse = { it[0] * 100.0 / 255.0 }),
    COMMANDED_THROTTLE(1, 0x4C, 1, "Commanded throttle", "%", 0.0, 100.0, PidGroup.DRIVER, parse = { it[0] * 100.0 / 255.0 }),
    PEDAL_D(1, 0x49, 1, "Accelerator pedal D", "%", 0.0, 100.0, PidGroup.DRIVER, parse = { it[0] * 100.0 / 255.0 }),
    PEDAL_E(1, 0x4A, 1, "Accelerator pedal E", "%", 0.0, 100.0, PidGroup.DRIVER, parse = { it[0] * 100.0 / 255.0 }),
    REL_PEDAL(1, 0x5A, 1, "Relative pedal", "%", 0.0, 100.0, PidGroup.DRIVER, parse = { it[0] * 100.0 / 255.0 }),

    // ---- Emissions
    COMMANDED_EGR(1, 0x2C, 1, "Commanded EGR", "%", 0.0, 100.0, PidGroup.EMISSIONS, parse = { it[0] * 100.0 / 255.0 }),
    EGR_ERROR(1, 0x2D, 1, "EGR error", "%", -100.0, 100.0, PidGroup.EMISSIONS, parse = { it[0] * 100.0 / 128.0 - 100.0 }),
    EVAP_PURGE(1, 0x2E, 1, "Evap purge", "%", 0.0, 100.0, PidGroup.EMISSIONS, parse = { it[0] * 100.0 / 255.0 }),
    EVAP_PRESSURE(1, 0x32, 2, "Evap vapour pressure", "Pa", -8192.0, 8192.0, PidGroup.EMISSIONS, parse = { s16(it) / 4.0 }),

    // ---- Electrical
    MODULE_VOLTAGE(1, 0x42, 2, "Battery", "V", 0.0, 16.0, PidGroup.ELECTRICAL, decimals = 1, parse = { u16(it) / 1000.0 }),

    // ---- Distance & time
    DIST_SINCE_CLEAR(1, 0x31, 2, "Dist (codes)", "km", 0.0, 65535.0, PidGroup.TRIP, rare = true, parse = { u16(it).toDouble() }),
    DIST_MIL_ON(1, 0x21, 2, "Dist with fault light", "km", 0.0, 65535.0, PidGroup.TRIP, rare = true, parse = { u16(it).toDouble() }),
    WARMUPS_SINCE_CLEAR(1, 0x30, 1, "Warm-ups since clear", "", 0.0, 255.0, PidGroup.TRIP, rare = true, parse = { it[0].toDouble() }),
    TIME_MIL_ON(1, 0x4D, 2, "Time with fault light", "min", 0.0, 65535.0, PidGroup.TRIP, rare = true, parse = { u16(it).toDouble() }),
    TIME_SINCE_CLEAR(1, 0x4E, 2, "Time since codes cleared", "min", 0.0, 65535.0, PidGroup.TRIP, rare = true, parse = { u16(it).toDouble() });

    /** ASCII hex request, e.g. ENGINE_RPM -> "010C". */
    val command: String get() = "%02X%02X".format(mode, code)

    companion object {
        fun fromCode(mode: Int, code: Int): Pid? =
            entries.firstOrNull { it.mode == mode && it.code == code }

        /**
         * The value a car sends when it has no real reading (every bit set), e.g. speed 255 km/h for a few
         * seconds as the ignition goes off. Not a measurement, so it's ignored.
         */
        fun isNoReading(p: Pid, v: Double): Boolean = when (p) {
            SPEED -> v >= 255.0
            ENGINE_RPM -> v >= 16383.0
            else -> p.isTemperature && p.bytes == 1 && v >= 215.0
        }
    }
}
