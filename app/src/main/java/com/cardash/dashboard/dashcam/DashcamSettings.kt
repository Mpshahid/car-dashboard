package com.cardash.dashboard.dashcam

import android.content.Context
import androidx.camera.video.Quality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Recording quality presets. The video bitrate is fixed so clip sizes are predictable; the sizes
 * shown include sound (about 0.7 MB a minute). [fps] lowers the frame rate (null = the camera's
 * normal rate): at a very low bitrate fewer frames means each one is sharper, like CCTV.
 */
enum class DashcamQuality(val label: String, val detail: String, val quality: Quality, val bitrate: Int, val fps: Int? = null) {
    CCTV("CCTV", "480p (SD) · 15 fps · about 3 MB a minute", Quality.SD, 350_000, 15),
    SMALL("Small", "480p (SD) · about 6 MB a minute", Quality.SD, 700_000),
    STANDARD("Standard", "720p (HD) · about 12 MB a minute", Quality.HD, 1_500_000),
    HIGH("High", "1080p (Full HD) · about 30 MB a minute", Quality.FHD, 4_000_000),
}

/** Persistent dashcam preferences (shares the app's "cardash" SharedPreferences file). */
class DashcamSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cardash", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(K_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _autoRecord = MutableStateFlow(prefs.getBoolean(K_AUTO, true))
    val autoRecord: StateFlow<Boolean> = _autoRecord.asStateFlow()

    private val _audio = MutableStateFlow(prefs.getBoolean(K_AUDIO, true))
    val audio: StateFlow<Boolean> = _audio.asStateFlow()

    private val _dual = MutableStateFlow(prefs.getBoolean(K_DUAL, true))
    val dual: StateFlow<Boolean> = _dual.asStateFlow()

    private val _wide = MutableStateFlow(prefs.getBoolean(K_WIDE, true))
    val wide: StateFlow<Boolean> = _wide.asStateFlow()

    // A new key: everyone starts on CCTV (the user asked for much smaller clips), then their own choice sticks.
    private val _quality = MutableStateFlow(
        runCatching { DashcamQuality.valueOf(prefs.getString(K_QUALITY, null) ?: "") }.getOrDefault(DashcamQuality.CCTV),
    )
    val quality: StateFlow<DashcamQuality> = _quality.asStateFlow()

    /** Days to keep clips; 0 = keep forever. */
    private val _retentionDays = MutableStateFlow(prefs.getInt(K_RETENTION, 7))
    val retentionDays: StateFlow<Int> = _retentionDays.asStateFlow()

    fun setEnabled(v: Boolean) { prefs.edit().putBoolean(K_ENABLED, v).apply(); _enabled.value = v }
    fun setAutoRecord(v: Boolean) { prefs.edit().putBoolean(K_AUTO, v).apply(); _autoRecord.value = v }
    fun setAudio(v: Boolean) { prefs.edit().putBoolean(K_AUDIO, v).apply(); _audio.value = v }
    fun setDual(v: Boolean) { prefs.edit().putBoolean(K_DUAL, v).apply(); _dual.value = v }
    fun setWide(v: Boolean) { prefs.edit().putBoolean(K_WIDE, v).apply(); _wide.value = v }
    fun setQuality(v: DashcamQuality) { prefs.edit().putString(K_QUALITY, v.name).apply(); _quality.value = v }
    fun setRetentionDays(v: Int) { prefs.edit().putInt(K_RETENTION, v).apply(); _retentionDays.value = v }

    companion object {
        val RETENTION_OPTIONS = listOf(1, 3, 7, 30, 0)
        private const val K_ENABLED = "dc_enabled"
        private const val K_AUTO = "dc_auto"
        private const val K_AUDIO = "dc_audio"
        private const val K_DUAL = "dc_dual"
        private const val K_WIDE = "dc_wide"
        private const val K_QUALITY = "dc_quality_v2"
        private const val K_RETENTION = "dc_retention_days"
    }
}
