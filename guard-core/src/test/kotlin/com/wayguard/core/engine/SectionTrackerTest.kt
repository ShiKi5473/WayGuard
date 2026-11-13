package com.wayguard.core.engine

import com.wayguard.core.model.Section
import com.waymap.geo.GeoUtils
import com.waymap.geo.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionTrackerTest {

    private val start = LatLng(25.0, 121.5)
    private val end = GeoUtils.destinationPoint(start, 2000.0, 0.0)
    private val section = Section("s1", "測試區間", start.lat, start.lon, end.lat, end.lon, 2000.0, 90, heading = 0)

    private fun runSection(speedKmh: Double, bearing: Double = 0.0, from: LatLng = GeoUtils.destinationPoint(start, 200.0, 180.0)):
        Pair<List<SectionEvent>, List<SectionStatus?>> {
        val tracker = SectionTracker().apply { setSections(listOf(section)) }
        val heading = HeadingFilter()
        val seconds = (2600 / GeoUtils.kmhToMs(speedKmh)).toInt()
        val events = ArrayList<SectionEvent>()
        val statuses = ArrayList<SectionStatus?>()
        for (f in drive(from, bearing, speedKmh, seconds)) {
            val (ev, st) = tracker.update(f, heading.update(f))
            events += ev
            statuses += st
        }
        return events to statuses
    }

    @Test
    fun overTheLimitIsFlaggedAndFinalAverageReported() {
        val (events, _) = runSection(100.0)
        assertEquals(
            listOf(SectionEventType.ENTER, SectionEventType.OVER_LIMIT, SectionEventType.COMPLETE),
            events.map { it.type },
        )
        val done = events.last().status
        assertEquals(100.0, done.averageKmh!!, 3.0)
        assertTrue(done.over)
    }

    @Test
    fun underTheLimitCompletesWithoutWarning() {
        val (events, statuses) = runSection(80.0)
        assertEquals(listOf(SectionEventType.ENTER, SectionEventType.COMPLETE), events.map { it.type })
        assertEquals(80.0, events.last().status.averageKmh!!, 3.0)
        assertFalse(events.last().status.over)
        // While inside, a speed that keeps the average legal is suggested, and it is above 80.
        val mid = statuses.filterNotNull().first { it.traveledM > 1000 }
        assertNotNull(mid.maxSpeedForRemainingKmh)
        assertTrue(mid.maxSpeedForRemainingKmh!! > 80.0)
        // Tracking stops after the end.
        assertNull(statuses.last())
    }

    /** Drives north from 200 m before the start, with no GPS fix while [lost] is true for the distance driven. */
    private fun runWithGpsLoss(speedKmh: Double, lost: (Double) -> Boolean, target: Section = section): List<SectionEvent> {
        val tracker = SectionTracker().apply { setSections(listOf(target)) }
        val heading = HeadingFilter()
        val from = GeoUtils.destinationPoint(start, 200.0, 180.0)
        val step = GeoUtils.kmhToMs(speedKmh)
        val seconds = (2800 / step).toInt()
        return drive(from, 0.0, speedKmh, seconds)
            .filterIndexed { i, _ -> !lost(i * step) }
            .flatMap { tracker.update(it, heading.update(it)).first }
    }

    @Test
    fun endInsideTunnelStillCompletes() {
        // GPS lost from 500 m before the end camera until 300 m past it.
        val events = runWithGpsLoss(80.0, lost = { d -> d in 1700.0..2500.0 })
        assertEquals(listOf(SectionEventType.ENTER, SectionEventType.COMPLETE), events.map { it.type })
        // The crossing time is interpolated, so the average is the real one.
        assertEquals(80.0, events.last().status.averageKmh!!, 3.0)
    }

    @Test
    fun wholeSectionInsideTunnelEntersAndCompletes() {
        // GPS lost from 100 m before the start until 300 m past the end.
        val events = runWithGpsLoss(100.0, lost = { d -> d in 100.0..2500.0 })
        assertEquals(listOf(SectionEventType.ENTER, SectionEventType.COMPLETE), events.map { it.type })
        assertEquals(100.0, events.last().status.averageKmh!!, 3.0)
        assertTrue(events.last().status.over)
    }

    @Test
    fun consecutiveTunnelsInOneGpsLossBothComplete() {
        // A second section starts 60 m after the first ends; GPS returns only after both.
        val start2 = GeoUtils.destinationPoint(end, 60.0, 0.0)
        val end2 = GeoUtils.destinationPoint(start2, 400.0, 0.0)
        val second = Section("s2", "下一個隧道", start2.lat, start2.lon, end2.lat, end2.lon, 400.0, 70, heading = 0)
        val tracker = SectionTracker().apply { setSections(listOf(section, second)) }
        val heading = HeadingFilter()
        val from = GeoUtils.destinationPoint(start, 200.0, 180.0)
        val step = GeoUtils.kmhToMs(80.0)
        val events = drive(from, 0.0, 80.0, (3000 / step).toInt())
            .filterIndexed { i, _ -> i * step !in 100.0..2900.0 }
            .flatMap { tracker.update(it, heading.update(it)).first }
        assertEquals(
            listOf(SectionEventType.ENTER, SectionEventType.COMPLETE, SectionEventType.ENTER, SectionEventType.COMPLETE),
            events.map { it.type },
        )
        assertEquals(listOf("s1", "s1", "s2", "s2"), events.map { it.status.section.id })
        assertEquals(80.0, events.last().status.averageKmh!!, 3.0)
    }

    @Test
    fun curvedTunnelEndOffTheStraightLineCompletes() {
        // The end camera sits 120 m beside the straight line between the fixes around the gap.
        val offEnd = GeoUtils.destinationPoint(end, 120.0, 90.0)
        val curved = section.copy(endLat = offEnd.lat, endLon = offEnd.lon)
        val events = runWithGpsLoss(80.0, lost = { d -> d in 1700.0..2500.0 }, target = curved)
        assertEquals(listOf(SectionEventType.ENTER, SectionEventType.COMPLETE), events.map { it.type })
    }

    @Test
    fun withoutGpsLossPassingBesideTheEndDoesNotComplete() {
        // Same 120 m offset with GPS working: not close enough, so it aborts instead of completing.
        val offEnd = GeoUtils.destinationPoint(end, 120.0, 90.0)
        val curved = section.copy(endLat = offEnd.lat, endLon = offEnd.lon)
        val events = runWithGpsLoss(80.0, lost = { false }, target = curved)
        assertEquals(listOf(SectionEventType.ENTER, SectionEventType.ABORT), events.map { it.type })
    }

    @Test
    fun wrongDirectionNeverEnters() {
        val from = GeoUtils.destinationPoint(start, 300.0, 0.0)
        val (events, _) = runSection(80.0, bearing = 180.0, from = from)
        assertTrue(events.isEmpty())
    }

    @Test
    fun drivingOffCourseAbortsSoonAndStoppedDriftDoesNotAccumulateDistance() {
        val tracker = SectionTracker().apply { setSections(listOf(section)) }
        val enterFixes = drive(GeoUtils.destinationPoint(start, 100.0, 180.0), 0.0, 60.0, 10)
        var t = enterFixes.last().timeMs
        var pos = enterFixes.last().pos
        val events = ArrayList<SectionEvent>()
        var lastStatus: SectionStatus? = null
        for (f in enterFixes) {
            val (ev, st) = tracker.update(f, 0.0)
            events += ev
            lastStatus = st
        }
        val beforeDrift = lastStatus!!.traveledM
        // Stationary GPS jitter (speed < 3 km/h) must not add to traveledM.
        repeat(5) {
            t += 1000L
            val jitter = GeoUtils.destinationPoint(pos, 15.0, if (it % 2 == 0) 90.0 else 270.0)
            val (ev, st) = tracker.update(Fix(jitter, 1.0, 0.0, t), 0.0)
            events += ev
            lastStatus = st
        }
        assertEquals(beforeDrift, lastStatus!!.traveledM, 1e-6)
        // Turn East off the section route: aborts within ~250 m without driving 1.5x length + 500 m.
        for (f in drive(pos, 90.0, 72.0, 20, startMs = t + 1000L)) {
            events += tracker.update(f, 90.0).first
        }
        assertEquals(listOf(SectionEventType.ENTER, SectionEventType.ABORT), events.map { it.type })
    }
}
