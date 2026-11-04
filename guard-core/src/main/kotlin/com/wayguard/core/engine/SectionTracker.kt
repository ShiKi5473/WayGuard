package com.wayguard.core.engine

import com.wayguard.core.model.Section
import com.waymap.geo.GeoUtils

/**
 * Progress through an average-speed section.
 *
 * @property averageKmh null until enough distance/time has passed to be meaningful.
 * @property maxSpeedForRemainingKmh the speed that, held for the rest of the section,
 *   brings the overall average exactly to the limit (0 = already over for good).
 */
data class SectionStatus(
    val section: Section,
    val traveledM: Double,
    val elapsedS: Double,
    val averageKmh: Double?,
    val remainingM: Double,
    val maxSpeedForRemainingKmh: Double?,
    val over: Boolean,
)

enum class SectionEventType { ENTER, OVER_LIMIT, COMPLETE, ABORT }

data class SectionEvent(val type: SectionEventType, val status: SectionStatus)

/**
 * Tracks one active average-speed section at a time: starts within [ENTER_RADIUS_M]
 * of a section's start (direction permitting), accumulates the distance driven and
 * finishes within [ENTER_RADIUS_M] of the end.
 */
class SectionTracker(var overspeedToleranceKmh: Int = 0) {

    private var sections: List<Section> = emptyList()

    private class Active(val section: Section, val startMs: Long, var last: com.waymap.geo.LatLng) {
        var traveled = 0.0
        var over = false
    }

    private var active: Active? = null
    /** Section id → time it ended, so lingering near its end/start doesn't restart it. */
    private val recentlyEnded = HashMap<String, Long>()

    fun setSections(list: List<Section>) {
        sections = list
        if (active?.section?.id !in list.map { it.id }) active = null
    }

    fun reset() {
        active = null
        recentlyEnded.clear()
    }

    fun update(fix: Fix, heading: Double?): Pair<List<SectionEvent>, SectionStatus?> {
        val a = active ?: return tryEnter(fix, heading)
        a.traveled += GeoUtils.haversine(a.last, fix.pos)
        a.last = fix.pos
        val s = a.section
        val elapsedS = (fix.timeMs - a.startMs) / 1000.0

        if (GeoUtils.haversine(fix.pos, s.end) <= ENTER_RADIUS_M) {
            // Enforcement divides the section length by the time between the cameras.
            val finalAvg = if (elapsedS > 0) s.lengthM / elapsedS * 3.6 else null
            val st = status(a, elapsedS, s.lengthM).copy(
                averageKmh = finalAvg,
                over = finalAvg != null && finalAvg > s.limitKmh + overspeedToleranceKmh,
            )
            end(s, fix)
            return listOf(SectionEvent(SectionEventType.COMPLETE, st)) to null
        }
        if (a.traveled > s.lengthM * 1.5 + 500 || elapsedS > MAX_DURATION_S) {
            val st = status(a, elapsedS, a.traveled)
            end(s, fix)
            return listOf(SectionEvent(SectionEventType.ABORT, st)) to null
        }

        val st = status(a, elapsedS, a.traveled)
        val events = if (st.over && !a.over) listOf(SectionEvent(SectionEventType.OVER_LIMIT, st)) else emptyList()
        a.over = st.over
        return events to st
    }

    private fun tryEnter(fix: Fix, heading: Double?): Pair<List<SectionEvent>, SectionStatus?> {
        recentlyEnded.entries.removeAll { fix.timeMs - it.value > REENTER_COOLDOWN_MS }
        if (fix.speedKmh < MIN_SPEED_KMH) return emptyList<SectionEvent>() to null
        val s = sections.firstOrNull { s ->
            s.id !in recentlyEnded &&
                GeoUtils.haversine(fix.pos, s.start) <= ENTER_RADIUS_M &&
                (s.heading == null || (heading != null &&
                    GeoUtils.angleDiff(heading, s.heading.toDouble()) <= HEADING_TOLERANCE_DEG))
        } ?: return emptyList<SectionEvent>() to null
        val a = Active(s, fix.timeMs, fix.pos)
        active = a
        val st = status(a, 0.0, 0.0)
        return listOf(SectionEvent(SectionEventType.ENTER, st)) to st
    }

    private fun end(s: Section, fix: Fix) {
        active = null
        recentlyEnded[s.id] = fix.timeMs
    }

    private fun status(a: Active, elapsedS: Double, traveledM: Double): SectionStatus {
        val s = a.section
        val avg = if (elapsedS >= MIN_AVG_TIME_S && traveledM >= MIN_AVG_DIST_M) traveledM / elapsedS * 3.6 else null
        val remaining = (s.lengthM - traveledM).coerceAtLeast(0.0)
        val allowedTotalS = s.lengthM / (s.limitKmh / 3.6)
        val timeLeftS = allowedTotalS - elapsedS
        val maxForRemaining = when {
            remaining <= 0.0 -> null
            timeLeftS <= 0.0 -> 0.0
            else -> remaining / timeLeftS * 3.6
        }
        val over = avg != null && traveledM >= MIN_OVER_DIST_M && avg > s.limitKmh + overspeedToleranceKmh
        return SectionStatus(s, traveledM, elapsedS, avg, remaining, maxForRemaining, over)
    }

    private companion object {
        const val ENTER_RADIUS_M = 60.0
        const val HEADING_TOLERANCE_DEG = 70.0
        const val MIN_SPEED_KMH = 5.0
        const val MIN_AVG_TIME_S = 3.0
        const val MIN_AVG_DIST_M = 50.0
        const val MIN_OVER_DIST_M = 200.0
        const val MAX_DURATION_S = 2 * 3600.0
        const val REENTER_COOLDOWN_MS = 2 * 60_000L
    }
}
