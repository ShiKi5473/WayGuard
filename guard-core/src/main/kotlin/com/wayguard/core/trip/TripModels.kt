package com.wayguard.core.trip

import com.wayguard.core.model.SpeedCamera
import kotlinx.serialization.Serializable

/** One thinned GPS sample along a recorded trip. */
@Serializable
data class TrackPoint(
    /** Wall-clock timestamp (`Location.time`, UTC epoch ms). */
    val tMs: Long,
    val lat: Double,
    val lon: Double,
    val speedKmh: Double,
    val bearing: Float? = null,
    val accM: Float? = null,
)

/** Enforcement and warning events recorded during a trip. */
@Serializable
enum class TripEventType {
    APPROACH,
    NEAR,
    OVERSPEED,
    PASS,
    SECTION_ENTER,
    SECTION_OVER,
    SECTION_EXIT,
}

/** Snapshot of a camera at recording time so deleted/updated datasets don't break trip history. */
@Serializable
data class CameraSnapshot(
    val id: String,
    val address: String,
    val limitKmh: Int? = null,
    val lat: Double,
    val lon: Double,
) {
    companion object {
        fun from(cam: SpeedCamera): CameraSnapshot = CameraSnapshot(
            id = cam.id,
            address = listOf(cam.city, cam.district, cam.address).filter { it.isNotBlank() }.joinToString(" "),
            limitKmh = cam.limitKmh,
            lat = cam.lat,
            lon = cam.lon,
        )
    }
}

/** One alert or section event logged during a trip. */
@Serializable
data class TripEvent(
    val tMs: Long,
    val type: TripEventType,
    val lat: Double,
    val lon: Double,
    val speedKmh: Double,
    val camera: CameraSnapshot? = null,
    val sectionId: String? = null,
    val sectionAvgKmh: Double? = null,
    val limitKmh: Int? = null,
)

@Serializable
enum class TripSource {
    MANUAL,
    GOOGLE_MAPS,
}

/** First-line metadata header in a `.jsonl` trip stream for crash recovery. */
@Serializable
data class TripMetaHeader(
    val id: String,
    val startedAtMs: Long,
    val source: TripSource = TripSource.MANUAL,
    val destination: String? = null,
)

/** One line in an append-only `.jsonl` / `.jsonl.gz` trip stream. */
@Serializable
data class TripRecordLine(
    val meta: TripMetaHeader? = null,
    val point: TrackPoint? = null,
    val event: TripEvent? = null,
)

/** Compact summary persisted in `files/trips/index.json` for fast list rendering. */
@Serializable
data class TripSummary(
    val id: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val distanceM: Double,
    val movingDurationMs: Long,
    val maxSpeedKmh: Double,
    val avgMovingSpeedKmh: Double,
    val cameraCount: Int,
    val overspeedCount: Int,
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
    val destination: String? = null,
    val source: TripSource = TripSource.MANUAL,
    val abnormalEnd: Boolean = false,
    val backedUp: Boolean = true,
)

/** Full trip payload loaded on demand from `<id>.jsonl.gz` or `<id>.jsonl`. */
data class TripDetail(
    val summary: TripSummary,
    val points: List<TrackPoint>,
    val events: List<TripEvent>,
)

