package com.cardash.dashboard.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.cardash.dashboard.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Google Maps API key the ride replay page uses for Google's map, satellite photos and 3D view.
 *
 * The driver pastes it in Setup ▸ Google Maps key. It's kept encrypted on this phone and handed only to the replay page
 * (which needs the access code), and from there to Google. The source code carries no key. Debug builds also pick up
 * `MAPS_API_KEY` from local.properties (git-ignored) as a fallback; release builds never do, so a shared APK has no key.
 */
object MapsKey {
    private const val FILE = "cardash_maps_key"
    private const val PREF = "maps_api_key"
    private val SHAPE = Regex("AIza[0-9A-Za-z_-]{35}")

    @Volatile private var store: SharedPreferences? = null

    private val _rev = MutableStateFlow(0)
    /** Bumped on every save, so Setup refreshes. */
    val rev: StateFlow<Int> = _rev.asStateFlow()

    /** True when this build carries a key from local.properties (debug builds only). */
    val builtIn: Boolean get() = BuildConfig.MAPS_API_KEY.isNotBlank()

    /** The key saved in Setup, or "" if none. */
    fun saved(context: Context): String = prefs(context).getString(PREF, "").orEmpty()

    /** Saves the key; "" removes it. */
    fun save(context: Context, key: String) {
        prefs(context).edit().putString(PREF, key.trim()).apply()
        _rev.value++
    }

    /** The key the replay page gets: the one saved in Setup, else the build's, else "". */
    fun current(context: Context): String = saved(context).ifBlank { BuildConfig.MAPS_API_KEY }

    /** Google API keys are 39 characters starting with "AIza". */
    fun looksValid(key: String): Boolean = SHAPE.matches(key.trim())

    private fun prefs(context: Context): SharedPreferences =
        store ?: synchronized(this) { store ?: open(context.applicationContext).also { store = it } }

    private fun open(app: Context): SharedPreferences = runCatching {
        EncryptedSharedPreferences.create(
            FILE,
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            app,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse { app.getSharedPreferences("${FILE}_fallback", Context.MODE_PRIVATE) }
}
