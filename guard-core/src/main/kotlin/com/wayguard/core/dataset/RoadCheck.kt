package com.wayguard.core.dataset

import com.waymap.geo.GeoUtils
import kotlinx.serialization.Serializable

/**
 * The road a camera stands on, looked up once in OpenStreetMap and cached in
 * data/generated/road-checks.json (keyed by camera id) by camera-sync.
 */
@Serializable
data class RoadCheck(
    /** Direction of the road at the camera, 0..359 — one of its two ways; null = no road found nearby. */
    val bearing: Int? = null,
    val road: String = "",
    /** Distance from the camera to that road, metres. */
    val distanceM: Int? = null,
    /** ISO-8601 time of the lookup. */
    val checkedAt: String,
    /** Camera coordinates when this check was fetched; null in older cache entries. */
    val lat: Double? = null,
    val lon: Double? = null,
)

/** Why a destination-derived direction was not applied (the camera then alerts both ways). */
enum class SkipReason(val text: String) {
    TOO_CLOSE("目的地離測速點太近"),
    ROAD_TARGET_TOO_FAR("路名的最近點太遠，可能不是相交的那一段"),
    NOT_CHECKED("尚未比對測速點所在道路的走向"),
    NO_ROAD("測速點附近查無道路"),
    LANE("測速點旁查到的是巷弄，不是所在道路"),
    WRONG_ROAD("查到的道路不是地址上的道路（可能是交叉路）"),
    INTERSECTION("路口點位，無法確定測速點在哪條路上"),
    ROAD_MISMATCH("道路走向與目的地方位不符（彎路或路口）"),
}

/**
 * Decides whether a direction estimated from a destination can be trusted.
 * Fails safe: anything uncertain keeps the camera alerting both ways.
 */
object PlaceHeadingRules {

    /** Closer than this, the bearing to the destination says little about the road. */
    const val MIN_PLACE_DISTANCE_M = 150.0

    /** A road destination is the crossing ahead; a "nearest point" farther than this is suspect. */
    const val MAX_ROAD_TARGET_M = 1000.0

    /** The road at the camera must run within this of the destination's bearing. */
    const val MAX_ROAD_DIFF_DEG = 60.0

    sealed interface Outcome {
        /** Use [heading] — the camera road's direction that leads toward the destination. */
        data class Apply(val heading: Int, val diffDeg: Int) : Outcome
        data class Skip(val reason: SkipReason, val diffDeg: Int? = null) : Outcome
    }

    /**
     * @param cameraRoad the road the camera's own texts name; the street found in OSM must
     *   be that road, or the check would compare against a crossing street.
     */
    fun decide(
        place: Place,
        distanceM: Double,
        bearingToPlace: Int,
        check: RoadCheck?,
        cameraRoad: CameraRoad = CameraRoad.Unnamed,
    ): Outcome {
        if (distanceM < MIN_PLACE_DISTANCE_M) return Outcome.Skip(SkipReason.TOO_CLOSE)
        if (place.road && distanceM > MAX_ROAD_TARGET_M) return Outcome.Skip(SkipReason.ROAD_TARGET_TOO_FAR)
        if (check == null) return Outcome.Skip(SkipReason.NOT_CHECKED)
        val axis = check.bearing ?: return Outcome.Skip(SkipReason.NO_ROAD)
        if (CameraRoad.isLane(check.road)) return Outcome.Skip(SkipReason.LANE)
        when (cameraRoad) {
            is CameraRoad.Known ->
                if (CameraRoad.base(check.road) != cameraRoad.base) return Outcome.Skip(SkipReason.WRONG_ROAD)
            is CameraRoad.Intersection -> return Outcome.Skip(SkipReason.INTERSECTION)
            CameraRoad.Unnamed -> Unit
        }
        // The road runs both ways; take the way that points toward the destination.
        val dir = listOf(axis, (axis + 180) % 360)
            .minBy { GeoUtils.angleDiff(it.toDouble(), bearingToPlace.toDouble()) }
        val diff = GeoUtils.angleDiff(dir.toDouble(), bearingToPlace.toDouble()).toInt()
        return if (diff > MAX_ROAD_DIFF_DEG) Outcome.Skip(SkipReason.ROAD_MISMATCH, diff)
        else Outcome.Apply(dir, diff)
    }
}
