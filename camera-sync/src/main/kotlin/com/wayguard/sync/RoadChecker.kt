package com.wayguard.sync

import com.wayguard.core.dataset.RoadCheck
import com.waymap.geo.GeoUtils
import com.waymap.geo.LatLng
import kotlin.math.roundToInt

/**
 * Looks up the street each camera stands on (Nominatim reverse) and its direction at
 * the camera. Results feed [com.wayguard.core.dataset.PlaceHeadingRules]; they are
 * cached by camera id, so the weekly run only queries cameras it hasn't seen.
 */
internal class RoadChecker(private val nominatim: Nominatim) {

    /**
     * Checks each (camera id, position). A camera whose lookup failed (network) is
     * left out, so it is retried next run; "no street nearby" is a result and kept.
     */
    fun check(cameras: List<Pair<String, LatLng>>, now: String): Map<String, RoadCheck> {
        val out = LinkedHashMap<String, RoadCheck>()
        for ((id, pos) in cameras) {
            val hit = nominatim.reverseStreet(pos) ?: continue
            val nearest = if (hit.category == "highway") Nominatim.nearestOnLines(hit.lines, pos) else null
            val distance = nearest?.let { GeoUtils.haversine(pos, it.first) }
            out[id] = if (nearest == null || distance!! > MAX_SNAP_M) {
                // A camera far from any mapped street (or a non-street hit) can't be verified.
                RoadCheck(bearing = null, road = hit.name, distanceM = distance?.roundToInt(), lat = pos.lat, lon = pos.lon, checkedAt = now)
            } else {
                val b = (nearest.second.roundToInt() % 360 + 360) % 360
                RoadCheck(bearing = b, road = hit.name, distanceM = distance.roundToInt(), lat = pos.lat, lon = pos.lon, checkedAt = now)
            }
        }
        return out
    }

    private companion object {
        /** Published coordinates sit within a few tens of metres of the road. */
        const val MAX_SNAP_M = 40.0
    }
}
