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
    fun perCameraToleranceWidensTheDirectionMatch() {
        // Camera heading 90° (east) but the road runs north: 90° off.
        val estimated = camera(listOf(90)).copy(headingToleranceDeg = 100)
        val strict = camera(listOf(90))
        assertEquals(
            listOf(AlertType.APPROACH, AlertType.NEAR, AlertType.PASSED),
            types(run(engine(estimated), drive(origin, 0.0, 60.0, 150))),
        )
        assertTrue(run(engine(strict), drive(origin, 0.0, 60.0, 150)).isEmpty())
        // Even ±100° still rules out travel in the opposite direction (180° off).
        val north = camera(listOf(0)).copy(headingToleranceDeg = 100)
        val start = GeoUtils.destinationPoint(origin, 2400.0, 0.0)
        assertTrue(run(engine(north), drive(start, 180.0, 60.0, 150)).isEmpty())
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

    @Test
    fun turningOntoCameraRoadAfterApproachingCrossStreetStillWarnsNearAndOverspeed() {
        val camOnTurn = SpeedCamera("turn", GeoUtils.destinationPoint(origin, 120.0, 90.0).lat, GeoUtils.destinationPoint(origin, 120.0, 90.0).lon, 50, emptyList())
        val start = GeoUtils.destinationPoint(origin, 480.0, 180.0)
        val approachLeg = drive(start, 0.0, 58.0, 30)
        val corner = approachLeg.last()
        val eastLeg = drive(corner.pos, 90.0, 58.0, 18, startMs = corner.timeMs + 1000)
        val events = types(run(engine(camOnTurn), approachLeg + eastLeg))
        assertTrue(AlertType.NEAR in events)
        assertTrue(AlertType.OVERSPEED in events)
        assertTrue(AlertType.PASSED in events)
    }

    @Test
    fun hairpinCurveApproachingTo100mThenLoopingBackStillWarnsWhenPassing() {
        val firstPass = drive(origin, 0.0, 60.0, 66) // stops ~100 m south of camPos
        val away = drive(firstPass.last().pos, 135.0, 60.0, 12, startMs = firstPass.last().timeMs + 1000)
        val turnPos = away.last().pos
        val bearingToCam = GeoUtils.bearing(turnPos, camPos)
        val secondPass = drive(turnPos, bearingToCam, 60.0, 30, startMs = away.last().timeMs + 1000)
        val ev = types(run(engine(camera(emptyList())), firstPass + away + secondPass))
        assertEquals(1, ev.count { it == AlertType.PASSED })
    }

    @Test
    fun overspeedUsesHysteresisAndTenSecondCooldown() {
        val e = engine(camera(listOf(0), limit = 60))
        val h = HeadingFilter()
        val fixes = ArrayList<Fix>()
        var pos = GeoUtils.destinationPoint(camPos, 450.0, 180.0)
        var t = 0L
        fun step(speedKmh: Double, secs: Int) {
            repeat(secs) {
                t += 1000L
                pos = GeoUtils.destinationPoint(pos, GeoUtils.kmhToMs(speedKmh), 0.0)
                fixes += Fix(pos, speedKmh, 0.0, t)
            }
        }
        step(62.0, 2) // APPROACH + first OVERSPEED at t=1000
        step(58.0, 2) // still > 60-3=57, hysteresis keeps overspeed=true
        step(62.0, 2) // no re-alert
        step(56.0, 2) // <= 57 resets overspeed=false (at t=7000..8000)
        step(62.0, 2) // t=9000..10000: within 10s of t=1000 -> suppressed by cooldown
        step(56.0, 4) // drops <= 57 again
        step(62.0, 2) // past 10s cooldown -> second OVERSPEED
        val overspeeds = fixes.flatMap { e.update(it, h.update(it)).events }.filter { it.type == AlertType.OVERSPEED }
        assertEquals(2, overspeeds.size)
    }

    @Test
    fun nearbyOverlappingCamerasMergeIntoSingleAlertWithLowestLimit() {
        val c1 = SpeedCamera("c1", camPos.lat, camPos.lon, 60, listOf(0), setOf(CameraKind.SPEED))
        val p2 = GeoUtils.destinationPoint(camPos, 25.0, 0.0)
        val c2 = SpeedCamera("c2", p2.lat, p2.lon, 50, listOf(0), setOf(CameraKind.RED_LIGHT))
        val events = run(engine(c1, c2), drive(origin, 0.0, 55.0, 150))
        assertEquals(
            listOf(AlertType.APPROACH, AlertType.OVERSPEED, AlertType.NEAR, AlertType.PASSED),
            types(events),
        )
        assertEquals(50, events.first().second.camera.limitKmh)
        assertEquals(setOf(CameraKind.SPEED, CameraKind.RED_LIGHT), events.first().second.camera.kinds)
    }

    @Test
    fun stationaryGpsDriftDoesNotChangeHeading() {
        val filter = HeadingFilter()
        filter.update(Fix(origin, 9.0, null, 0L))
        val north = GeoUtils.destinationPoint(origin, 20.0, 0.0)
        assertEquals(0.0, filter.update(Fix(north, 9.0, null, 2000L))!!, 1.0)
        val driftEast = GeoUtils.destinationPoint(north, 25.0, 90.0)
        assertEquals(0.0, filter.update(Fix(driftEast, 1.0, null, 3000L))!!, 1.0)
    }
}
