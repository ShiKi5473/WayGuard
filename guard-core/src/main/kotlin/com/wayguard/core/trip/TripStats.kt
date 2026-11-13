package com.wayguard.core.trip

import com.wayguard.core.model.DatasetJson
import com.waymap.geo.GeoUtils
import com.waymap.geo.LatLng
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/** Continuous polyline slice styled by whether the trip was overspeeding during that span. */
data class TrackSegment(
    val points: List<LatLng>,
    val overspeed: Boolean,
)

/**
 * Computes accurate trip statistics, recovers incomplete `.jsonl` streams, and builds map polylines.
 */
object TripStats {

    private const val MAX_SEGMENT_GAP_MS = 60_000L
    private const val MAX_IMPLIED_SPEED_KMH = 250.0
    private const val MAX_REALISTIC_SPEED_KMH = 220.0
    private const val SPIKE_NEIGHBOR_WINDOW_MS = 10_000L
    private const val SPIKE_JUMP_KMH = 35.0
    private const val SPIKE_SOLO_CAP_KMH = 150.0
    private const val OVERSPEED_SEGMENT_HOLD_MS = 4_000L

    fun summarize(
        id: String,
        startedAtMs: Long,
        endedAtMs: Long,
        points: List<TrackPoint>,
        events: List<TripEvent>,
        destination: String? = null,
        source: TripSource = TripSource.MANUAL,
        abnormalEnd: Boolean = false,
        backedUp: Boolean = true,
    ): TripSummary {
        var distanceM = 0.0
        var movingDurationMs = 0L

        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val cur = points[i]
            val dtMs = cur.tMs - prev.tMs
            if (dtMs <= 0L || dtMs > MAX_SEGMENT_GAP_MS) continue
            // Only accumulate distance & moving time when moving (>= 3 km/h) so stopped GPS drift is ignored.
            if (prev.speedKmh >= TrackThinning.MOVING_SPEED_KMH && cur.speedKmh >= TrackThinning.MOVING_SPEED_KMH) {
                val segDist = GeoUtils.haversine(LatLng(prev.lat, prev.lon), LatLng(cur.lat, cur.lon))
                val impliedKmh = segDist / (dtMs / 1000.0) * 3.6
                if (impliedKmh <= MAX_IMPLIED_SPEED_KMH) {
                    distanceM += segDist
                    movingDurationMs += dtMs
                }
            }
        }

        val maxSpeedKmh = filteredMaxSpeed(points)
        val avgMovingSpeedKmh = if (movingDurationMs > 0L) {
            (distanceM / (movingDurationMs / 1000.0)) * 3.6
        } else {
            0.0
        }

        val cameraCount = countDistinctCameraEncounters(events)
        val overspeedCount = events.count { it.type == TripEventType.OVERSPEED || it.type == TripEventType.SECTION_OVER }

        val firstLat = points.firstOrNull()?.lat ?: events.firstOrNull()?.lat ?: 0.0
        val firstLon = points.firstOrNull()?.lon ?: events.firstOrNull()?.lon ?: 0.0
        val lastLat = points.lastOrNull()?.lat ?: events.lastOrNull()?.lat ?: firstLat
        val lastLon = points.lastOrNull()?.lon ?: events.lastOrNull()?.lon ?: firstLon

