package com.cardash.dashboard.vm

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cardash.dashboard.data.SettingsStore
import com.cardash.dashboard.domain.Units
import com.cardash.dashboard.nav.LocationProvider
import com.cardash.dashboard.nav.NavManager
import com.cardash.dashboard.nav.SpeedLimitManager
import com.cardash.dashboard.obd.ObdManager
import com.cardash.dashboard.obd.Pid
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val locationProvider = LocationProvider(app)

    val state = ObdManager.state
    val imperial = settings.imperial
    val tempF = settings.tempF
    val pinned = settings.pinned

    // Speed limit for the current road (navigation itself runs in Google Maps).
    val speedLimit = SpeedLimitManager.limit

    // Efficiency history (recent MPG samples) for the sparkline
    private val _mpgHistory = MutableStateFlow<List<Float>>(emptyList())
    val mpgHistory = _mpgHistory.asStateFlow()

    init {
        NavManager.init(app)
        com.cardash.dashboard.dashcam.DashcamEngine.init(app)
        com.cardash.dashboard.data.DriveLog.init(app)
        com.cardash.dashboard.data.DriveKeeper.init(app)
        com.cardash.dashboard.web.RideServer.init(app)
        com.cardash.dashboard.ai.VoiceAssistant.init(app)
        com.cardash.dashboard.music.MusicController.init(app)
        viewModelScope.launch {
            while (isActive) {
                delay(2000)
                val l100 = ObdManager.state.value.trip.instantL100
                val mpg = if (l100 > 0) Units.l100ToMpgUk(l100).toFloat() else 0f
                _mpgHistory.update { (it + mpg).takeLast(60) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        // One app-wide location stream for speed-limit lookups.
        NavManager.startLocation()
    }

    fun startDemo() = ObdManager.startDemo(viewModelScope)

    /** On launch: reconnect to the last adapter if there is one (and Bluetooth is usable); else demo. */
    @SuppressLint("MissingPermission")
    fun autoStart() {
        val app = getApplication<Application>()
        val granted = Build.VERSION.SDK_INT < 31 ||
            androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.BLUETOOTH_CONNECT) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val device = settings.lastDeviceAddress
            ?.takeIf { granted && adapter?.isEnabled == true }
            ?.let { runCatching { adapter?.getRemoteDevice(it) }.getOrNull() }
        if (device != null) connect(device) else startDemo()
    }

    /** Forget the adapter so the app no longer reconnects to it on launch. */
    fun forgetAdapter() { settings.lastDeviceAddress = null }
    fun disconnect() = ObdManager.disconnect()

    fun connect(device: BluetoothDevice) {
        settings.lastDeviceAddress = device.address
        ObdManager.connectBluetooth(viewModelScope, device, getApplication())
    }

    fun refreshFaults() = viewModelScope.launch { ObdManager.refreshFaults() }
    fun clearFaults() = viewModelScope.launch { ObdManager.clearFaults() }

    fun setImperial(v: Boolean) {
        settings.setImperial(v)
    }

    fun setTempF(v: Boolean) = settings.setTempF(v)
    fun togglePin(pid: Pid) = settings.togglePin(pid)


    @SuppressLint("MissingPermission")
    // Scan for new (unpaired) adapters and pair from inside the app.
    val btScanner = com.cardash.dashboard.obd.BluetoothScanner(app)

    fun scanForAdapters() = btScanner.start()
    fun stopScan() = btScanner.stop()
    fun pairAndConnect(device: BluetoothDevice) = btScanner.pair(device) { connect(it) }

    override fun onCleared() {
        btScanner.release()
        super.onCleared()
    }

    fun pairedDevices(): List<BluetoothDevice> {
        val mgr = getApplication<Application>().getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = mgr?.adapter ?: return emptyList()
        return runCatching { adapter.bondedDevices?.toList() ?: emptyList() }.getOrDefault(emptyList())
    }
}
