package com.wayguard.core.trip

import com.waymap.geo.GeoUtils
import com.waymap.geo.LatLng
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlin.math.max
import kotlin.math.min

/** Trip list ordering. */
enum class TripSort(val label: String) {
    NEWEST("最新"),
    OLDEST("最舊"),
    DISTANCE("里程最長"),
    MAX_SPEED("極速最高"),
    OVERSPEED("超速最多"),
    ;

    /** Only time-ordered lists are grouped under date headers. */
    val groupsByDate: Boolean get() = this == NEWEST || this == OLDEST
}

/** Trip list quick filter. */
enum class TripFilter(val label: String) {
    ALL("全部"),
    OVERSPEED("有超速"),
    LONG("長途 ≥ 50 km"),
    ABNORMAL("異常結束"),
}

/** Calendar window used for the trip list and its totals header. */
enum class TripPeriod(val label: String) {
    WEEK("本週"),
    MONTH("本月"),
    YEAR("今年"),
    ALL("全部"),
}

/** Aggregated figures over a set of trips. */
data class TripTotals(
    val count: Int,
    val distanceM: Double,
    val movingDurationMs: Long,
    val cameraCount: Int,
    val overspeedCount: Int,
    val maxSpeedKmh: Double,
) {
    /** Overspeed alerts per 100 km driven (0 when no distance). */
    val overspeedPer100Km: Double
        get() = if (distanceM > 0.0) overspeedCount / (distanceM / 100_000.0) else 0.0
}

/** Trips sharing one local calendar day, newest/oldest order preserved. */
data class TripDayGroup(
    val date: LocalDate,
    val title: String,
    val trips: List<TripSummary>,
    val totals: TripTotals,
)

/** One point of a downsampled speed-over-time series. */
data class SpeedSample(val tMs: Long, val speedKmh: Double)

/** Interpolated position along a track at a given time, for playback. */
data class PlaybackPosition(
    val tMs: Long,
    val pos: LatLng,
    val speedKmh: Double,
    val bearing: Float?,
    /** Index of the track point at or before [tMs]. */
    val index: Int,
)

/**
 * Pure helpers behind the trip browser: list query/grouping, totals, speed-tier time split,
 * chart downsampling and playback interpolation.
 */
object TripAnalytics {

    /** Same gap rule as [TripStats]: a jump longer than this is a recording gap, not driving. */
    const val MAX_GAP_MS = 60_000L
    const val LONG_TRIP_M = 50_000.0

