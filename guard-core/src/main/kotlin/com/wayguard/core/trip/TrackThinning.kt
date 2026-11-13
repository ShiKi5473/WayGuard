package com.wayguard.core.trip

import com.waymap.geo.GeoUtils
import com.waymap.geo.LatLng

/**
 * Down-samples 1 Hz GPS fixes into compact trip track points:
 * - Drops stationary fixes (`speedKmh < 3.0`) to prevent red-light GPS jitter.
 * - While moving, records when distance >= 10 m, heading changes >= 15°, or >= 5 s elapsed.
 */
object TrackThinning {
    const val MOVING_SPEED_KMH = 3.0
    const val MIN_DISTANCE_M = 10.0
    const val MIN_HEADING_CHANGE_DEG = 15.0
    const val MAX_INTERVAL_MS = 5_000L

    fun shouldRecord(candidate: TrackPoint, lastRecorded: TrackPoint?): Boolean {
        if (candidate.speedKmh < MOVING_SPEED_KMH) return false
        if (lastRecorded == null) return true
        if (candidate.tMs - lastRecorded.tMs >= MAX_INTERVAL_MS) return true
        val distM = GeoUtils.haversine(
            LatLng(lastRecorded.lat, lastRecorded.lon),
            LatLng(candidate.lat, candidate.lon),
        )
        if (distM >= MIN_DISTANCE_M) return true
        val b1 = lastRecorded.bearing
        val b2 = candidate.bearing
        if (b1 != null && b2 != null && GeoUtils.angleDiff(b1.toDouble(), b2.toDouble()) >= MIN_HEADING_CHANGE_DEG) {
            return true
        }
        return false
    }
}

