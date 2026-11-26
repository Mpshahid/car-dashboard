package com.cardash.dashboard.nav

import com.google.android.gms.maps.model.LatLng
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object GeoUtils {
    private const val R = 6371000.0 // earth radius, metres

    /** Great-circle distance between two points in metres. */
    fun distance(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLng / 2).pow(2)
        return 2 * R * asin(min(1.0, sqrt(h)))
    }

    /** Shortest distance (metres) from point [p] to a polyline [path]. */
    fun distanceToPath(p: LatLng, path: List<LatLng>): Double {
        if (path.isEmpty()) return Double.MAX_VALUE
        if (path.size == 1) return distance(p, path[0])
        var best = Double.MAX_VALUE
        for (i in 0 until path.size - 1) {
            val d = distanceToSegment(p, path[i], path[i + 1])
            if (d < best) best = d
        }
        return best
    }

    private fun distanceToSegment(p: LatLng, a: LatLng, b: LatLng): Double {
        val latRef = Math.toRadians(a.latitude)
        fun x(pt: LatLng) = Math.toRadians(pt.longitude - a.longitude) * cos(latRef) * R
        fun y(pt: LatLng) = Math.toRadians(pt.latitude - a.latitude) * R
        val px = x(p); val py = y(p)
        val bx = x(b); val by = y(b)
        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val cx = t * bx; val cy = t * by
        return sqrt((px - cx).pow(2) + (py - cy).pow(2))
    }
}
