package com.wayguard.core.validate

import com.wayguard.core.index.GridIndex
import com.wayguard.core.model.SpeedCamera
import com.wayguard.core.parse.AddressParser
import com.waymap.geo.GeoUtils
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/** Cross-verification verdict for a camera's published coordinates. */
@Serializable
enum class VerdictLevel(val locTag: String?, val severity: Int) {
    /** Coordinates agree with address roads, route refs, and administrative district. */
    OK(null, 1),

    /** Minor discrepancy: deserves manual verification (e.g. near wrong road or wrong district). */
    SUSPECT("suspect", 2),

    /** Major discrepancy: far from named road/route or outside declared city/county. */
    MISMATCH("mismatch", 3),

    /** Address lacks verifiable road/route info or OSM extract has no matching geometry. */
    UNVERIFIABLE(null, 0),
}

/**
 * Cached result of location cross-verification for one camera (stored in
 * `data/generated/location-checks.json`).
 */
@Serializable
data class LocationCheck(
    val verdict: VerdictLevel,
    val method: String,
    /** Distance from published coordinate to matched road/suggested point, rounded to 10 m. */
    val distanceM: Int? = null,
    val suggestedLat: Double? = null,
    val suggestedLon: Double? = null,
    val reason: String = "",
    val checkedAt: String = "",
    /** Camera coordinates when this check ran; invalidated if the published coords change. */
    val lat: Double? = null,
    val lon: Double? = null,
) {
    fun matchesPosition(camLat: Double, camLon: Double): Boolean =
        (lat == null || abs(lat - camLat) <= 1e-5) && (lon == null || abs(lon - camLon) <= 1e-5)

    /** True when only timestamps differ; prevents noisy weekly commits when geometry hasn't shifted. */
    fun sameConclusion(other: LocationCheck): Boolean =
        verdict == other.verdict &&
            method == other.method &&
            distanceM == other.distanceM &&
            suggestedLat == other.suggestedLat &&
            suggestedLon == other.suggestedLon &&
            reason == other.reason &&
            matchesPosition(other.lat ?: Double.NaN, other.lon ?: Double.NaN)
}

/** Thresholds and combination rules for camera location cross-verification. */
object LocationVerdict {

    const val INTERSECTION_OK_M = 60.0
    const val INTERSECTION_SUSPECT_M = 300.0
    const val ROAD_OK_M = 30.0
    const val ROAD_SUSPECT_M = 150.0
    const val ADMIN_BORDER_TOLERANCE_M = 200.0
    const val INTERNAL_CONFLICT_M = 10.0

    fun roundDistance10m(meters: Double): Int = ((meters / 10.0).roundToInt()) * 10

    fun forIntersection(maxRoadDistM: Double): VerdictLevel = when {
        maxRoadDistM <= INTERSECTION_OK_M -> VerdictLevel.OK
        maxRoadDistM <= INTERSECTION_SUSPECT_M -> VerdictLevel.SUSPECT
        else -> VerdictLevel.MISMATCH
    }

    fun forRoad(distM: Double): VerdictLevel = when {
        distM <= ROAD_OK_M -> VerdictLevel.OK
        distM <= ROAD_SUSPECT_M -> VerdictLevel.SUSPECT
        else -> VerdictLevel.MISMATCH
    }

    fun forAdmin(
        expectedCity: String,
        expectedDistrict: String,
        actualCity: String?,
        actualDistrict: String?,
        borderDistanceM: Double? = null,
    ): VerdictLevel {
        if (actualCity.isNullOrBlank()) return VerdictLevel.UNVERIFIABLE
        val expCity = CountyBounds.normalizeCity(expectedCity)
        val actCity = CountyBounds.normalizeCity(actualCity)
        if (expCity.isNotEmpty() && expCity != actCity) {
            return if (borderDistanceM != null && borderDistanceM <= ADMIN_BORDER_TOLERANCE_M) {
                VerdictLevel.OK
            } else {
                VerdictLevel.MISMATCH
            }
        }
        val expDist = CountyBounds.normalizeCity(expectedDistrict)
        val actDist = CountyBounds.normalizeCity(actualDistrict.orEmpty())
        if (expDist.isEmpty() || actDist.isEmpty() || expDist == actDist) return VerdictLevel.OK
        return if (borderDistanceM != null && borderDistanceM <= ADMIN_BORDER_TOLERANCE_M) {
            VerdictLevel.OK
        } else {
            VerdictLevel.SUSPECT
        }
    }

    /** Takes the most severe verdict across checks (`MISMATCH > SUSPECT > OK > UNVERIFIABLE`). */
    fun combine(vararg levels: VerdictLevel): VerdictLevel =
        levels.maxByOrNull { it.severity } ?: VerdictLevel.UNVERIFIABLE

    /**
     * Checks pairs of cameras within [INTERNAL_CONFLICT_M] metres whose addresses name different
     * roads or different numbered routes (requiring no external map data).
     */
    fun findInternalConflicts(
        cameras: List<SpeedCamera>,
        knownRoads: Set<String> = emptySet(),
        checkedAt: String = "",
    ): Map<String, LocationCheck> {
        if (cameras.size < 2) return emptyMap()
        val parsed = cameras.associate { it.id to AddressParser.parse(it.address, it.city, it.directionText, knownRoads) }
        val index = GridIndex(cameras, { it.pos }, cellDeg = 0.002)
        val out = LinkedHashMap<String, LocationCheck>()
        for (a in cameras) {
            val baseA = a.id.substringBefore("-h")
            val pa = parsed.getValue(a.id)
            val nearby = index.near(a.pos, INTERNAL_CONFLICT_M)
            val conflicts = ArrayList<Pair<SpeedCamera, Double>>()
            for (b in nearby) {
                if (b.id.substringBefore("-h") == baseA) continue
                val pb = parsed.getValue(b.id)
                val roadConflict = pa.roads.isNotEmpty() && pb.roads.isNotEmpty() && pa.roads.toSet() != pb.roads.toSet()
                val routeConflict = pa.roads.isEmpty() && pb.roads.isEmpty() &&
                    pa.routeRef != null && pb.routeRef != null &&
                    (pa.routeRef != pb.routeRef || pa.isFreeway != pb.isFreeway)
                if (roadConflict || routeConflict) {
                    conflicts += b to GeoUtils.haversine(a.pos, b.pos)
                }
            }
            if (conflicts.isNotEmpty()) {
                val (other, dist) = conflicts.minBy { it.second }
                val distRounded = dist.roundToInt()
                out[a.id] = LocationCheck(
                    verdict = VerdictLevel.SUSPECT,
                    method = "internal",
                    distanceM = null,
                    reason = "與 ${other.id}（${other.city}${other.district} ${other.address}）相距 ${distRounded}m 但路名不同",
                    checkedAt = checkedAt,
                    lat = a.lat,
                    lon = a.lon,
                )
            }
        }
        return out
    }
}

