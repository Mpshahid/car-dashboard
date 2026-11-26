package com.cardash.dashboard.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Finds nearby (unpaired) Bluetooth devices and pairs with them, so the ELM327 can be
 * added from inside the app. Android shows its own PIN prompt (usually 1234 or 0000).
 */
@SuppressLint("MissingPermission")
class BluetoothScanner(context: Context) {
    private val app = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    data class Found(val device: BluetoothDevice, val name: String, val bonded: Boolean, val likelyObd: Boolean)

    private val _found = MutableStateFlow<List<Found>>(emptyList())
    val found: StateFlow<List<Found>> = _found.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** Address currently being paired, if any. */
    private val _pairing = MutableStateFlow<String?>(null)
    val pairing: StateFlow<String?> = _pairing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var onPaired: ((BluetoothDevice) -> Unit)? = null
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> device(intent)?.let { add(it) }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> _scanning.value = true
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> _scanning.value = false
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val d = device(intent) ?: return
                    when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                        BluetoothDevice.BOND_BONDED -> {
                            _pairing.value = null
                            _message.value = "Paired with ${name(d)}"
                            add(d)
                            onPaired?.invoke(d)
                        }
                        BluetoothDevice.BOND_NONE -> if (_pairing.value == d.address) {
                            _pairing.value = null
                            _message.value = "Pairing with ${name(d)} didn't complete. Try again — PIN is usually 1234 or 0000."
                        }
                    }
                }
            }
        }
    }

    val bluetoothOn: Boolean get() = adapter?.isEnabled == true

    fun start() {
        val a = adapter ?: run { _message.value = "This device has no Bluetooth"; return }
        if (!a.isEnabled) { _message.value = "Turn Bluetooth on first"; return }
        register()
        _message.value = null
        // Keep already-paired devices at the top of the list.
        _found.value = runCatching { a.bondedDevices.orEmpty().map { toFound(it) } }.getOrDefault(emptyList()).sortedForObd()
        if (a.isDiscovering) a.cancelDiscovery()
        val ok = runCatching { a.startDiscovery() }.getOrDefault(false)
        if (!ok) _message.value = "Couldn't start scanning — check Bluetooth and location are on"
        _scanning.value = ok
    }

    fun stop() {
        runCatching { adapter?.cancelDiscovery() }
        _scanning.value = false
    }

    /** Pair (bond) with [d]; [then] runs once pairing succeeds (used to connect straight away). */
    fun pair(d: BluetoothDevice, then: (BluetoothDevice) -> Unit) {
        register()
        stop() // discovery slows pairing down
        onPaired = then
        // BLE adapters ("OBDBLE") don't pair with a PIN — connect directly.
        if (d.bondState == BluetoothDevice.BOND_BONDED || BleObdConnection.isBle(d)) { then(d); return }
        _pairing.value = d.address
        _message.value = "Pairing with ${name(d)}… enter PIN 1234 or 0000 if asked"
        if (!runCatching { d.createBond() }.getOrDefault(false)) {
            _pairing.value = null
            _message.value = "Couldn't start pairing with ${name(d)}"
        }
    }

    fun release() {
        stop()
        if (registered) runCatching { app.unregisterReceiver(receiver) }
        registered = false
    }

    private fun register() {
        if (registered) return
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(app, receiver, f, ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    private fun add(d: BluetoothDevice) {
        _found.update { list -> (list.filter { it.device.address != d.address } + toFound(d)).sortedForObd() }
    }

    private fun toFound(d: BluetoothDevice): Found {
        val n = name(d)
        val obd = OBD_HINTS.any { n.contains(it, ignoreCase = true) }
        return Found(d, n, d.bondState == BluetoothDevice.BOND_BONDED, obd)
    }

    private fun List<Found>.sortedForObd() =
        sortedWith(compareByDescending<Found> { it.likelyObd }.thenByDescending { it.bonded }.thenBy { it.name.lowercase() })

    private fun name(d: BluetoothDevice) = runCatching { d.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: d.address

    @Suppress("DEPRECATION")
    private fun device(i: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    companion object {
        private val OBD_HINTS = listOf("OBD", "ELM", "V-LINK", "VLINK", "VEEPEAK", "KONNWEI", "OBDII", "CAN")
    }
}
