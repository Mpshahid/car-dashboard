package com.cardash.dashboard.obd

import kotlinx.coroutines.delay

/** Speaks the ELM327 command set over an [ObdConnection] and returns typed results. */
class Elm327(private val conn: ObdConnection) {

    suspend fun initialise() {
        conn.send("ATZ")   // reset
        delay(200)
        conn.send("ATE0")  // echo off
        conn.send("ATL0")  // linefeeds off
        conn.send("ATH0")  // headers off
        conn.send("ATSP0") // automatic protocol
        runCatching { conn.send("ATAT2") } // faster adaptive timing (ignored by clones that lack it)
        conn.send("0100")  // wake up / confirm link
    }

    /** True once the car has answered a multi-PID request (CAN cars do; older protocols don't). */
    var multiPid = false
        private set

    /** Check whether several PIDs can be asked for in one request (6 per request on CAN). */
    suspend fun probeMultiPid(a: Pid, b: Pid) {
        multiPid = runCatching { readMany(listOf(a, b)).size == 2 }.getOrDefault(false)
    }

    /**
     * Read up to 6 Mode 01 PIDs in one request ("010C0D0511…") — far faster than one at a time
     * over Bluetooth. The reply lists each PID followed by its data bytes.
     */
    suspend fun readMany(pids: List<Pid>): Map<Pid, Double> {
        if (pids.isEmpty()) return emptyMap()
        if (pids.size == 1 || pids.any { it.mode != 1 }) {
            return pids.mapNotNull { p -> read(p)?.let { p to it } }.toMap()
        }
        val raw = conn.send("01" + pids.joinToString("") { "%02X".format(it.code) })
        val bytes = hexBytes(raw) ?: return emptyMap()
        val byCode = pids.associateBy { it.code }
        val out = HashMap<Pid, Double>()
        var i = 0
        while (i < bytes.size) {
            if (bytes[i] != 0x41) { i++; continue }
            // Walk "41 <pid> <data…> <pid> <data…>" until an unknown byte (padding / next frame).
            var j = i + 1
            while (j < bytes.size) {
                val p = byCode[bytes[j]] ?: break
                val data = bytes.subList(j + 1, minOf(bytes.size, j + 1 + p.bytes))
                if (data.size < p.bytes) break
                runCatching { p.parse(data.toIntArray()) }.getOrNull()?.let { out[p] = it }
                j += 1 + p.bytes
            }
            i = j
        }
        return out
    }

    /** Read one PID; null if unsupported or no data returned. */
    suspend fun read(pid: Pid): Double? {
        val raw = conn.send(pid.command)
        val data = parseData(raw, pid.mode, pid.code, pid.bytes) ?: return null
        return runCatching { pid.parse(data) }.getOrNull()
    }

    /** Query the Mode 01 "supported PIDs" bitmasks, mapped to the PIDs we know. */
    suspend fun supportedPids(): Set<Pid> {
        val supported = mutableSetOf<Pid>()
        for (base in intArrayOf(0x00, 0x20, 0x40, 0x60)) {
            val cmd = "%02X%02X".format(1, base)
            val raw = conn.send(cmd)
            val data = parseData(raw, 1, base, 4) ?: continue
            var bits = 0L
            for (b in data) bits = (bits shl 8) or (b.toLong() and 0xFF)
            for (i in 0 until 32) {
                val on = (bits shr (31 - i)) and 1L == 1L
                if (!on) continue
                Pid.fromCode(1, base + i + 1)?.let { supported.add(it) }
            }
        }
        return supported
    }

    /** Mode 01 PID 01 — check-engine light, code count, and readiness monitors (petrol engine layout). */
    suspend fun readReadiness(): Readiness? {
        val d = parseData(conn.send("0101"), 1, 0x01, 4) ?: return null
        val (a, b, c, dd) = d.toList()
        fun bit(x: Int, n: Int) = (x shr n) and 1 == 1
        val monitors = buildList {
            // Continuous monitors (byte B): bit0-2 supported, bit4-6 incomplete.
            if (bit(b, 0)) add(Monitor("Misfire", !bit(b, 4)))
            if (bit(b, 1)) add(Monitor("Fuel system", !bit(b, 5)))
            if (bit(b, 2)) add(Monitor("Components", !bit(b, 6)))
            // Non-continuous, spark ignition (byte C supported, byte D incomplete).
            val names = listOf("Catalyst", "Heated catalyst", "Evap system", "Secondary air", "A/C refrigerant", "O2 sensor", "O2 sensor heater", "EGR system")
            for (i in 0..7) if (bit(c, i)) add(Monitor(names[i], !bit(dd, i)))
        }
        return Readiness(milOn = bit(a, 7), dtcCount = a and 0x7F, monitors = monitors)
    }

    /** Mode 09 PID 02 — Vehicle Identification Number (multi-frame); null if the car won't say. */
    suspend fun readVin(): String? {
        val raw = conn.send("0902")
        val bytes = hexBytes(raw) ?: return null
        // Strip ISO-TP frame indexes / headers: keep printable ASCII that can appear in a VIN.
        val text = bytes.filter { it in 0x30..0x5A }.map { it.toChar() }.joinToString("")
        val vin = Regex("[A-HJ-NPR-Z0-9]{17}").findAll(text).lastOrNull()?.value
        return vin
    }

    suspend fun readFaults(): List<DtcCode> =
        readCodes("03", DtcStatus.STORED) + readCodes("07", DtcStatus.PENDING)

    suspend fun clearFaults() {
        conn.send("04")
    }

    private suspend fun readCodes(mode: String, status: DtcStatus): List<DtcCode> {
        val raw = conn.send(mode)
        val bytes = hexBytes(raw) ?: return emptyList()
        val respByte = mode.toInt(16) + 0x40
        val start = bytes.indexOf(respByte)
        if (start < 0) return emptyList()
        val payload = bytes.drop(start + 1)
        val out = mutableListOf<DtcCode>()
        var i = 0
        while (i + 1 < payload.size) {
            val code = Dtc.decode(payload[i], payload[i + 1])
            if (code != null) out.add(DtcCode(code, Dtc.describe(code), status))
            i += 2
        }
        return out
    }

    companion object {
        /** Extract the data bytes for a Mode/PID reply from an ELM text response. */
        fun parseData(raw: String, mode: Int, code: Int, dataBytes: Int): IntArray? {
            val bytes = hexBytes(raw) ?: return null
            val respMode = mode + 0x40
            var i = 0
            while (i + 1 < bytes.size) {
                if (bytes[i] == respMode && bytes[i + 1] == code) {
                    val data = bytes.drop(i + 2).take(dataBytes)
                    return if (data.size < dataBytes) null else data.toIntArray()
                }
                i++
            }
            return null
        }

        /** Parse hex byte tokens from an ELM response, tolerating spaces or none. */
        fun hexBytes(raw: String): List<Int>? {
            val cleaned = raw.uppercase()
                .replace(">", " ")
                .replace("\r", " ")
                .replace("\n", " ")
            val out = ArrayList<Int>()
            for (t in cleaned.trim().split(Regex("\\s+"))) {
                if (t.isEmpty()) continue
                if (t.length % 2 == 0 && t.all { it.isHex() }) {
                    var j = 0
                    while (j + 1 < t.length) {
                        out.add(t.substring(j, j + 2).toInt(16))
                        j += 2
                    }
                }
            }
            return if (out.isEmpty()) null else out
        }

        private fun Char.isHex() = this in '0'..'9' || this in 'A'..'F'
    }
}
