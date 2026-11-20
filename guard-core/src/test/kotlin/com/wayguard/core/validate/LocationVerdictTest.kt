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

        val csv = TgosCsv.formatUploadCsv(listOf(TgosRow("npa-1", cleaned), TgosRow("npa-2", "臺中市北區三民路100號,前")))
        val lines = csv.lines().filter { it.isNotEmpty() }
        assertEquals("id,Address,Response_Address,Response_X,Response_Y", lines[0])
        assertEquals("npa-1,臺中市大里區國光路一段72號,,,", lines[1])
        assertEquals("npa-2,\"臺中市北區三民路100號,前\",,,", lines[2])
        // Every row carries exactly the five TGOS columns.
        assertTrue(lines.all { TgosCsv.splitCsvLine(it, ',').size == 5 })

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

        // TGOS batch result format: Response_* filled in, unmatched rows left empty.
        val batchCsv = """
            id,Address,Response_Address,Response_X,Response_Y
            npa-1,臺北市信義區市府路45號,臺北市信義區西村里市府路45號,306969.38,2769659.58
            npa-2,臺中市北區三民路100號,,,
            npa-3,澎湖縣馬公市中正路1號,澎湖縣馬公市啟明里中正路1號,119.566,23.566
        """.trimIndent()
        val batch = TgosCsv.parseResults(batchCsv)
        assertEquals(setOf("npa-1", "npa-3"), batch.keys)
        assertEquals(25.033976, batch.getValue("npa-1").pos.lat, 1e-4)
        assertEquals("臺北市信義區西村里市府路45號", batch.getValue("npa-1").matchedAddress)
        assertEquals(119.566, batch.getValue("npa-3").pos.lon, 1e-9)
    }

    @Test
    fun tgosAssessKeepsOnlyResultsPinnedToTheQueriedHouseNumber() {
        // Rows taken from a real TGOS fuzzy-matching batch result.
        val csv = """
            id,Address,Response_Address,Response_X,Response_Y
            a,新北市三重區環河北路3段166號,新北市三重區富貴里21鄰環河北路三段166號,121.493948,25.088166
            b,新北市鶯歌區西湖街191號,新北市鶯歌區中湖里27鄰西湖街191號之1,121.338615,24.964082
            c,臺中市大雅區民生路四段55號,臺中市大雅區上楓里19鄰民生路四段51號;臺中市大雅區上楓里19鄰民生路四段57號,120.668297;120.668364,24.236740;24.236788
            d,臺北市萬華區中華路1段406號,臺北市萬華區仁德里16鄰中華路一段218號,121.506311,25.037291
            e,臺中市西屯區環中路三段龍洋巷50-15號,臺中市西屯區龍潭里2鄰環中路三段770巷50之1號,120.627225,24.163722
            f,臺北市內湖區潭美街223號,臺北市內湖區週美里1鄰潭美街221號;臺北市內湖區石潭里12鄰潭美街285號,121.585372;121.587965,25.056158;25.057496
            g,臺中市霧峰區中正路569-9號,臺中市霧峰區坑口里31鄰中正路569之9號,120.693273,24.048494
            h,基隆市中正區臺62甲線3號,基隆市中正區正砂里10鄰東海街3號;基隆市中正區正義里7鄰信五路3號,121.760642;121.748123,25.142896;25.133585
            i,臺北市文山區木柵路5段近100之1號,臺北市文山區博嘉里1鄰木柵路五段100之1號,121.591446,24.999579
            j,新北市石碇區106線靜安路1段282號,找不到指定的門牌地址。,,
        """.trimIndent()
        val hits = TgosCsv.parseResults(csv)
        assertEquals(9, hits.size)
        assertEquals(2, hits.getValue("c").candidates.size)
        assertEquals(24.236764, hits.getValue("c").pos.lat, 1e-6)

        fun quality(id: String, city: String, district: String, address: String) =
            TgosCsv.assess(hits.getValue(id), city, district, address)?.quality

        assertEquals(TgosMatchQuality.EXACT, quality("a", "新北市", "三重區", "新北市三重區環河北路3段166號"))
        assertEquals(TgosMatchQuality.SAME_BASE, quality("b", "新北市", "鶯歌區", "新北市鶯歌區西湖街191號"))
        assertEquals(TgosMatchQuality.BRACKETED, quality("c", "臺中市", "大雅區", "臺中市大雅區民生路四段55號"))
        // Fuzzy matching fell back to another number / lane / road: a different place.
        assertNull(quality("d", "臺北市", "萬華區", "臺北市萬華區中華路1段406號"))
        assertNull(quality("e", "臺中市", "西屯區", "臺中市西屯區環中路三段龍洋巷50-15號"))
        assertNull(quality("h", "基隆市", "中正區", "基隆市中正區臺62甲線3號"))
        // Bracketing numbers ~300 m apart are too vague.
        assertNull(quality("f", "臺北市", "內湖區", "臺北市內湖區潭美街223號"))
        assertEquals(TgosMatchQuality.EXACT, quality("g", "臺中市", "霧峰區", "臺中市霧峰區中正路569-9號"))
        assertEquals(TgosMatchQuality.EXACT, quality("i", "臺北市", "文山區", "臺北市文山區木柵路5段近100之1號"))

        // A result without matched addresses still verifies, but never pins a house.
        val generic = TgosCsv.parseResults("id,lon,lat\nk,121.23,24.96\n")
        val match = TgosCsv.assess(generic.getValue("k"), "桃園市", "中壢區", "桃園市中壢區中山路100號")!!
        assertEquals(TgosMatchQuality.UNKNOWN, match.quality)
        assertFalse(match.pinsHouse)
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

