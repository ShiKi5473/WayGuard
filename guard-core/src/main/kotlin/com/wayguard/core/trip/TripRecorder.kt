package com.wayguard.core.trip

import com.wayguard.core.engine.AlertEvent
import com.wayguard.core.engine.AlertType
import com.wayguard.core.engine.Fix
import com.wayguard.core.engine.SectionEvent
import com.wayguard.core.engine.SectionEventType
import com.wayguard.core.model.DatasetJson

/**
 * Accumulates thinned track points and camera/section events for the active drive session,
 * splits long parked breaks automatically, and formats JSONL lines for 10-second flushes.
 */
class TripRecorder(
    var autoSplitMinutes: Int = DEFAULT_AUTO_SPLIT_MINUTES,
    private val source: TripSource = TripSource.MANUAL,
    private val destination: String? = null,
    private val idFactory: (Long) -> String = ::defaultId,
) {
    private var activeId: String? = null
    private var startedAtMs: Long = 0L
    private var lastActivityMs: Long = 0L
    private var lastStoppedStartMs: Long? = null
    private var lastRecordedPoint: TrackPoint? = null
    private val points = ArrayList<TrackPoint>()
    private val events = ArrayList<TripEvent>()
    private val pendingLines = ArrayList<String>()

    val currentTripId: String? get() = activeId

    /**
     * Records one 1 Hz fix along with alert/section events emitted on that tick.
     * If a parked break exceeded [autoSplitMinutes] and movement resumes, finalizes the prior
     * trip and returns its completed [TripDetail] so the caller can persist it immediately.
     */
    fun recordFix(
        wallTimeMs: Long,
        fix: Fix,
        accuracyM: Float?,
        alertEvents: List<AlertEvent>,
        sectionEvents: List<SectionEvent>,
    ): TripDetail? {
        var splitCompleted: TripDetail? = null
        val splitThresholdMs = autoSplitMinutes.coerceAtLeast(1) * 60_000L
        val moving = fix.speedKmh >= TrackThinning.MOVING_SPEED_KMH

        if (activeId != null && hasMeaningfulData()) {
            if (!moving) {
                if (lastStoppedStartMs == null) lastStoppedStartMs = wallTimeMs
            } else {
                val stoppedSince = lastStoppedStartMs
                if (stoppedSince != null && wallTimeMs - stoppedSince >= splitThresholdMs) {
                    splitCompleted = finish(stoppedSince)
                }
                lastStoppedStartMs = null
            }
        } else if (!moving) {
            lastStoppedStartMs = null
        }

        ensureStarted(wallTimeMs)

        val candidate = TrackPoint(
            tMs = wallTimeMs,
            lat = fix.pos.lat,
            lon = fix.pos.lon,
            speedKmh = fix.speedKmh,
            bearing = fix.bearing?.toFloat(),
            accM = accuracyM,
        )
        if (TrackThinning.shouldRecord(candidate, lastRecordedPoint)) {
            points += candidate
            lastRecordedPoint = candidate
            pendingLines += encodeLine(TripRecordLine(point = candidate))
        }

        for (ae in alertEvents) {
            val type = when (ae.type) {
                AlertType.APPROACH -> TripEventType.APPROACH
                AlertType.NEAR -> TripEventType.NEAR
                AlertType.OVERSPEED -> TripEventType.OVERSPEED
                AlertType.PASSED -> TripEventType.PASS
            }
            val te = TripEvent(
                tMs = wallTimeMs,
                type = type,
                lat = fix.pos.lat,
                lon = fix.pos.lon,
                speedKmh = ae.speedKmh,
                camera = CameraSnapshot.from(ae.camera),
                limitKmh = ae.camera.limitKmh,
            )
            events += te
            pendingLines += encodeLine(TripRecordLine(event = te))
        }

        for (se in sectionEvents) {
            val type = when (se.type) {
                SectionEventType.ENTER -> TripEventType.SECTION_ENTER
                SectionEventType.OVER_LIMIT -> TripEventType.SECTION_OVER
                SectionEventType.COMPLETE, SectionEventType.ABORT -> TripEventType.SECTION_EXIT
            }
            val te = TripEvent(
                tMs = wallTimeMs,
                type = type,
                lat = fix.pos.lat,
                lon = fix.pos.lon,
                speedKmh = fix.speedKmh,
                sectionId = se.status.section.id,
                sectionAvgKmh = se.status.averageKmh,
                limitKmh = se.status.section.limitKmh,
            )
            events += te
            pendingLines += encodeLine(TripRecordLine(event = te))
        }

        lastActivityMs = wallTimeMs
        return splitCompleted
    }

    /** Drains pending JSONL lines to append to `files/trips/<id>.jsonl`. */
    fun drainPendingLines(): Pair<String, List<String>>? {
        val id = activeId ?: return null
        if (pendingLines.isEmpty()) return null
        val batch = pendingLines.toList()
        pendingLines.clear()
        return id to batch
    }

    /** Ends the current trip and returns its full detail, or `null` if no movement/events occurred. */
    fun finish(endedAtMs: Long): TripDetail? {
        val id = activeId ?: return null
        val detail = if (hasMeaningfulData()) {
            val end = maxOf(endedAtMs, lastActivityMs, startedAtMs)
            val summary = TripStats.summarize(
                id = id,
                startedAtMs = startedAtMs,
                endedAtMs = end,
                points = points.toList(),
                events = events.toList(),
                destination = destination,
                source = source,
                abnormalEnd = false,
                backedUp = true,
            )
            TripDetail(summary, points.toList(), events.toList())
        } else {
            null
        }
        resetState()
        return detail
    }

    fun hasMeaningfulData(): Boolean = points.isNotEmpty() || events.isNotEmpty()

    private fun ensureStarted(wallTimeMs: Long) {
        if (activeId != null) return
        val id = idFactory(wallTimeMs)
        activeId = id
        startedAtMs = wallTimeMs
        lastActivityMs = wallTimeMs
        pendingLines += encodeLine(
            TripRecordLine(
                meta = TripMetaHeader(
                    id = id,
                    startedAtMs = wallTimeMs,
                    source = source,
                    destination = destination,
                )
            )
        )
    }

    private fun resetState() {
        activeId = null
        startedAtMs = 0L
        lastActivityMs = 0L
        lastStoppedStartMs = null
        lastRecordedPoint = null
        points.clear()
        events.clear()
        pendingLines.clear()
    }

    companion object {
        const val DEFAULT_AUTO_SPLIT_MINUTES = 15

        private fun encodeLine(line: TripRecordLine): String =
            DatasetJson.encodeToString(TripRecordLine.serializer(), line)

        private fun defaultId(wallTimeMs: Long): String =
            "trip-$wallTimeMs-${(1000..9999).random()}"
    }
}

