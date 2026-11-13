package com.wayguard.core.trip

import com.wayguard.core.engine.AlertEvent
import com.wayguard.core.engine.AlertType
import com.wayguard.core.engine.Fix
import com.wayguard.core.engine.SectionEvent
import com.wayguard.core.engine.SectionEventType
import com.wayguard.core.engine.SectionStatus
import com.wayguard.core.model.Section
import com.wayguard.core.model.SpeedCamera
import com.waymap.geo.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripRecorderTest {

    @Test
    fun trackThinningDropsStoppedAndThinsByDistanceBearingAndTime() {
        val base = TrackPoint(tMs = 1_000_000L, lat = 25.0330, lon = 121.5654, speedKmh = 40.0, bearing = 0f)
        // Stationary fix must be dropped even far away / later.
        assertFalse(TrackThinning.shouldRecord(base.copy(tMs = 1_010_000L, speedKmh = 2.9), base))
        // First moving point always accepted.
        assertTrue(TrackThinning.shouldRecord(base, null))
        // Same spot, 1 s later, same bearing -> dropped.
        assertFalse(TrackThinning.shouldRecord(base.copy(tMs = 1_001_000L, lat = 25.03301, bearing = 5f), base))
        // Heading change >= 15 deg -> kept.
        assertTrue(TrackThinning.shouldRecord(base.copy(tMs = 1_001_000L, bearing = 16f), base))
        // >= 5 s elapsed -> kept.
        assertTrue(TrackThinning.shouldRecord(base.copy(tMs = 1_005_000L), base))
        // >= 10 m moved (~0.0001 deg lat is ~11.1 m) -> kept.
        assertTrue(TrackThinning.shouldRecord(base.copy(tMs = 1_001_000L, lat = 25.03310), base))
    }

    @Test
    fun stoppedDriftIgnoredAndSinglePointSpeedSpikeFiltered() {
        val t0 = 1_700_000_000_000L
        val points = listOf(
            TrackPoint(t0, 25.0330, 121.5654, speedKmh = 50.0),
            TrackPoint(t0 + 2_000L, 25.03325, 121.5654, speedKmh = 50.0),
            // Isolated 1-point GPS tunnel spike (185 km/h surrounded by 50 km/h)
            TrackPoint(t0 + 4_000L, 25.03350, 121.5654, speedKmh = 185.0),
            TrackPoint(t0 + 6_000L, 25.03375, 121.5654, speedKmh = 52.0),
            // Stationary drift at red light (speed < 3 km/h) -> ignored in distance/movingDuration
            TrackPoint(t0 + 16_000L, 25.03375, 121.5654, speedKmh = 0.5),
            TrackPoint(t0 + 26_000L, 25.03390, 121.5654, speedKmh = 1.2),
        )
        val summary = TripStats.summarize("trip-1", t0, t0 + 26_000L, points, emptyList())
        assertEquals(52.0, summary.maxSpeedKmh, 0.1)
        // Moving duration should only be 6 s (from t0 to t0 + 6_000L).
        assertEquals(6_000L, summary.movingDurationMs)
        assertTrue(summary.distanceM in 70.0..100.0)
    }

    @Test
    fun recoversIncompleteJsonlWithTruncatedTailLine() {
        val t0 = 1_700_000_000_000L
        val lines = listOf(
            """{"meta":{"id":"trip-crash","startedAtMs":$t0,"source":"GOOGLE_MAPS","destination":"台北101"}}""",
            """{"point":{"tMs":$t0,"lat":25.0330,"lon":121.5654,"speedKmh":45.0,"bearing":0.0}}""",
            """{"point":{"tMs":${t0 + 5000},"lat":25.0335,"lon":121.5654,"speedKmh":48.0,"bearing":0.0}}""",
            """{"event":{"tMs":${t0 + 5000},"type":"OVERSPEED","lat":25.0335,"lon":121.5654,"speedKmh":62.0,"limitKmh":50}}""",
            """{"point":{"tMs":${t0 + 10000},"lat":25.034""", // truncated tail line
        )
        val detail = TripStats.decodeJsonl("trip-crash", lines, assumeIncompleteFile = true)
        assertNotNull(detail)
        val d = detail!!
        assertEquals("trip-crash", d.summary.id)
        assertTrue(d.summary.abnormalEnd)
        assertEquals(TripSource.GOOGLE_MAPS, d.summary.source)
        assertEquals("台北101", d.summary.destination)
        assertEquals(2, d.points.size)
        assertEquals(1, d.events.size)
        assertEquals(1, d.summary.overspeedCount)
    }

    @Test
    fun autoSplitsAfterParkedBreakAndResumedDriving() {
        var seq = 0
        val recorder = TripRecorder(autoSplitMinutes = 15, idFactory = { "trip-${++seq}" })
        val t0 = 1_700_000_000_000L

        // Leg 1 driving
        assertNull(
            recorder.recordFix(
                t0,
                Fix(LatLng(25.0330, 121.5654), speedKmh = 45.0, bearing = 0.0, timeMs = 1_000L),
                5f,
                emptyList(),
                emptyList(),
            )
        )
        assertNull(
            recorder.recordFix(
                t0 + 5_000L,
                Fix(LatLng(25.0336, 121.5654), speedKmh = 50.0, bearing = 0.0, timeMs = 6_000L),
                5f,
                emptyList(),
                emptyList(),
            )
        )

        // Parked at t0 + 10s
        assertNull(
            recorder.recordFix(
                t0 + 10_000L,
                Fix(LatLng(25.0336, 121.5654), speedKmh = 0.0, bearing = 0.0, timeMs = 11_000L),
                5f,
                emptyList(),
                emptyList(),
            )
        )

        // Resume driving 16 minutes later -> auto-split completes leg 1 and starts leg 2
        val splitTime = t0 + 10_000L + 16 * 60_000L
        val completedLeg1 = recorder.recordFix(
            splitTime,
            Fix(LatLng(25.0340, 121.5654), speedKmh = 40.0, bearing = 90.0, timeMs = 1_000_000L),
            5f,
            emptyList(),
            emptyList(),
        )
        assertNotNull(completedLeg1)
        assertEquals("trip-1", completedLeg1!!.summary.id)
        assertEquals(2, completedLeg1.points.size)
        assertEquals("trip-2", recorder.currentTripId)

        val completedLeg2 = recorder.finish(splitTime + 5_000L)
        assertNotNull(completedLeg2)
        assertEquals("trip-2", completedLeg2!!.summary.id)
        assertEquals(1, completedLeg2.points.size)
    }

    @Test
    fun buildsSegmentsAndExportsValidGpxXml() {
        val t0 = 1_700_000_000_000L
        val cam = SpeedCamera(
            id = "npa-1",
            lat = 25.034,
            lon = 121.5654,
            limitKmh = 50,
            city = "臺北市",
            district = "信義區",
            address = "市府路 & 松壽路口",
        )
        val recorder = TripRecorder(idFactory = { "trip-gpx" })
        recorder.recordFix(
            t0,
            Fix(LatLng(25.0330, 121.5654), speedKmh = 45.0, bearing = 0.0, timeMs = 100L),
            4f,
            listOf(AlertEvent(AlertType.APPROACH, cam, 200.0, 45.0)),
            emptyList(),
        )
        recorder.recordFix(
            t0 + 5_000L,
            Fix(LatLng(25.0338, 121.5654), speedKmh = 68.0, bearing = 0.0, timeMs = 5_100L),
            4f,
            listOf(AlertEvent(AlertType.OVERSPEED, cam, 50.0, 68.0)),
            emptyList(),
        )
        val sec = Section(
            id = "sec-1",
            name = "測試區間",
            startLat = 25.0338,
            startLon = 121.5654,
            endLat = 25.0400,
            endLon = 121.5654,
            lengthM = 700.0,
            limitKmh = 60,
            heading = 0,
        )
        recorder.recordFix(
            t0 + 10_000L,
            Fix(LatLng(25.0345, 121.5654), speedKmh = 55.0, bearing = 0.0, timeMs = 10_100L),
            4f,
            listOf(AlertEvent(AlertType.PASSED, cam, 60.0, 55.0)),
            listOf(SectionEvent(SectionEventType.ENTER, SectionStatus(sec, 0.0, 0.0, null, 700.0, 60.0, false))),
        )

        val detail = recorder.finish(t0 + 10_000L)!!
        assertEquals(2, detail.summary.cameraCount) // 1 camera + 1 section entry
        assertEquals(1, detail.summary.overspeedCount)

        val segs = TripStats.buildSegments(detail.points, detail.events)
        assertTrue(segs.any { it.overspeed })
        assertNotNull(TripStats.computeCenterAndZoom(detail.points, detail.events))

        val gpx = GpxWriter.write(detail)
        assertTrue(gpx.contains("""<gpx version="1.1""""))
        assertTrue(gpx.contains("<wpt lat=\"25.033800\" lon=\"121.565400\">"))
        assertTrue(gpx.contains("<trkpt lat=\"25.033000\" lon=\"121.565400\">"))
        assertTrue(gpx.contains("臺北市 信義區 市府路 &amp; 松壽路口"))
    }
}