        return TripSummary(
            id = id,
            startedAtMs = startedAtMs,
            endedAtMs = max(endedAtMs, startedAtMs),
            distanceM = (distanceM * 10.0).roundToInt() / 10.0,
            movingDurationMs = movingDurationMs,
            maxSpeedKmh = (maxSpeedKmh * 10.0).roundToInt() / 10.0,
            avgMovingSpeedKmh = (avgMovingSpeedKmh * 10.0).roundToInt() / 10.0,
            cameraCount = cameraCount,
            overspeedCount = overspeedCount,
            startLat = firstLat,
            startLon = firstLon,
            endLat = lastLat,
            endLon = lastLon,
            destination = destination,
            source = source,
            abnormalEnd = abnormalEnd,
            backedUp = backedUp,
        )
    }

    /**
     * Filters isolated single-point speed spikes (e.g. cold tunnel exits) when computing max speed.
     */
    fun filteredMaxSpeed(points: List<TrackPoint>): Double {
        if (points.isEmpty()) return 0.0
        var maxV = 0.0
        for (i in points.indices) {
            val s = points[i].speedKmh
            if (s < TrackThinning.MOVING_SPEED_KMH || s > MAX_REALISTIC_SPEED_KMH) continue
            val isSpike = isIsolatedSpike(points, i)
            if (!isSpike && s > maxV) {
                maxV = s
            }
        }
        if (maxV == 0.0) {
            // Fallback if all points were below realistic cap and somehow got filtered (e.g. 1-point trip).
            maxV = points.asSequence()
                .map { it.speedKmh }
                .filter { it in TrackThinning.MOVING_SPEED_KMH..SPIKE_SOLO_CAP_KMH }
                .maxOrNull() ?: 0.0
        }
        return maxV
    }

    private fun isIsolatedSpike(points: List<TrackPoint>, index: Int): Boolean {
        val cur = points[index]
        val prev = points.getOrNull(index - 1)?.takeIf { cur.tMs - it.tMs <= SPIKE_NEIGHBOR_WINDOW_MS }
        val next = points.getOrNull(index + 1)?.takeIf { it.tMs - cur.tMs <= SPIKE_NEIGHBOR_WINDOW_MS }
        return when {
            prev != null && next != null ->
                cur.speedKmh - prev.speedKmh > SPIKE_JUMP_KMH && cur.speedKmh - next.speedKmh > SPIKE_JUMP_KMH
            prev == null && next != null ->
                cur.speedKmh > SPIKE_SOLO_CAP_KMH && cur.speedKmh - next.speedKmh > SPIKE_JUMP_KMH
            prev != null && next == null ->
                cur.speedKmh > SPIKE_SOLO_CAP_KMH && cur.speedKmh - prev.speedKmh > SPIKE_JUMP_KMH
            else -> cur.speedKmh > SPIKE_SOLO_CAP_KMH
        }
    }

    private fun countDistinctCameraEncounters(events: List<TripEvent>): Int {
        var count = 0
        var activeCameraId: String? = null
        for (e in events) {
            when (e.type) {
                TripEventType.APPROACH, TripEventType.NEAR, TripEventType.OVERSPEED -> {
                    val camId = e.camera?.id
                    if (camId != null && camId != activeCameraId) {
                        activeCameraId = camId
                        count++
                    }
                }
                TripEventType.PASS -> {
                    val camId = e.camera?.id
                    if (camId != null && camId != activeCameraId) {
                        count++
                    }
                    activeCameraId = null
                }
                TripEventType.SECTION_ENTER -> count++
                TripEventType.SECTION_OVER, TripEventType.SECTION_EXIT -> Unit
            }
        }
        return count
    }

    /**
     * Parses a `.jsonl` (or decompressed `.jsonl.gz`) stream into a [TripDetail].
     * If [assumeIncompleteFile] is true and the final line is truncated/corrupt, skips it and marks `abnormalEnd = true`.
     */
    fun decodeJsonl(
        fallbackId: String,
        lines: List<String>,
        assumeIncompleteFile: Boolean = false,
        backedUp: Boolean = true,
    ): TripDetail? {
        var meta: TripMetaHeader? = null
        val points = ArrayList<TrackPoint>()
        val events = ArrayList<TripEvent>()
        var truncatedLastLine = false

        val nonBlank = lines.filter { it.isNotBlank() }
        for (i in nonBlank.indices) {
            val raw = nonBlank[i]
            val parsed = runCatching { DatasetJson.decodeFromString(TripRecordLine.serializer(), raw) }.getOrNull()
            if (parsed == null) {
                if (i == nonBlank.lastIndex && assumeIncompleteFile) {
                    truncatedLastLine = true
                }
                continue
            }
            parsed.meta?.let { meta = it }
            parsed.point?.let { points += it }
            parsed.event?.let { events += it }
        }

        if (points.isEmpty() && events.isEmpty()) return null
        points.sortBy { it.tMs }
        events.sortBy { it.tMs }

        val id = meta?.id ?: fallbackId
        val startedAt = meta?.startedAtMs ?: points.firstOrNull()?.tMs ?: events.first().tMs
        val endedAt = max(points.lastOrNull()?.tMs ?: 0L, events.lastOrNull()?.tMs ?: 0L).coerceAtLeast(startedAt)
        val abnormal = assumeIncompleteFile || truncatedLastLine
        val summary = summarize(
            id = id,
            startedAtMs = startedAt,
            endedAtMs = endedAt,
            points = points,
            events = events,
            destination = meta?.destination,
            source = meta?.source ?: TripSource.MANUAL,
            abnormalEnd = abnormal,
            backedUp = backedUp,
        )
        return TripDetail(summary, points, events)
    }

    /** Splits a trip track into continuous blue (normal) and red (overspeed) segments for map rendering. */
    fun buildSegments(points: List<TrackPoint>, events: List<TripEvent>): List<TrackSegment> {
        if (points.size < 2) return emptyList()
        val overspeedWindows = buildOverspeedWindows(events)
        val isPointOver = points.map { pt -> isPointOverspeed(pt, events, overspeedWindows) }

        val out = ArrayList<TrackSegment>()
        var curOver = isPointOver[0]
        var curList = mutableListOf(LatLng(points[0].lat, points[0].lon))
        for (i in 1 until points.size) {
            val pt = points[i]
            val ll = LatLng(pt.lat, pt.lon)
            val over = isPointOver[i]
            val gap = pt.tMs - points[i - 1].tMs > MAX_SEGMENT_GAP_MS
            if (gap) {
                if (curList.size >= 2) out += TrackSegment(curList, curOver)
                curList = mutableListOf(ll)
                curOver = over
            } else if (over != curOver) {
                curList += ll
                if (curList.size >= 2) out += TrackSegment(curList, curOver)
                curList = mutableListOf(ll)
                curOver = over
            } else {
                curList += ll
            }
        }
        if (curList.size >= 2) out += TrackSegment(curList, curOver)
        return out
    }

    private fun buildOverspeedWindows(events: List<TripEvent>): List<LongRange> {
        val windows = ArrayList<LongRange>()
        var sectionOverStart: Long? = null
        for (e in events) {
            when (e.type) {
                TripEventType.OVERSPEED -> windows += (e.tMs - 1_500L)..(e.tMs + OVERSPEED_SEGMENT_HOLD_MS)
                TripEventType.SECTION_OVER -> if (sectionOverStart == null) sectionOverStart = e.tMs
                TripEventType.SECTION_EXIT -> if (sectionOverStart != null) {
                    windows += sectionOverStart..e.tMs
                    sectionOverStart = null
                }
                else -> Unit
            }
        }
        if (sectionOverStart != null) {
            windows += sectionOverStart..Long.MAX_VALUE
        }
        return windows
    }

    private fun isPointOverspeed(
        pt: TrackPoint,
        events: List<TripEvent>,
        windows: List<LongRange>,
    ): Boolean {
        if (windows.any { pt.tMs in it }) return true
        // Also highlight high-speed stretches near any camera whose speed limit was exceeded.
        for (e in events) {
            val limit = e.limitKmh ?: e.camera?.limitKmh ?: continue
            if (abs(e.tMs - pt.tMs) <= OVERSPEED_SEGMENT_HOLD_MS && pt.speedKmh > limit) return true
        }
        return false
    }

    /** Computes map center & approximate zoom level framing all points/events of a trip. */
    fun computeCenterAndZoom(points: List<TrackPoint>, events: List<TripEvent>): Pair<LatLng, Double>? {
        val allLat = points.map { it.lat } + events.map { it.lat }
        val allLon = points.map { it.lon } + events.map { it.lon }
        if (allLat.isEmpty() || allLon.isEmpty()) return null
        val minLat = allLat.min()
        val maxLat = allLat.max()
        val minLon = allLon.min()
        val maxLon = allLon.max()
        val center = LatLng((minLat + maxLat) / 2.0, (minLon + maxLon) / 2.0)
        val diagM = GeoUtils.haversine(LatLng(minLat, minLon), LatLng(maxLat, maxLon))
        val zoom = when {
            diagM < 200.0 -> 16.0
            diagM > 200_000.0 -> 8.0
            else -> (16.5 - ln(diagM / 200.0) / ln(2.0)).coerceIn(8.0, 16.0)
        }
        return center to zoom
    }
}

