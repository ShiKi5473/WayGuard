package com.wayguard.core.engine

import com.waymap.geo.GeoUtils
import com.waymap.geo.LatLng

/** One location fix as the engines see it. [bearing] is null when the GPS gave none. */
data class Fix(
    val pos: LatLng,
    val speedKmh: Double,
    val bearing: Double?,
    val timeMs: Long,
)

/**
 * Travel direction that survives slow speeds and GPS noise: trusts the GPS bearing
 * only at speed, otherwise derives it from movement of at least [minMoveM], and
 * otherwise keeps the last known value (e.g. while stopped at a light).
 */
class HeadingFilter(
    private val minBearingSpeedKmh: Double = 10.0,
    private val minMoveM: Double = 15.0,
    private val minMoveSpeedKmh: Double = 3.0,
) {
    var heading: Double? = null
        private set
    private var anchor: LatLng? = null

    fun update(fix: Fix): Double? {
        if (fix.bearing != null && fix.speedKmh >= minBearingSpeedKmh) {
            heading = fix.bearing
            anchor = fix.pos
            return heading
        }
        if (fix.speedKmh < minMoveSpeedKmh) {
            // Stationary GPS jitter must not rotate the heading; re-anchor so drift doesn't accumulate.
            anchor = fix.pos
            return heading
        }
        val a = anchor
        if (a == null) {
            anchor = fix.pos
        } else if (GeoUtils.haversine(a, fix.pos) >= minMoveM) {
            heading = GeoUtils.bearing(a, fix.pos)
            anchor = fix.pos
        }
        return heading
    }

    fun reset() {
        heading = null
        anchor = null
    }
}
