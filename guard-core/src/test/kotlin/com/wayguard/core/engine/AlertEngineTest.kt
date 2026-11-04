package com.wayguard.core.engine

import com.wayguard.core.index.GridIndex
import com.wayguard.core.model.CameraKind
import com.wayguard.core.model.SpeedCamera
import com.waymap.geo.GeoUtils
import com.waymap.geo.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertEngineTest {

    private val origin = LatLng(25.0, 121.5)
    /** 1.2 km north of [origin]. */
    private val camPos = GeoUtils.destinationPoint(origin, 1200.0, 0.0)

    private fun camera(headings: List<Int>, limit: Int? = 60, kinds: Set<CameraKind> = setOf(CameraKind.SPEED)) =
        SpeedCamera("c1", camPos.lat, camPos.lon, limit, headings, kinds)

    private fun engine(vararg cams: SpeedCamera, config: AlertConfig = AlertConfig()) =
        AlertEngine(config).apply { setCameras(GridIndex(cams.toList(), { it.pos })) }

    private fun types(events: List<Pair<Fix, AlertEvent>>) = events.map { it.second.type }

    @Test
    fun northboundPastOneWayCameraWarnsTwiceThenPasses() {
        val events = run(engine(camera(listOf(0))), drive(origin, 0.0, 60.0, 150))
        assertEquals(listOf(AlertType.APPROACH, AlertType.NEAR, AlertType.PASSED), types(events))
        // 60 km/h = 16.7 m/s per fix: the first fix inside each radius.
        assertTrue(events[0].second.distanceM in 483.0..500.0)
        assertTrue(events[1].second.distanceM in 133.0..150.0)
    }

    @Test
    fun oppositeDirectionOfOneWayCameraIsSilent() {
        val start = GeoUtils.destinationPoint(origin, 2400.0, 0.0)
        assertTrue(run(engine(camera(listOf(0))), drive(start, 180.0, 60.0, 150)).isEmpty())
    }

    @Test
    fun bidirectionalCameraWarnsBothWays() {
        val start = GeoUtils.destinationPoint(origin, 2400.0, 0.0)
        val events = run(engine(camera(emptyList())), drive(start, 180.0, 60.0, 150))
        assertEquals(listOf(AlertType.APPROACH, AlertType.NEAR, AlertType.PASSED), types(events))
    }

    @Test
    fun parallelRoad300mAwayIsSilent() {
        val parallel = GeoUtils.destinationPoint(origin, 300.0, 90.0)
        assertTrue(run(engine(camera(emptyList())), drive(parallel, 0.0, 60.0, 150)).isEmpty())
    }

    @Test
    fun cameraBehindIsSilent() {
        val start = GeoUtils.destinationPoint(camPos, 100.0, 0.0)
        assertTrue(run(engine(camera(emptyList())), drive(start, 0.0, 60.0, 60)).isEmpty())
    }

    @Test
    fun overspeedIsReportedOncePerApproach() {
        // 90 km/h → fast band (800 m first warning); limit 60.
        val events = run(engine(camera(listOf(0))), drive(origin, 0.0, 90.0, 100))
        assertEquals(
            listOf(AlertType.APPROACH, AlertType.OVERSPEED, AlertType.NEAR, AlertType.PASSED),
            types(events),
        )
        assertTrue(events[0].second.distanceM in 775.0..800.0)
    }

    @Test
    fun toleranceSuppressesSmallOverspeed() {
        val cfg = AlertConfig(overspeedToleranceKmh = 10)
        val events = run(engine(camera(listOf(0), limit = 60), config = cfg), drive(origin, 0.0, 65.0, 150))
        assertTrue(AlertType.OVERSPEED !in types(events))
    }

    @Test
    fun stoppedVehicleGetsNoWarnings() {
        val start = GeoUtils.destinationPoint(origin, 900.0, 0.0)
        assertTrue(run(engine(camera(emptyList())), drive(start, 0.0, 0.0, 30)).isEmpty())
    }

    @Test
    fun turningAwayEndsWithoutAPassAnnouncement() {
        val toCorner = drive(origin, 0.0, 60.0, 50) // ~833 m north, 367 m before the camera
        val corner = toCorner.last()
        val east = drive(corner.pos, 90.0, 60.0, 60, startMs = corner.timeMs + 1000)
        val events = run(engine(camera(emptyList())), toCorner + east)
        assertEquals(listOf(AlertType.APPROACH), types(events))
    }

    @Test
    fun disabledKindsAreIgnored() {
        val cfg = AlertConfig(enabledKinds = setOf(CameraKind.RED_LIGHT))
        val events = run(engine(camera(listOf(0)), config = cfg), drive(origin, 0.0, 60.0, 150))
        assertTrue(events.isEmpty())
    }

    @Test
    fun activeCameraReportsDistanceWhileApproaching() {
        val e = engine(camera(listOf(0)))
        val heading = HeadingFilter()
        val results = drive(origin, 0.0, 60.0, 60).map { e.update(it, heading.update(it)) }
        val active = results.mapNotNull { it.active }
        assertTrue(active.isNotEmpty())
        assertTrue(active.zipWithNext().all { (a, b) -> b.distanceM < a.distanceM })
    }
}
