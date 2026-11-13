package com.wayguard.core.engine

import com.wayguard.core.model.Section
import com.waymap.geo.GeoUtils
import com.waymap.geo.LatLng

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
 * Tracks one active average-speed section at a time: starts when the way driven since
 * the previous fix passes within [ENTER_RADIUS_M] of a section's start (direction
 * permitting), accumulates the distance driven and finishes the same way at the end.
 *
 * Checking the way between fixes rather than each fix alone matters in tunnels: GPS
 * is lost inside, and the first fix after the exit can already be past the start or
 * end camera. Crossing times are interpolated along that way, so the average is
 * computed over the time between the cameras, as enforcement does.
 */
class SectionTracker(var overspeedToleranceKmh: Int = 0) {

    private var sections: List<Section> = emptyList()

    /** A position and the (possibly interpolated) time it was there. */
    private class Point(val pos: LatLng, val timeMs: Double)

    private class Active(val section: Section, val startMs: Double, var last: Point) {
        var traveled = 0.0
        var over = false
    }

    /** Where the way since the previous fix came closest to a target. */
    private class Crossing(val point: Point, val distanceM: Double, val interior: Boolean)

    private var active: Active? = null
    private var previous: Point? = null
    /** Section id → time it ended, so lingering near its end/start doesn't restart it. */
    private val recentlyEnded = HashMap<String, Long>()

    fun setSections(list: List<Section>) {
        sections = list
        if (active?.section?.id !in list.map { it.id }) active = null
    }

    fun reset() {
        active = null
        previous = null
        recentlyEnded.clear()
    }

    fun update(fix: Fix, heading: Double?): Pair<List<SectionEvent>, SectionStatus?> {
        val cur = Point(fix.pos, fix.timeMs.toDouble())
        val prev = previous
        previous = cur
        val stepMs = prev?.let { fix.timeMs - it.timeMs } ?: 0.0
        // A long step means GPS was lost (tunnel): the straight way between the fixes is
        // only an approximation of the road, so it may pass a camera at a larger distance.
        val gap = stepMs > GAP_MS

        val events = ArrayList<SectionEvent>()
        // Start of the part of this step not yet accounted for. One step can hold several
        // sections when GPS is lost through consecutive tunnels (觀音 → 谷風).
        var from = prev?.takeIf { stepMs in 1.0..MAX_GAP_MS.toDouble() }
        var a: Active
        while (true) {
            a = active ?: run {
                val entered = tryEnter(from, cur, fix, heading, gap) ?: return events to null
                events += SectionEvent(SectionEventType.ENTER, status(entered, 0.0, 0.0))
                entered
            }
            val s = a.section
            val exit = crossing(a.last, cur, s.end)
            val traveledAtEnd = a.traveled + GeoUtils.haversine(a.last.pos, exit.point.pos)
            if (exit.distanceM <= ENTER_RADIUS_M ||
                (gap && exit.interior && exit.distanceM <= GAP_PASS_RADIUS_M && traveledAtEnd >= s.lengthM * GAP_PASS_MIN_FRACTION)
            ) {
                events += SectionEvent(SectionEventType.COMPLETE, finish(a, exit.point.timeMs))
                end(s, fix)
                if (!exit.interior) return events to null
                from = exit.point
                continue
            }
            break
        }
        val s = a.section

        if (gap || fix.speedKmh >= MIN_ACCUM_SPEED_KMH) {
            a.traveled += GeoUtils.haversine(a.last.pos, cur.pos)
            a.last = cur
        }
        val elapsedS = (cur.timeMs - a.startMs) / 1000.0
        val straightSum = GeoUtils.haversine(cur.pos, s.start) + GeoUtils.haversine(cur.pos, s.end)
        val maxSpan = maxOf(s.lengthM, GeoUtils.haversine(s.start, s.end))
        if (a.traveled > s.lengthM * 1.5 + 500 ||
            straightSum > maxSpan + OFF_COURSE_MARGIN_M ||
            elapsedS > MAX_DURATION_S
        ) {
            val st = status(a, elapsedS, a.traveled)
            end(s, fix)
            events += SectionEvent(SectionEventType.ABORT, st)
            return events to null
        }

        val st = status(a, elapsedS, a.traveled)
        if (st.over && !a.over) events += SectionEvent(SectionEventType.OVER_LIMIT, st)
        a.over = st.over
        return events to st
    }

    private fun tryEnter(from: Point?, cur: Point, fix: Fix, heading: Double?, gap: Boolean): Active? {
        recentlyEnded.entries.removeAll { fix.timeMs - it.value > REENTER_COOLDOWN_MS }
        if (fix.speedKmh < MIN_SPEED_KMH) return null
        for (s in sections) {
            if (s.id in recentlyEnded) continue
            if (heading == null || GeoUtils.angleDiff(heading, s.heading.toDouble()) > HEADING_TOLERANCE_DEG) continue
            val c = crossing(from ?: cur, cur, s.start)
            if (c.distanceM <= ENTER_RADIUS_M || (gap && c.interior && c.distanceM <= GAP_PASS_RADIUS_M)) {
                val a = Active(s, c.point.timeMs, c.point)
                active = a
                return a
            }
        }
        return null
    }

    /** Closest approach to [target] on the way [from]→[to], with the interpolated time. */
    private fun crossing(from: Point, to: Point, target: LatLng): Crossing {
        val p = GeoUtils.projectOnSegment(target, from.pos, to.pos)
        val len = GeoUtils.haversine(from.pos, to.pos)
        val f = if (len > 0) (GeoUtils.haversine(from.pos, p) / len).coerceIn(0.0, 1.0) else 1.0
        val t = from.timeMs + f * (to.timeMs - from.timeMs)
        return Crossing(Point(p, t), GeoUtils.haversine(p, target), interior = f > 0.0 && f < 1.0)
    }

    private fun finish(a: Active, endMs: Double): SectionStatus {
        val s = a.section
        // Enforcement divides the section length by the time between the cameras.
        val elapsedS = (endMs - a.startMs) / 1000.0
        val finalAvg = if (elapsedS > 0) s.lengthM / elapsedS * 3.6 else null
        return status(a, elapsedS, s.lengthM).copy(
            averageKmh = finalAvg,
            over = finalAvg != null && finalAvg > s.limitKmh + overspeedToleranceKmh,
        )
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
        const val MIN_ACCUM_SPEED_KMH = 3.0
        const val OFF_COURSE_MARGIN_M = 200.0
        const val MIN_AVG_TIME_S = 3.0
        const val MIN_AVG_DIST_M = 50.0
        const val MIN_OVER_DIST_M = 200.0
        const val MAX_DURATION_S = 2 * 3600.0
        const val REENTER_COOLDOWN_MS = 2 * 60_000L
        /** Fix-to-fix time above which GPS counts as having been lost in between. */
        const val GAP_MS = 3_000L
        /** Longest GPS loss still bridged (the longest tunnels take ~10 minutes). */
        const val MAX_GAP_MS = 15 * 60_000L
        /** Across a GPS gap a camera counts as passed when the way passes this close. */
        const val GAP_PASS_RADIUS_M = 150.0
        /** …and, for the end, at least this share of the section has been driven. */
        const val GAP_PASS_MIN_FRACTION = 0.5
    }
}
