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

    @Test
    fun wrongDirectionNeverEnters() {
        val from = GeoUtils.destinationPoint(start, 300.0, 0.0)
        val (events, _) = runSection(80.0, bearing = 180.0, from = from)
        assertTrue(events.isEmpty())
    }
}
