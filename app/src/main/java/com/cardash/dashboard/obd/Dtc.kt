package com.cardash.dashboard.obd

/** Diagnostic Trouble Code decoding (SAE J2012). */
object Dtc {

    private val letters = charArrayOf('P', 'C', 'B', 'U')

    /** Decode a pair of raw bytes into a code like "P0301". Returns null for 0000 padding. */
    fun decode(b1: Int, b2: Int): String? {
        if (b1 == 0 && b2 == 0) return null
        val letter = letters[(b1 shr 6) and 0x03]
        val d1 = (b1 shr 4) and 0x03
        val d2 = b1 and 0x0F
        val d3 = (b2 shr 4) and 0x0F
        val d4 = b2 and 0x0F
        return "%c%d%X%X%X".format(letter, d1, d2, d3, d4)
    }

    /**
     * Plain-English description for a code. Covers the common generic codes;
     * anything unknown falls back to a sensible category label so the user still
     * sees something useful.
     */
    fun describe(code: String): String {
        known[code]?.let { return it }
        return category(code)
    }

    private fun category(code: String): String {
        if (code.length < 2) return "Unknown code"
        val area = when (code[0]) {
            'P' -> "Powertrain"
            'C' -> "Chassis"
            'B' -> "Body"
            'U' -> "Network"
            else -> "Unknown"
        }
        return "$area fault — look up $code"
    }

    private val known = mapOf(
        "P0010" to "Camshaft position actuator circuit (bank 1)",
        "P0011" to "Camshaft position — timing over-advanced (bank 1)",
        "P0030" to "O2 sensor heater control circuit (bank 1, sensor 1)",
        "P0100" to "Mass air flow circuit malfunction",
        "P0101" to "Mass air flow circuit range/performance",
        "P0105" to "Manifold absolute pressure circuit",
        "P0110" to "Intake air temperature circuit",
        "P0115" to "Engine coolant temperature circuit",
        "P0120" to "Throttle position sensor circuit",
        "P0128" to "Coolant thermostat below regulating temperature",
        "P0130" to "O2 sensor circuit (bank 1, sensor 1)",
        "P0133" to "O2 sensor slow response (bank 1, sensor 1)",
        "P0171" to "System too lean (bank 1)",
        "P0172" to "System too rich (bank 1)",
        "P0300" to "Random/multiple cylinder misfire detected",
        "P0301" to "Cylinder 1 misfire detected",
        "P0302" to "Cylinder 2 misfire detected",
        "P0303" to "Cylinder 3 misfire detected",
        "P0304" to "Cylinder 4 misfire detected",
        "P0351" to "Ignition coil A primary/secondary circuit",
        "P0401" to "Exhaust gas recirculation flow insufficient",
        "P0420" to "Catalyst system efficiency below threshold (bank 1)",
        "P0442" to "Evaporative emission system small leak",
        "P0455" to "Evaporative emission system gross leak",
        "P0500" to "Vehicle speed sensor",
        "P0506" to "Idle control system RPM lower than expected",
        "P0507" to "Idle control system RPM higher than expected",
        "P0601" to "Internal control module memory check-sum error",
        "U0100" to "Lost communication with ECM/PCM",
        "U0121" to "Lost communication with ABS control module",
    )
}