    private val DAY_TITLE = DateTimeFormatter.ofPattern("M月d日")
    private val DAY_TITLE_WITH_YEAR = DateTimeFormatter.ofPattern("yyyy年M月d日")
    private val DATE_KEYS = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("yyyy/M/d"),
        DateTimeFormatter.ofPattern("M/d"),
        DateTimeFormatter.ofPattern("M月d日"),
    )
    private val WEEKDAY_NAMES = arrayOf("一", "二", "三", "四", "五", "六", "日")

    fun totals(trips: List<TripSummary>): TripTotals = TripTotals(
        count = trips.size,
        distanceM = trips.sumOf { it.distanceM },
        movingDurationMs = trips.sumOf { it.movingDurationMs },
        cameraCount = trips.sumOf { it.cameraCount },
        overspeedCount = trips.sumOf { it.overspeedCount },
        maxSpeedKmh = trips.maxOfOrNull { it.maxSpeedKmh } ?: 0.0,
    )

    /** Inclusive start (epoch ms) of [period] containing [nowMs]; weeks start on Monday. */
    fun periodStartMs(period: TripPeriod, nowMs: Long, zone: ZoneId): Long {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val start = when (period) {
            TripPeriod.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            TripPeriod.MONTH -> today.withDayOfMonth(1)
            TripPeriod.YEAR -> today.withDayOfYear(1)
            TripPeriod.ALL -> return Long.MIN_VALUE
        }
        return start.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /**
     * Filters [trips] by [period], [filter] and free [text] (matches destination or local start
     * date written as `2026-11-17`, `2026/11/17`, `11/17` or `11月17日`), then orders by [sort].
     */
    fun query(
        trips: List<TripSummary>,
        text: String = "",
        filter: TripFilter = TripFilter.ALL,
        sort: TripSort = TripSort.NEWEST,
        period: TripPeriod = TripPeriod.ALL,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<TripSummary> {
        val fromMs = periodStartMs(period, nowMs, zone)
        val needle = text.trim()
        val filtered = trips.filter { s ->
            s.startedAtMs >= fromMs &&
                when (filter) {
                    TripFilter.ALL -> true
                    TripFilter.OVERSPEED -> s.overspeedCount > 0
                    TripFilter.LONG -> s.distanceM >= LONG_TRIP_M
                    TripFilter.ABNORMAL -> s.abnormalEnd
                } &&
                (needle.isEmpty() || matchesText(s, needle, zone))
        }
        return when (sort) {
            TripSort.NEWEST -> filtered.sortedByDescending { it.startedAtMs }
            TripSort.OLDEST -> filtered.sortedBy { it.startedAtMs }
            TripSort.DISTANCE -> filtered.sortedWith(compareByDescending<TripSummary> { it.distanceM }.thenByDescending { it.startedAtMs })
            TripSort.MAX_SPEED -> filtered.sortedWith(compareByDescending<TripSummary> { it.maxSpeedKmh }.thenByDescending { it.startedAtMs })
            TripSort.OVERSPEED -> filtered.sortedWith(compareByDescending<TripSummary> { it.overspeedCount }.thenByDescending { it.startedAtMs })
        }
    }

    private fun matchesText(s: TripSummary, needle: String, zone: ZoneId): Boolean {
        if (s.destination?.contains(needle, ignoreCase = true) == true) return true
        val date = Instant.ofEpochMilli(s.startedAtMs).atZone(zone).toLocalDate()
        return DATE_KEYS.any { fmt -> fmt.format(date).contains(needle) }
    }

    /** Groups already-ordered [trips] by local start date, keeping their order. */
    fun groupByDay(
        trips: List<TripSummary>,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<TripDayGroup> {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val groups = LinkedHashMap<LocalDate, MutableList<TripSummary>>()
        for (s in trips) {
            val d = Instant.ofEpochMilli(s.startedAtMs).atZone(zone).toLocalDate()
            groups.getOrPut(d) { ArrayList() } += s
        }
        return groups.map { (date, list) -> TripDayGroup(date, dayTitle(date, today), list, totals(list)) }
    }

    /** `今天` / `昨天` / `11月15日（週日）`, with the year only when it differs from [today]'s. */
    fun dayTitle(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> {
            val fmt = if (date.year == today.year) DAY_TITLE else DAY_TITLE_WITH_YEAR
            "${fmt.format(date)}（週${WEEKDAY_NAMES[date.dayOfWeek.value - 1]}）"
        }
    }

    /**
     * Moving time spent in each [SpeedTier], using the same edge rules as [TripStats.summarize]
     * (positive gap ≤ [MAX_GAP_MS], both ends moving). Every tier is present, possibly 0.
     */
    fun tierDurations(points: List<TrackPoint>): Map<SpeedTier, Long> {
        val out = SpeedTier.entries.associateWithTo(LinkedHashMap()) { 0L }
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val cur = points[i]
            val dt = cur.tMs - prev.tMs
            if (dt <= 0L || dt > MAX_GAP_MS) continue
            if (prev.speedKmh < TrackThinning.MOVING_SPEED_KMH || cur.speedKmh < TrackThinning.MOVING_SPEED_KMH) continue
            val tier = SpeedTier.fromSpeed((prev.speedKmh + cur.speedKmh) / 2.0)
            out[tier] = out.getValue(tier) + dt
        }
        return out
    }

    /** Wall-clock time of the trip not spent moving (stops, traffic lights, gaps). */
    fun stoppedDurationMs(s: TripSummary): Long =
        (s.endedAtMs - s.startedAtMs - s.movingDurationMs).coerceAtLeast(0L)

    /**
     * Downsamples [points] to at most about [maxSamples] samples for charting while keeping peaks:
     * each bucket contributes its min and max (in time order). Gaps longer than [MAX_GAP_MS]
     * are bridged by a 0 km/h sample on each side so the chart shows the stop.
     */
    fun speedSeries(points: List<TrackPoint>, maxSamples: Int = 600): List<SpeedSample> {
        if (points.isEmpty()) return emptyList()
        val withGaps = ArrayList<SpeedSample>(points.size + 8)
        for (i in points.indices) {
            val p = points[i]
            if (i > 0 && p.tMs - points[i - 1].tMs > MAX_GAP_MS) {
                withGaps += SpeedSample(points[i - 1].tMs + 1, 0.0)
                withGaps += SpeedSample(p.tMs - 1, 0.0)
            }
            withGaps += SpeedSample(p.tMs, p.speedKmh.coerceAtLeast(0.0))
        }
        val limit = max(2, maxSamples)
        if (withGaps.size <= limit) return withGaps
        val bucketCount = limit / 2
        val bucketSize = withGaps.size.toDouble() / bucketCount
        val out = ArrayList<SpeedSample>(limit)
        for (b in 0 until bucketCount) {
            val from = (b * bucketSize).toInt()
            val to = min(withGaps.size, ((b + 1) * bucketSize).toInt()).coerceAtLeast(from + 1)
            var lo = withGaps[from]
            var hi = withGaps[from]
            for (k in from until to) {
                val s = withGaps[k]
                if (s.speedKmh < lo.speedKmh) lo = s
                if (s.speedKmh > hi.speedKmh) hi = s
            }
            if (lo === hi) out += lo
            else if (lo.tMs <= hi.tMs) { out += lo; out += hi }
            else { out += hi; out += lo }
        }
        return out
    }

    /** Index of the last point with `tMs <= t` (0 when [t] precedes the track), or -1 if empty. */
    fun indexAtOrBefore(points: List<TrackPoint>, t: Long): Int {
        if (points.isEmpty()) return -1
        var lo = 0
        var hi = points.lastIndex
        if (t <= points[0].tMs) return 0
        if (t >= points[hi].tMs) return hi
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (points[mid].tMs <= t) lo = mid else hi = mid - 1
        }
        return lo
    }

    /**
     * Position at time [t], clamped to the track. Interpolates linearly between neighbours
     * unless they are separated by a recording gap, where it holds the earlier point.
     */
    fun positionAt(points: List<TrackPoint>, t: Long): PlaybackPosition? {
        val i = indexAtOrBefore(points, t)
        if (i < 0) return null
        val a = points[i]
        val b = points.getOrNull(i + 1)
        val clampedT = t.coerceIn(points.first().tMs, points.last().tMs)
        if (b == null || b.tMs <= a.tMs || b.tMs - a.tMs > MAX_GAP_MS || clampedT <= a.tMs) {
            return PlaybackPosition(clampedT, LatLng(a.lat, a.lon), a.speedKmh, a.bearing, i)
        }
        val f = (clampedT - a.tMs).toDouble() / (b.tMs - a.tMs)
        val pos = LatLng(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
        val speed = a.speedKmh + (b.speedKmh - a.speedKmh) * f
        val bearing = b.bearing ?: a.bearing
            ?: GeoUtils.bearing(LatLng(a.lat, a.lon), LatLng(b.lat, b.lon)).toFloat()
        return PlaybackPosition(clampedT, pos, speed, bearing, i)
    }

    /**
     * Advances playback from [t] by [deltaMs] of track time, skipping recording gaps so a long
     * stop doesn't stall the animation. Returns the track end once it is reached.
     */
    fun advancePlayback(points: List<TrackPoint>, t: Long, deltaMs: Long): Long {
        if (points.isEmpty()) return t
        val end = points.last().tMs
        val next = (t + deltaMs).coerceAtMost(end)
        val i = indexAtOrBefore(points, t)
        val after = points.getOrNull(i + 1) ?: return next
        val gapStart = points[i].tMs
        return if (after.tMs - gapStart > MAX_GAP_MS && next < after.tMs) after.tMs else next
    }
}
