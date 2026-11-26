package com.cardash.dashboard.obd

sealed interface ConnectionStatus {
    data object Disconnected : ConnectionStatus
    data object Connecting : ConnectionStatus
    data class Connected(val adapter: String) : ConnectionStatus
    data class Error(val message: String) : ConnectionStatus
    /** The link dropped; trying again automatically. */
    data class Reconnecting(val adapter: String, val attempt: Int, val reason: String) : ConnectionStatus
}

/** Estimated gear for a manual gearbox, derived from speed and RPM. */
sealed interface GearReading {
    data class Engaged(val gear: Int, val shiftUp: Boolean) : GearReading
    data object Neutral : GearReading
    data object Unknown : GearReading
}

enum class DtcStatus { STORED, PENDING, PERMANENT }

/** One emissions self-test ("readiness monitor") — what an MOT emissions check looks at. */
data class Monitor(val name: String, val complete: Boolean)

/** Mode 01 PID 01: check-engine light, stored-code count and readiness monitors. */
data class Readiness(
    val milOn: Boolean,
    val dtcCount: Int,
    val monitors: List<Monitor>,
) {
    val incomplete: List<Monitor> get() = monitors.filter { !it.complete }
}

data class DtcCode(
    val code: String,
    val description: String,
    val status: DtcStatus,
)

data class TripStats(
    val distanceKm: Double = 0.0,
    val durationSec: Long = 0L,
    val avgSpeedKmh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val fuelUsedL: Double = 0.0,
    val instantL100: Double = 0.0,
)

/** The single source of truth the whole UI renders from. */
data class VehicleState(
    val status: ConnectionStatus = ConnectionStatus.Disconnected,
    val demo: Boolean = false,
    val readings: Map<Pid, Double> = emptyMap(),
    val supportedPids: Set<Pid> = emptySet(),
    val gear: GearReading = GearReading.Unknown,
    val faults: List<DtcCode> = emptyList(),
    val milOn: Boolean = false,
    val trip: TripStats = TripStats(),
    val readiness: Readiness? = null,
    val vin: String? = null,
    /** Bluetooth address of the adapter in use (real connection only). */
    val adapterAddress: String? = null,
) {
    val connected: Boolean get() = status is ConnectionStatus.Connected
    fun value(pid: Pid): Double? = readings[pid]
}
