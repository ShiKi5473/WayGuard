package com.wayguard.core.trip

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripAnalyticsTest {

    private val zone = ZoneId.of("Asia/Taipei")

    /** 2026-11-17 (Tuesday) 12:00 Taipei. */
    private val nowMs = ms(2026, 11, 17, 12)

    private fun ms(y: Int, mo: Int, d: Int, h: Int, min: Int = 0): Long =
        ZonedDateTime.of(y, mo, d, h, min, 0, 0, zone).toInstant().toEpochMilli()

    private fun trip(
        id: String,
        startMs: Long,
        distanceM: Double = 10_000.0,
        maxKmh: Double = 80.0,
        over: Int = 0,
        dest: String? = null,
        abnormal: Boolean = false,
    ) = TripSummary(
        id = id, startedAtMs = startMs, endedAtMs = startMs + 1_800_000L, distanceM = distanceM,
        movingDurationMs = 1_200_000L, maxSpeedKmh = maxKmh, avgMovingSpeedKmh = 30.0,
        cameraCount = 2, overspeedCount = over, startLat = 0.0, startLon = 0.0, endLat = 0.0, endLon = 0.0,
        destination = dest, abnormalEnd = abnormal,
    )

    private val trips = listOf(
        trip("today", ms(2026, 11, 17, 8), distanceM = 12_000.0, over = 1, dest = "台中車站"),
        trip("monday", ms(2026, 11, 16, 18), distanceM = 60_000.0, maxKmh = 115.0),
        trip("sunday", ms(2026, 11, 15, 9), distanceM = 5_000.0, abnormal = true),
        trip("october", ms(2026, 10, 3, 9), distanceM = 80_000.0, over = 3, dest = "高雄"),
        trip("lastyear", ms(2025, 12, 31, 23), distanceM = 1_000.0),
    )

    @Test
    fun periodStartUsesMondayWeeksAndLocalCalendar() {
        assertEquals(ms(2026, 11, 16, 0), TripAnalytics.periodStartMs(TripPeriod.WEEK, nowMs, zone))
        assertEquals(ms(2026, 11, 1, 0), TripAnalytics.periodStartMs(TripPeriod.MONTH, nowMs, zone))
        assertEquals(ms(2026, 1, 1, 0), TripAnalytics.periodStartMs(TripPeriod.YEAR, nowMs, zone))
        assertEquals(Long.MIN_VALUE, TripAnalytics.periodStartMs(TripPeriod.ALL, nowMs, zone))
    }

    @Test
    fun queryFiltersByPeriodFilterTextAndSorts() {
        fun ids(list: List<TripSummary>) = list.map { it.id }

        assertEquals(listOf("today", "monday"), ids(TripAnalytics.query(trips, period = TripPeriod.WEEK, nowMs = nowMs, zone = zone)))
        assertEquals(listOf("today", "monday", "sunday"), ids(TripAnalytics.query(trips, period = TripPeriod.MONTH, nowMs = nowMs, zone = zone)))
        assertEquals(listOf("today", "october"), ids(TripAnalytics.query(trips, filter = TripFilter.OVERSPEED, nowMs = nowMs, zone = zone)))
        assertEquals(listOf("october", "monday"), ids(TripAnalytics.query(trips, filter = TripFilter.LONG, sort = TripSort.DISTANCE, nowMs = nowMs, zone = zone)))
        assertEquals(listOf("sunday"), ids(TripAnalytics.query(trips, filter = TripFilter.ABNORMAL, nowMs = nowMs, zone = zone)))
        assertEquals(listOf("monday"), ids(TripAnalytics.query(trips, sort = TripSort.MAX_SPEED, nowMs = nowMs, zone = zone)).take(1))
        assertEquals("lastyear", TripAnalytics.query(trips, sort = TripSort.OLDEST, nowMs = nowMs, zone = zone).first().id)

        // Destination text and several local date spellings.
        assertEquals(listOf("october"), ids(TripAnalytics.query(trips, text = "高雄", nowMs = nowMs, zone = zone)))
        assertEquals(listOf("today"), ids(TripAnalytics.query(trips, text = "2026-11-17", nowMs = nowMs, zone = zone)))
        assertEquals(listOf("monday"), ids(TripAnalytics.query(trips, text = "11/16", nowMs = nowMs, zone = zone)))
        assertEquals(listOf("october"), ids(TripAnalytics.query(trips, text = "10月3日", nowMs = nowMs, zone = zone)))
        // 23:00 Taipei on 12/31 is still 2025 locally even though UTC is earlier the same day.
        assertEquals(listOf("lastyear"), ids(TripAnalytics.query(trips, text = "2025/12/31", nowMs = nowMs, zone = zone)))
    }

    @Test
    fun groupsByLocalDayWithFriendlyTitlesAndTotals() {
        val groups = TripAnalytics.groupByDay(trips, nowMs, zone)
        assertEquals(listOf("今天", "昨天", "11月15日（週日）", "10月3日（週六）", "2025年12月31日（週三）"), groups.map { it.title })
        assertEquals(12_000.0, groups[0].totals.distanceM, 0.0)
        assertEquals(1, groups[0].totals.overspeedCount)

        val totals = TripAnalytics.totals(trips)
        assertEquals(5, totals.count)
        assertEquals(158_000.0, totals.distanceM, 0.0)
        assertEquals(115.0, totals.maxSpeedKmh, 0.0)
        assertEquals(4.0 / 1.58, totals.overspeedPer100Km, 1e-9)
        assertEquals("昨天", TripAnalytics.dayTitle(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2)))
    }

    private fun pt(tSec: Long, kmh: Double, lat: Double = 24.0, lon: Double = 121.0) =
        TrackPoint(tMs = tSec * 1000L, lat = lat, lon = lon, speedKmh = kmh)

    @Test
    fun tierDurationsCountOnlyMovingEdgesWithinGapLimit() {
        val pts = listOf(
            pt(0, 20.0), pt(10, 20.0),     // 10 s SLOW
            pt(20, 70.0), pt(30, 70.0),    // 10 s (avg 45 → CITY) + 10 s ARTERIAL
            pt(200, 70.0),                 // 170 s gap: ignored
            pt(210, 1.0),                  // stopped end: ignored
        )
        val d = TripAnalytics.tierDurations(pts)
        assertEquals(SpeedTier.entries.toSet(), d.keys)
        assertEquals(10_000L, d[SpeedTier.SLOW])
        assertEquals(10_000L, d[SpeedTier.CITY])
        assertEquals(10_000L, d[SpeedTier.ARTERIAL])
        assertEquals(0L, d[SpeedTier.HIGH_SPEED])
        assertEquals(600_000L, TripAnalytics.stoppedDurationMs(trips[0]))
    }

    @Test
    fun speedSeriesKeepsPeaksAndMarksGapsAsStops() {
        val pts = (0 until 1_000).map { i -> pt(i.toLong(), if (i == 500) 140.0 else 60.0) }
        val series = TripAnalytics.speedSeries(pts, maxSamples = 100)
        assertTrue(series.size <= 100)
        assertEquals(140.0, series.maxOf { it.speedKmh }, 0.0)
        assertTrue(series.zipWithNext().all { (a, b) -> a.tMs <= b.tMs })

        val gapped = TripAnalytics.speedSeries(listOf(pt(0, 50.0), pt(10, 50.0), pt(300, 50.0)))
        assertEquals(listOf(50.0, 50.0, 0.0, 0.0, 50.0), gapped.map { it.speedKmh })
    }

    @Test
    fun playbackInterpolatesHoldsAcrossGapsAndSkipsThem() {
        val pts = listOf(
            pt(0, 40.0, lat = 24.0, lon = 121.0),
            pt(10, 60.0, lat = 24.001, lon = 121.0),
            pt(300, 30.0, lat = 24.01, lon = 121.0),
            pt(310, 30.0, lat = 24.011, lon = 121.0),
        )
        val mid = TripAnalytics.positionAt(pts, 5_000L)!!
        assertEquals(24.0005, mid.pos.lat, 1e-9)
        assertEquals(50.0, mid.speedKmh, 1e-9)
        assertEquals(0, mid.index)

        // Inside the recording gap the marker holds at the last fix instead of sliding across town.
        val inGap = TripAnalytics.positionAt(pts, 100_000L)!!
        assertEquals(24.001, inGap.pos.lat, 1e-9)
        assertEquals(1, inGap.index)

        // Clamped at both ends.
        assertEquals(0L, TripAnalytics.positionAt(pts, -5_000L)!!.tMs)
        assertEquals(310_000L, TripAnalytics.positionAt(pts, 999_000L)!!.tMs)

        assertEquals(8_000L, TripAnalytics.advancePlayback(pts, 3_000L, 5_000L))
        assertEquals(300_000L, TripAnalytics.advancePlayback(pts, 11_000L, 5_000L))
        assertEquals(310_000L, TripAnalytics.advancePlayback(pts, 305_000L, 60_000L))
        assertEquals(2, TripAnalytics.indexAtOrBefore(pts, 300_000L))
    }
}
