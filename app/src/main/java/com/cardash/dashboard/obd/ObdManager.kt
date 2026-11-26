package com.cardash.dashboard.obd

import android.bluetooth.BluetoothDevice
import com.cardash.dashboard.domain.GearEstimator
import com.cardash.dashboard.domain.Units
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the connection (real or demo), runs the polling loop, and publishes a
 * single [VehicleState] the whole UI observes.
 */
object ObdManager {

    private val _state = MutableStateFlow(VehicleState())
    val state: StateFlow<VehicleState> = _state.asStateFlow()

    private var job: Job? = null
    private var elm: Elm327? = null
    private val gearEstimator = GearEstimator()

    // Trip accumulators
    private var tripDistanceKm = 0.0
    private var tripSeconds = 0.0
    private var tripMovingSeconds = 0.0
    private var tripFuelL = 0.0
    private var tripMaxSpeed = 0.0

    fun startDemo(scope: CoroutineScope) {
        stop()
        resetTrip()
        _state.value = VehicleState(
            status = ConnectionStatus.Connected("Demo mode"),
            demo = true,
            supportedPids = DemoSimulator.SUPPORTED,
            readiness = DemoSimulator.READINESS,
        )
        SensorHistory.clear()
        val sim = DemoSimulator()
        DemoGps.reset()
        DemoGps.advance(0.0, 0.0)
        job = scope.launch(Dispatchers.Default) {
            var last = System.currentTimeMillis()
            while (isActive) {
                delay(160)
                val now = System.currentTimeMillis()
                val dt = (now - last) / 1000.0
                last = now
                val readings = sim.tick(dt)
                DemoGps.advance(readings[Pid.SPEED] ?: 0.0, dt)   // the demo car drives a real road loop
                publish(readings, dt)
            }
        }
    }

    fun connectBluetooth(scope: CoroutineScope, device: BluetoothDevice, context: android.content.Context) {
        stop()
        resetTrip()
        SensorHistory.clear()
        val name = runCatching { device.name }.getOrNull() ?: device.address
        _state.value = VehicleState(status = ConnectionStatus.Connecting, adapterAddress = device.address)
        job = scope.launch(Dispatchers.IO) {
            var attempt = 0
            var everConnected = false
            while (isActive) {
                // Dual-mode ELM327s show up twice: "OBDII" (classic, PIN) and "OBDBLE" (Low Energy, no PIN).
                val conn: ObdConnection =
                    if (BleObdConnection.isBle(device)) BleObdConnection(context.applicationContext, device)
                    else BluetoothObdConnection(device)
                val e = Elm327(conn)
                elm = e
                try {
                    conn.open()
                    e.initialise()
                    val supported = e.supportedPids().ifEmpty { DEFAULT_SUPPORTED }
                    if (Pid.SPEED in supported && Pid.ENGINE_RPM in supported) e.probeMultiPid(Pid.ENGINE_RPM, Pid.SPEED)
                    everConnected = true
                    attempt = 0
                    _state.update {
                        it.copy(
                            status = ConnectionStatus.Connected(conn.name),
                            demo = false,
                            supportedPids = supported,
                            adapterAddress = device.address,
                        )
                    }
                    // Identity + emissions readiness (both optional on some cars); not repeated on reconnect.
                    if (_state.value.vin == null) {
                        val vin = runCatching { e.readVin() }.getOrNull()
                        _state.update { it.copy(vin = vin) }
                    }
                    if (_state.value.readiness == null) {
                        val readiness = runCatching { e.readReadiness() }.getOrNull()
                        _state.update { it.copy(readiness = readiness, milOn = readiness?.milOn ?: it.milOn) }
                    }
                    poll(e, supported)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    conn.close()
                    throw ce
                } catch (ex: Exception) {
                    conn.close()
                    elm = null
                    attempt++
                    val reason = ex.message ?: "Connection lost"
                    android.util.Log.w("ObdManager", "Link problem (attempt $attempt): $reason")
                    if (!everConnected && attempt >= 3) {
                        _state.update { it.copy(status = ConnectionStatus.Error(reason)) }
                        return@launch
                    }
                    // The link dropped (engine cranking, adapter glitch, phone Bluetooth hiccup): try again.
                    _state.update { it.copy(status = ConnectionStatus.Reconnecting(name, attempt, reason)) }
                    delay(minOf(1500L * attempt, 12_000L))
                }
            }
        }
    }

