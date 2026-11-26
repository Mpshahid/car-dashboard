package com.cardash.dashboard.data

import android.content.Context
import com.cardash.dashboard.obd.Pid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small persistent store for unit preferences and pinned dashboard tiles. */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("cardash", Context.MODE_PRIVATE)

    private val _imperial = MutableStateFlow(prefs.getBoolean(KEY_IMPERIAL, true))
    val imperial: StateFlow<Boolean> = _imperial.asStateFlow()

    private val _tempF = MutableStateFlow(prefs.getBoolean(KEY_TEMP_F, false))
    val tempF: StateFlow<Boolean> = _tempF.asStateFlow()

    private val _pinned = MutableStateFlow(loadPinned())
    val pinned: StateFlow<List<Pid>> = _pinned.asStateFlow()

    fun setImperial(v: Boolean) {
        prefs.edit().putBoolean(KEY_IMPERIAL, v).apply()
        _imperial.value = v
    }

    fun setTempF(v: Boolean) {
        prefs.edit().putBoolean(KEY_TEMP_F, v).apply()
        _tempF.value = v
    }

    fun togglePin(pid: Pid) {
        val current = _pinned.value.toMutableList()
        if (!current.remove(pid)) current.add(pid)
        prefs.edit().putString(KEY_PINNED, current.joinToString(",") { it.name }).apply()
        _pinned.value = current
    }

    var lastDeviceAddress: String?
        get() = prefs.getString(KEY_LAST_DEVICE, null)
        set(value) {
            prefs.edit().putString(KEY_LAST_DEVICE, value).apply()
        }

    private fun loadPinned(): List<Pid> {
        val raw = prefs.getString(KEY_PINNED, null) ?: return DEFAULT_PINNED
        val list = raw.split(",").mapNotNull { name ->
            runCatching { Pid.valueOf(name.trim()) }.getOrNull()
        }
        return list.ifEmpty { DEFAULT_PINNED }
    }

    companion object {
        private const val KEY_IMPERIAL = "imperial"
        private const val KEY_TEMP_F = "tempF"
        private const val KEY_PINNED = "pinned"
        private const val KEY_LAST_DEVICE = "lastDevice"

        val DEFAULT_PINNED = listOf(
            Pid.COOLANT_TEMP,
            Pid.MODULE_VOLTAGE,
            Pid.ENGINE_LOAD,
            Pid.INTAKE_TEMP,
            Pid.FUEL_LEVEL,
            Pid.THROTTLE,
        )
    }
}
