package com.wayguard.core.model

import com.waymap.geo.GeoUtils
import com.waymap.geo.LatLng
import kotlinx.serialization.Serializable

/**
 * A camera the user marked as a false alarm / removed. Matched by [id], or — when a
 * data update re-keyed the camera (e.g. its address text changed) — by position and
 * directions.
 */
@Serializable
data class SuppressedMark(
    val id: String,
    val lat: Double,
    val lon: Double,
    val headings: List<Int> = emptyList(),
) {
    val pos: LatLng get() = LatLng(lat, lon)

    companion object {
        fun of(c: SpeedCamera) = SuppressedMark(c.id, c.lat, c.lon, c.headings)
    }
}

object Suppression {

    /** Within this distance and with the same directions, a re-keyed camera is the same one. */
    const val SAME_SPOT_M = 30.0

    fun matches(c: SpeedCamera, mark: SuppressedMark): Boolean =
        c.id == mark.id ||
            (c.headings.sorted() == mark.headings.sorted() && GeoUtils.haversine(c.pos, mark.pos) <= SAME_SPOT_M)

    /** Splits [cameras] into (active, suppressed). */
    fun partition(cameras: List<SpeedCamera>, marks: List<SuppressedMark>): Pair<List<SpeedCamera>, List<SpeedCamera>> {
        if (marks.isEmpty()) return cameras to emptyList()
        val ids = marks.mapTo(HashSet()) { it.id }
        return cameras.partition { c -> c.id !in ids && marks.none { matches(c, it) } }
    }
}
