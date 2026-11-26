package com.cardash.dashboard.nav

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Navigation is done by the real Google Maps app, opened beside the dashboard (split screen).
 * This object only keeps the phone's location (for speed limits), the saved Home / Work
 * addresses, and hands destinations and searches over to Google Maps.
 */
object NavManager {
    private const val GMAPS = "com.google.android.apps.maps"

    private var initialised = false
    private lateinit var appCtx: Context
    private lateinit var location: LocationProvider
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var locJob: Job? = null

    /** The dashboard's activity, so Google Maps can open beside it (split screen). */
    private var host: WeakReference<Context>? = null
    fun setHost(activity: Context?) { host = activity?.let { WeakReference(it) } }

    private val _here = MutableStateFlow<LatLng?>(null)
    val here: StateFlow<LatLng?> = _here.asStateFlow()

    /** Latest GPS speed (m/s) and heading (degrees), for the ride recorder. */
    @Volatile var gpsSpeedMps: Float? = null
        private set
    @Volatile var gpsBearing: Float? = null
        private set
    /** When the latest GPS fix arrived (SystemClock.elapsedRealtime). */
    @Volatile var gpsFixAt: Long = 0L
        private set
    /** Height above sea level (m) from the latest fix that had one. */
    @Volatile var gpsAltitudeMsl: Double? = null
        private set

    /** GPS speed (m/s), or null when there's been no fix for [maxAgeMs]. */
    fun freshGpsSpeedMps(maxAgeMs: Long = 4_000): Float? =
        gpsSpeedMps?.takeIf { android.os.SystemClock.elapsedRealtime() - gpsFixAt <= maxAgeMs }

    private val _home = MutableStateFlow("")
    val home: StateFlow<String> = _home.asStateFlow()
    private val _work = MutableStateFlow("")
    val work: StateFlow<String> = _work.asStateFlow()

    private val prefs get() = appCtx.getSharedPreferences("cardash_nav", Context.MODE_PRIVATE)

    fun init(context: Context) {
        if (initialised) return
        appCtx = context.applicationContext
        location = LocationProvider(appCtx)
        _home.value = prefs.getString("home", "").orEmpty()
        _work.value = prefs.getString("work", "").orEmpty()
        initialised = true
    }

    fun setHome(address: String) { _home.value = address.trim(); prefs.edit().putString("home", _home.value).apply() }
    fun setWork(address: String) { _work.value = address.trim(); prefs.edit().putString("work", _work.value).apply() }

    /** One app-wide location stream (needs location permission): drives the speed-limit lookup. */
    fun startLocation() {
        if (!initialised || locJob != null) return
        locJob = scope.launch {
            location.lastLocation()?.let { if (_here.value == null) _here.value = it }
            location.fixes().collect { fix ->
                _here.value = fix.latLng
                gpsSpeedMps = fix.speedMps
                gpsBearing = fix.bearing
                gpsFixAt = android.os.SystemClock.elapsedRealtime()
                fix.altitudeMsl?.let { gpsAltitudeMsl = it }
                SpeedLimitManager.update(scope, fix.latLng)
            }
        }
    }

    fun googleMapsInstalled(): Boolean =
        initialised && runCatching { appCtx.packageManager.getPackageInfo(GMAPS, 0) }.isSuccess

    /** Open Google Maps beside the dashboard. */
    fun openGoogleMaps(context: Context? = null): Boolean {
        val c = context ?: host?.get() ?: appCtx
        val i = c.packageManager.getLaunchIntentForPackage(GMAPS) ?: return false
        return launch(c, i)
    }

    /** Turn-by-turn driving directions in Google Maps to an address, place name or postcode. */
    fun navigate(destination: String): Boolean {
        val d = destination.trim()
        if (d.isEmpty()) return false
        return launch(null, Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(d)}&mode=d")))
    }

    /** Show "petrol station", "parking"… near the car in Google Maps. */
    fun searchNearby(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return false
        val at = _here.value
        val geo = if (at != null) "geo:${at.latitude},${at.longitude}" else "geo:0,0"
        return launch(null, Intent(Intent.ACTION_VIEW, Uri.parse("$geo?q=${Uri.encode(q)}")))
    }

    private fun launch(context: Context?, intent: Intent): Boolean {
        if (!initialised) return false
        val c = context ?: host?.get() ?: appCtx
        intent.setPackage(GMAPS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        return runCatching { c.startActivity(intent) }.isSuccess
    }
}