    /**
     * Live polling. With multi-PID support (CAN cars) every live reading is fetched in batches of
     * six, so a full sweep of ~30 values takes about half a second: everything refreshes ≥ 1×/s.
     * Without it, speed and RPM every cycle and the rest in rotation (the older behaviour).
     */
    private suspend fun kotlinx.coroutines.CoroutineScope.poll(e: Elm327, supported: Set<Pid>) {
        val fast = listOf(Pid.ENGINE_RPM, Pid.SPEED).filter { it in supported }
        val live = fast + supported.filter { it !in fast && !it.rare }.sortedBy { it.ordinal }
        val rare = supported.filter { it.rare }
        var idx = 0
        var rareIdx = 0
        var cycle = 0L
        var lastReadiness = System.currentTimeMillis()
        var last = System.currentTimeMillis()
        // When each reading last came back from the car. Once the engine or ignition is off the car
        // answers "NO DATA", and those readings are dropped instead of being shown frozen: speed and
        // RPM disappear (so "engine off" can be detected) and the trip stops counting distance.
        val seenAt = HashMap<Pid, Long>()
        while (isActive) {
            val fresh = HashMap<Pid, Double>()
            if (e.multiPid) {
                live.chunked(6).forEach { batch -> fresh.putAll(e.readMany(batch)) }
                if (rare.isNotEmpty() && cycle % 8 == 0L) rare.chunked(6).forEach { fresh.putAll(e.readMany(it)) }
            } else {
                for (p in fast) e.read(p)?.let { fresh[p] = it }
                val slow = live.filter { it !in fast }
                if (slow.isNotEmpty()) repeat(3) {
                    val p = slow[idx % slow.size]; idx++
                    e.read(p)?.let { fresh[p] = it }
                }
                if (rare.isNotEmpty() && cycle % 6 == 0L) {
                    val p = rare[rareIdx % rare.size]; rareIdx++
                    e.read(p)?.let { fresh[p] = it }
                }
            }
            // "No reading" answers (e.g. speed 255 km/h as the ignition goes off) aren't values.
            fresh.entries.removeAll { (p, v) -> Pid.isNoReading(p, v) }
            val readAt = System.currentTimeMillis()
            fresh.keys.forEach { seenAt[it] = readAt }
            val readings = HashMap(_state.value.readings)
            readings.putAll(fresh)
            readings.keys.retainAll { p ->
                val maxAge = when { p in fast -> 4_000L; p.rare -> 120_000L; else -> 20_000L }
                readAt - (seenAt[p] ?: 0L) <= maxAge
            }
            cycle++
            if (System.currentTimeMillis() - lastReadiness > 60_000) {
                lastReadiness = System.currentTimeMillis()
                runCatching { e.readReadiness() }.getOrNull()?.let { r ->
                    _state.update { it.copy(readiness = r, milOn = r.milOn) }
                }
            }
            val now = System.currentTimeMillis()
            val dt = (now - last) / 1000.0; last = now
            publish(readings, dt)
            // Ignition off / car asleep answers "NO DATA" quickly; don't spin flat out.
            if (fresh.isEmpty()) delay(500)
        }
    }

    suspend fun refreshFaults() {
        val e = elm ?: run {
            if (_state.value.demo) _state.update { it.copy(faults = emptyList(), milOn = false) }
            return
        }
        val codes = runCatching { e.readFaults() }.getOrDefault(emptyList())
        _state.update { it.copy(faults = codes, milOn = codes.isNotEmpty()) }
    }

    suspend fun clearFaults() {
        elm?.let { runCatching { it.clearFaults() } }
        _state.update { it.copy(faults = emptyList(), milOn = false) }
    }

    fun stop() {
        job?.cancel()
        job = null
        elm = null
        DemoGps.reset()
    }

    fun disconnect() {
        stop()
        _state.value = VehicleState(status = ConnectionStatus.Disconnected)
    }

    private fun resetTrip() {
        gearEstimator.reset()
        tripDistanceKm = 0.0
        tripSeconds = 0.0
        tripMovingSeconds = 0.0
        tripFuelL = 0.0
        tripMaxSpeed = 0.0
    }

    private fun publish(readings: Map<Pid, Double>, dt: Double) {
        val speed = readings[Pid.SPEED] ?: 0.0
        val rpm = readings[Pid.ENGINE_RPM] ?: 0.0
        val maf = readings[Pid.MAF]
        val gear = gearEstimator.estimate(speed, rpm, android.os.SystemClock.elapsedRealtime())

        if (dt in 0.0..2.0) {
            tripSeconds += dt
            tripDistanceKm += speed / 3600.0 * dt
            if (speed > 1.0) tripMovingSeconds += dt
            if (maf != null) tripFuelL += Units.petrolLitresPerHour(maf) * dt / 3600.0
            if (speed > tripMaxSpeed) tripMaxSpeed = speed
        }
        val avg = if (tripMovingSeconds > 1.0) tripDistanceKm / (tripMovingSeconds / 3600.0) else 0.0
        val l100 = if (maf != null) Units.petrolL100(maf, speed) else 0.0

        val trip = TripStats(
            distanceKm = tripDistanceKm,
            durationSec = tripSeconds.toLong(),
            avgSpeedKmh = avg,
            maxSpeedKmh = tripMaxSpeed,
            fuelUsedL = tripFuelL,
            instantL100 = l100,
        )
        _state.update { it.copy(readings = readings, gear = gear, trip = trip) }
        SensorHistory.record(readings)
    }

    private val DEFAULT_SUPPORTED = setOf(
        Pid.SPEED, Pid.ENGINE_RPM, Pid.COOLANT_TEMP, Pid.ENGINE_LOAD,
        Pid.THROTTLE, Pid.INTAKE_TEMP, Pid.MODULE_VOLTAGE,
    )
}
