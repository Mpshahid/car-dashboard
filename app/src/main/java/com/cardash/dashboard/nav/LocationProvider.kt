package com.cardash.dashboard.nav

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@SuppressLint("MissingPermission")
class LocationProvider(context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)

    suspend fun lastLocation(): LatLng? = suspendCancellableCoroutine { cont ->
        client.lastLocation
            .addOnSuccessListener { loc -> cont.resume(loc?.let { LatLng(it.latitude, it.longitude) }) }
            .addOnFailureListener { cont.resume(null) }
    }

    /**
     * Position plus heading (degrees), speed (m/s) and height above sea level (m) when the GPS provides them.
     * Height is what Google's 3D map needs to aim its camera at the ground under the car.
     */
    data class Fix(val latLng: LatLng, val bearing: Float?, val speedMps: Float?, val altitudeMsl: Double? = null)

    fun fixes(intervalMs: Long = 1000L): Flow<Fix> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(500L)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let {
                    trySend(
                        Fix(
                            LatLng(it.latitude, it.longitude),
                            if (it.hasBearing()) it.bearing else null,
                            if (it.hasSpeed()) it.speed else null,
                            // Android 14+ gives height above sea level; older phones only above the GPS ellipsoid,
                            // which in Britain sits about 47 m above sea level.
                            when {
                                android.os.Build.VERSION.SDK_INT >= 34 && it.hasMslAltitude() -> it.mslAltitudeMeters
                                it.hasAltitude() -> it.altitude - 47.0
                                else -> null
                            },
                        ),
                    )
                }
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }

    fun updates(intervalMs: Long = 1500L): Flow<LatLng> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(1000L)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(LatLng(it.latitude, it.longitude)) }
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }
}
