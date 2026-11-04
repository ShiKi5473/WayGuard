package com.wayguard.core.dataset

import com.wayguard.core.parse.PlaceTarget
import com.wayguard.core.validate.CountyBounds
import com.waymap.geo.LatLng
import kotlinx.serialization.Serializable

/**
 * A destination named by direction texts (`往大溪方向`), hand-curated in
 * data/source/places.json. The direction of such a camera is taken as the bearing
 * from the camera to this point.
 *
 * Scope narrows where the name applies: a [district] entry beats a [city] entry,
 * which beats a nationwide one (both null). Use a district scope for relative names
 * (`市區`) and for roads, whose relevant point differs per area.
 */
@Serializable
data class Place(
    val name: String,
    val aliases: List<String> = emptyList(),
    val city: String? = null,
    val district: String? = null,
    val lat: Double,
    val lon: Double,
    /** What the point is (e.g. "大溪區公所（OSM）") — for humans reviewing the file. */
    val ref: String = "",
    /**
     * A road (`往興豐路方向`): the point is that road's nearest point to the cameras,
     * which is only trusted when close (see [PlaceHeadingRules.MAX_ROAD_TARGET_M]).
     */
    val road: Boolean = false,
) {
    val pos: LatLng get() = LatLng(lat, lon)
}

/** Looks up [Place]s by name within the most specific matching scope. */
class PlaceDirectory(places: List<Place>) {

    private val byName: Map<String, List<Place>> = places
        .flatMap { p -> (listOf(p.name) + p.aliases).map { PlaceTarget.key(it) to p } }
        .groupBy({ it.first }, { it.second })

    val isEmpty: Boolean = places.isEmpty()

    fun find(name: String, city: String, district: String): Place? {
        val candidates = byName[PlaceTarget.key(name)] ?: return null
        val c = CountyBounds.normalizeCity(city)
        fun cityOf(p: Place) = p.city?.let(CountyBounds::normalizeCity)
        return candidates.firstOrNull { cityOf(it) == c && it.district == district }
            ?: candidates.firstOrNull { cityOf(it) == c && it.district == null }
            ?: candidates.firstOrNull { it.city == null && it.district == null }
    }
}
