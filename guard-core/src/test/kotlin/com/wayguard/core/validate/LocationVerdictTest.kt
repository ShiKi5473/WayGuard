package com.wayguard.core.validate

import com.wayguard.core.model.SpeedCamera
import com.waymap.geo.GeoUtils
import com.waymap.geo.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationVerdictTest {

    @Test
    fun thresholdsAndRounding() {
        assertEquals(0, LocationVerdict.roundDistance10m(4.0))
        assertEquals(10, LocationVerdict.roundDistance10m(6.0))
        assertEquals(200, LocationVerdict.roundDistance10m(196.0))

        assertEquals(VerdictLevel.OK, LocationVerdict.forIntersection(60.0))
        assertEquals(VerdictLevel.SUSPECT, LocationVerdict.forIntersection(60.1))
        assertEquals(VerdictLevel.SUSPECT, LocationVerdict.forIntersection(300.0))
        assertEquals(VerdictLevel.MISMATCH, LocationVerdict.forIntersection(300.1))

        assertEquals(VerdictLevel.OK, LocationVerdict.forRoad(30.0))
        assertEquals(VerdictLevel.SUSPECT, LocationVerdict.forRoad(30.1))
        assertEquals(VerdictLevel.SUSPECT, LocationVerdict.forRoad(150.0))
        assertEquals(VerdictLevel.MISMATCH, LocationVerdict.forRoad(150.1))
    }

    @Test
    fun adminDistrictChecksRespectBorderTolerance() {
        assertEquals(VerdictLevel.OK, LocationVerdict.forAdmin("臺北市", "中山區", "台北市", "中山區", 500.0))
        // Different district but within 200 m of border -> tolerated as OK
        assertEquals(VerdictLevel.OK, LocationVerdict.forAdmin("臺北市", "中山區", "臺北市", "大同區", 180.0))
        // Different district far inside -> SUSPECT
        assertEquals(VerdictLevel.SUSPECT, LocationVerdict.forAdmin("臺北市", "中山區", "臺北市", "大同區", 250.0))
        // Different city far inside -> MISMATCH
        assertEquals(VerdictLevel.MISMATCH, LocationVerdict.forAdmin("臺南市", "善化區", "高雄市", "路竹區", 500.0))
        // Unknown boundary -> UNVERIFIABLE
        assertEquals(VerdictLevel.UNVERIFIABLE, LocationVerdict.forAdmin("臺南市", "善化區", null, null, null))
    }

    @Test
    fun combineTakesMostSevereAndMapsLocTag() {
        assertEquals(
            VerdictLevel.MISMATCH,
            LocationVerdict.combine(VerdictLevel.OK, VerdictLevel.SUSPECT, VerdictLevel.MISMATCH),
        )
        assertEquals(
            VerdictLevel.SUSPECT,
            LocationVerdict.combine(VerdictLevel.OK, VerdictLevel.SUSPECT, VerdictLevel.UNVERIFIABLE),
        )
        assertEquals(VerdictLevel.OK, LocationVerdict.combine(VerdictLevel.OK, VerdictLevel.UNVERIFIABLE))
        assertEquals(VerdictLevel.UNVERIFIABLE, LocationVerdict.combine(VerdictLevel.UNVERIFIABLE))

        assertNull(VerdictLevel.OK.locTag)
        assertNull(VerdictLevel.UNVERIFIABLE.locTag)
        assertEquals("suspect", VerdictLevel.SUSPECT.locTag)
        assertEquals("mismatch", VerdictLevel.MISMATCH.locTag)
    }

    @Test
    fun findInternalConflictsFlagsDifferentRoadsWithin10mOnly() {
        val cams = listOf(
            SpeedCamera("npa-4fd28f09de", 25.054731, 121.52277, city = "臺北市", district = "中山區", address = "建國北路農安街口"),
            SpeedCamera("npa-a8eb2eec00", 25.054731, 121.52277, city = "臺北市", district = "中山區", address = "中山北路2段長春路口"),
            // Same road split per heading (-h0 / -h180) must never conflict
            SpeedCamera("npa-d63440e763-h0", 22.637, 120.605, headings = listOf(0), city = "屏東縣", address = "187丙線18.6K科大路一段"),
            SpeedCamera("npa-d63440e763-h180", 22.637, 120.605, headings = listOf(180), city = "屏東縣", address = "187丙線18.6K科大路一段"),
            // Opposite cameras at the same spot on the same route (route ref only) must not conflict
            SpeedCamera("npa-3f91f99676", 24.896, 121.377, city = "新北市", address = "台7乙線5.3公里（往桃園）"),
            SpeedCamera("npa-716aef7f18", 24.89602, 121.37702, city = "新北市", address = "台7乙線5.3K至8.1K（雙向）"),
        )
        val conflicts = LocationVerdict.findInternalConflicts(cams)
        assertEquals(setOf("npa-4fd28f09de", "npa-a8eb2eec00"), conflicts.keys)
        assertEquals(VerdictLevel.SUSPECT, conflicts.getValue("npa-4fd28f09de").verdict)
        assertEquals("internal", conflicts.getValue("npa-4fd28f09de").method)
    }

    @Test
    fun sameConclusionIgnoresCheckedAtTimestamp() {
        val a = LocationCheck(VerdictLevel.SUSPECT, "road", 120, reason = "偏離 120m", checkedAt = "2026-11-01T00:00:00Z", lat = 25.0, lon = 121.5)
        val b = a.copy(checkedAt = "2026-11-08T00:00:00Z")
        assertTrue(a.sameConclusion(b))
        assertFalse(a.sameConclusion(a.copy(distanceM = 130)))
        assertFalse(a.sameConclusion(a.copy(roadAxis = 90)))
    }

    @Test
    fun tgosAndMilepostThresholdsAndRoadAxisQuantization() {
        assertEquals(VerdictLevel.OK, LocationVerdict.forTgos(100.0))
        assertEquals(VerdictLevel.SUSPECT, LocationVerdict.forTgos(100.1))
        assertEquals(VerdictLevel.SUSPECT, LocationVerdict.forTgos(300.0))
        assertEquals(VerdictLevel.MISMATCH, LocationVerdict.forTgos(300.1))

        assertEquals(VerdictLevel.OK, LocationVerdict.forMilepost(200.0))
        assertEquals(VerdictLevel.SUSPECT, LocationVerdict.forMilepost(200.1))
        assertEquals(VerdictLevel.SUSPECT, LocationVerdict.forMilepost(1000.0))
        assertEquals(VerdictLevel.MISMATCH, LocationVerdict.forMilepost(1000.1))

        assertEquals(0, LocationVerdict.quantizeRoadAxis(356))
        assertEquals(0, LocationVerdict.quantizeRoadAxis(184))
        assertEquals(90, LocationVerdict.quantizeRoadAxis(268))
        assertEquals(170, LocationVerdict.quantizeRoadAxis(174))

        // Hysteresis keeps previous axis when bearing shifts by < 15°
        assertEquals(90, LocationVerdict.stabilizeRoadAxis(102, 90))
        assertEquals(110, LocationVerdict.stabilizeRoadAxis(108, 90))
    }

    @Test
    fun tgosCsvCleansAddressesConvertsTwd97AndParsesResults() {
        val cleaned = TgosCsv.cleanAddressForTgos("臺中市", "大里區", "大里區國光路一段72號前（北向）")
        assertEquals("臺中市大里區國光路一段72號", cleaned)

        val csv = TgosCsv.formatUploadCsv(listOf(TgosRow("npa-1", cleaned)))
        assertTrue(csv.contains("id,address"))
        assertTrue(csv.contains("npa-1,臺中市大里區國光路一段72號"))

        // Taipei 101 TWD97 TM2 (X=306969.38, Y=2769659.58) -> (25.033976, 121.564538)
        val wgs = TgosCsv.twd97ToWgs84(306969.38, 2769659.58)
        assertTrue(GeoUtils.haversine(wgs, LatLng(25.033976, 121.564538)) < 1.0)

        val resultCsv = """
            id,橫坐標,縱坐標,比對地址
            npa-1,306969.38,2769659.58,臺北市信義區市府路45號
            npa-2,120.680000,24.150000,臺中市北區三民路100號
        """.trimIndent()
        val parsed = TgosCsv.parseResults(resultCsv)
        assertEquals(2, parsed.size)
        assertEquals(25.033976, parsed.getValue("npa-1").pos.lat, 1e-4)
        assertEquals(24.150000, parsed.getValue("npa-2").pos.lat, 1e-5)
    }

    @Test
    fun milepostIndexParsesCsvAndInterpolatesLinearly() {
        val csv = """
            路線編號,里程,經度,緯度
            台14線,10K+000,120.7000,24.0000
            台14線,12K+000,120.7200,24.0000
            國道1號,50.0,121.2000,24.9500
            國道1號,52.0,121.2000,24.9700
        """.trimIndent()
        val index = MilepostIndex.fromCsv(csv)
        val p14 = index.interpolate("14", isFreeway = false, km = 11.0)!!
        assertEquals(24.0000, p14.lat, 1e-5)
        assertEquals(120.7100, p14.lon, 1e-5)

        val fw1 = index.interpolate("1", isFreeway = true, km = 51.5)!!
        assertEquals(24.9650, fw1.lat, 1e-5)
        assertEquals(121.2000, fw1.lon, 1e-5)

        // Gap exceeding maxBracketKm returns null
        assertNull(index.interpolate("14", isFreeway = false, km = 20.0))
    }
}

