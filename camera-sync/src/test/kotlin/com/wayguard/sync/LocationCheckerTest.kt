package com.wayguard.sync

import com.wayguard.core.dataset.DatasetBuilder
import com.wayguard.core.model.SpeedCamera
import com.wayguard.core.parse.NpaRow
import com.wayguard.core.parse.RejectedRow
import com.wayguard.core.validate.LocationCheck
import com.wayguard.core.validate.MilepostIndex
import com.wayguard.core.validate.TgosCsv
import com.wayguard.core.validate.VerdictLevel
import com.waymap.geo.GeoUtils
import com.waymap.geo.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationCheckerTest {

    /**
     * Minimal synthetic GeoJSONSeq extract in 桃園市 with:
     * - two districts: 中壢區 (lon 121.20..121.25) and 平鎮區 (lon 121.25..121.30), lat 24.94..24.98
     * - intersection 中山路 x 中正路 inside 中壢區 at (24.960, 121.220)
     * - a second 中山路 x 中正路 intersection inside 平鎮區 at (24.960, 121.270)
     * - Freeway 1 (highway=motorway, ref=1) at lat 24.970 and Provincial 1 (highway=primary, ref=台1線) at lat 24.950
     */
    private val sampleGeoJsonSeq = listOf(
        """{"type":"Feature","properties":{"boundary":"administrative","admin_level":"4","name":"桃園市"},"geometry":{"type":"Polygon","coordinates":[[[121.19,24.93],[121.31,24.93],[121.31,24.99],[121.19,24.99],[121.19,24.93]]]}}""",
        """{"type":"Feature","properties":{"boundary":"administrative","admin_level":"7","name":"中壢區"},"geometry":{"type":"Polygon","coordinates":[[[121.20,24.94],[121.25,24.94],[121.25,24.98],[121.20,24.98],[121.20,24.94]]]}}""",
        """{"type":"Feature","properties":{"boundary":"administrative","admin_level":"7","name":"平鎮區"},"geometry":{"type":"Polygon","coordinates":[[[121.25,24.94],[121.30,24.94],[121.30,24.98],[121.25,24.98],[121.25,24.94]]]}}""",
        // 中壢區 intersection at (24.960, 121.220)
        """{"type":"Feature","properties":{"highway":"primary","name":"中山路"},"geometry":{"type":"LineString","coordinates":[[121.215,24.960],[121.225,24.960]]}}""",
        """{"type":"Feature","properties":{"highway":"secondary","name":"中正路"},"geometry":{"type":"LineString","coordinates":[[121.220,24.955],[121.220,24.965]]}}""",
        // 平鎮區 duplicate-name intersection at (24.960, 121.270)
        """{"type":"Feature","properties":{"highway":"primary","name":"中山路"},"geometry":{"type":"LineString","coordinates":[[121.265,24.960],[121.275,24.960]]}}""",
        """{"type":"Feature","properties":{"highway":"secondary","name":"中正路"},"geometry":{"type":"LineString","coordinates":[[121.270,24.955],[121.270,24.965]]}}""",
        // Freeway 1 vs Provincial 1 sharing ref=1
        """{"type":"Feature","properties":{"highway":"motorway","ref":"1"},"geometry":{"type":"LineString","coordinates":[[121.210,24.970],[121.240,24.970]]}}""",
        """{"type":"Feature","properties":{"highway":"primary","ref":"台1線"},"geometry":{"type":"LineString","coordinates":[[121.210,24.950],[121.240,24.950]]}}""",
    ).joinToString("\n") { "\u001e$it" }

    private val osm = OsmExtract.fromText(sampleGeoJsonSeq)

    @Test
    fun parsesNlscXmlAndJsonAndNormalizesTai() {
        val xml = "<townVillageItem><ctyName>台北市</ctyName><townName>中山區</townName></townVillageItem>"
        assertEquals(NlscClient.AdminResult("臺北市", "中山區"), NlscClient.parseResponse(xml))

        val json = """{"ctyName":"台中市","townName":"西屯區"}"""
        assertEquals(NlscClient.AdminResult("臺中市", "西屯區"), NlscClient.parseResponse(json))
        assertNull(NlscClient.parseResponse("<error/>"))
    }

    @Test
    fun distinguishesFreewayAndProvincialRefsWithSameNumber() {
        // Near Provincial 1 (lat 24.950), ~2.2 km from Freeway 1 (lat 24.970)
        val onProv = LatLng(24.950, 121.225)
        assertTrue(osm.distanceToRoute(onProv, "1", isFreeway = false)!! < 5.0)
        assertTrue(osm.distanceToRoute(onProv, "1", isFreeway = true)!! > 2000.0)
    }

    @Test
    fun disambiguatesDuplicateCountyIntersectionByDistrict() {
        val candidates = osm.findIntersectionCandidates("中山路", "中正路", "桃園市", "中壢區")
        assertEquals(1, candidates.size)
        val c = candidates.single()
        assertEquals(24.960, c.lat, 1e-4)
        assertEquals(121.220, c.lon, 1e-4)
    }

    @Test
    fun localRoadCheckerReplacesNominatimWhenOsmExtractIsLoaded() {
        val checker = RoadChecker(Nominatim("test"), osm)
        val checks = checker.check(listOf("cam-1" to LatLng(24.960, 121.217)), "2026-11-13T00:00:00Z")
        val rc = checks.getValue("cam-1")
        assertEquals("中山路", rc.road)
        assertEquals(90, rc.bearing)
        assertEquals(0, rc.distanceM)
    }

    @Test
    fun crossVerifiesCamerasGeneratesFixSuggestionsAndAvoidsTimestampChurn() {
        val goodCam = SpeedCamera("cam-ok", 24.960, 121.2201, city = "桃園市", district = "中壢區", address = "中山路與中正路口")
        // Misplaced 1 km south in 中壢區: should be MISMATCH with unique suggestion at (24.960, 121.220)
        val badCam = SpeedCamera("cam-bad", 24.951, 121.220, city = "桃園市", district = "中壢區", address = "中山路與中正路口")
        // Highway camera on Freeway 1
        val fwyCam = SpeedCamera("cam-fw", 24.970, 121.225, city = "國道一號", address = "國道一號北向60公里")
        // Rejected row with bad coords outside Taiwan, same intersection address in 中壢區
        val rejRow = NpaRow(9, "桃園市", "中壢區", "中山路與中正路口", "局", "分", "121.22", "99.99", "往北", "50")
        val rejId = DatasetBuilder.stableId(rejRow)
        val rejected = listOf(RejectedRow(9, "經緯度不在桃園市範圍內", "", rejId))

        val checker = LocationChecker(osm, nlsc = null, offline = true)
        val run1 = checker.checkAll(
            cameras = listOf(goodCam, badCam, fwyCam),
            rejected = rejected,
            rowsById = mapOf(rejId to rejRow),
            existing = emptyMap(),
            now = "2026-11-13T00:00:00Z",
            verifyAll = true,
        )
        assertTrue(run1.changed)
        assertEquals(VerdictLevel.OK, run1.checks.getValue("cam-ok").verdict)
        assertEquals(VerdictLevel.OK, run1.checks.getValue("cam-fw").verdict)

        val badCheck = run1.checks.getValue("cam-bad")
        assertEquals(VerdictLevel.MISMATCH, badCheck.verdict)
        val sLat = badCheck.suggestedLat
        val sLon = badCheck.suggestedLon
        assertNotNull(sLat)
        assertNotNull(sLon)
        assertEquals(24.960, sLat!!, 1e-4)
        assertEquals(121.220, sLon!!, 1e-4)
        assertTrue(GeoUtils.haversine(badCam.pos, LatLng(sLat, sLon)) > 900.0)

        val rejCheck = run1.checks.getValue(rejId)
        assertEquals(24.960, rejCheck.suggestedLat!!, 1e-4)

        val fixes = LocationChecker.buildFixSuggestions(
            issues = listOf(badCam to badCheck, goodCam to run1.checks.getValue("cam-ok")),
            rejectedSuggestions = mapOf(rejId to rejCheck),
        )
        assertEquals(setOf("cam-bad", rejId), fixes.keys)

        // Second run with newer timestamp must report changed = false when conclusions match
        val run2 = checker.checkAll(
            cameras = listOf(goodCam, badCam, fwyCam),
            rejected = rejected,
            rowsById = mapOf(rejId to rejRow),
            existing = run1.checks,
            now = "2026-11-20T00:00:00Z",
            verifyAll = true,
        )
        assertFalse(run2.changed)
    }

    @Test
    fun nlscSecondaryCheckOverridesOsmDistrictMismatchAndHandlesOffline() {
        // Point near (24.960, 121.220) is inside OSM 中壢區, while camera declares 平鎮區 (>200m from border)
        val cam = SpeedCamera("cam-dist", 24.960, 121.220, city = "桃園市", district = "中正區", address = "中山路")
        // Actually let's test OSM says 中壢區, camera declares 平鎮區, distance to border > 200m -> SUSPECT in offline mode
        val suspectCam = SpeedCamera("cam-dist", 24.960, 121.220, city = "桃園市", district = "平鎮區", address = "中山路")

        val offlineRes = LocationChecker(osm, nlsc = NlscClient(fetcher = { null }), offline = false)
            .checkAll(listOf(suspectCam), emptyList(), emptyMap(), emptyMap(), "t", verifyAll = true)
        val offlineChk = offlineRes.checks.getValue("cam-dist")
        assertEquals(VerdictLevel.SUSPECT, offlineChk.verdict)
        assertTrue(offlineChk.reason.contains("待複查"))

        // When NLSC confirms 平鎮區, final verdict becomes OK
        val nlscConfirm = NlscClient(fetcher = { "<townVillageItem><ctyName>桃園市</ctyName><townName>平鎮區</townName></townVillageItem>" }, minIntervalMs = 0)
        val onlineRes = LocationChecker(osm, nlsc = nlscConfirm, offline = false)
            .checkAll(listOf(suspectCam), emptyList(), emptyMap(), emptyMap(), "t", verifyAll = true)
        assertEquals(VerdictLevel.OK, onlineRes.checks.getValue("cam-dist").verdict)
    }

    @Test
    fun phase2OffsetSpanTgosMilepostAndRoadAxisWorkTogether() {
        // 1. Offset intersection (~220m east on 中山路 from 中正路口): tolerated by offsetMeters -> OK
        val offsetPos = GeoUtils.destinationPoint(LatLng(24.960, 121.220), 220.0, 90.0)
        val offsetCam = SpeedCamera("cam-offset", offsetPos.lat, offsetPos.lon, city = "桃園市", district = "中壢區", address = "中山路與中正路口往東220公尺處")

        // 2. House number with TGOS hit > 300m -> MISMATCH without exposing TGOS coords in suggestedLat/Lon
        val houseCam = SpeedCamera("cam-house", 24.960, 121.223, city = "桃園市", district = "中壢區", address = "中山路100號前")
        val pendingHouse = SpeedCamera("cam-pending", 24.960, 121.224, city = "桃園市", district = "中壢區", address = "中山路120號前")
        val tgosHits = TgosCsv.parseResults("id,lon,lat,address\ncam-house,121.230,24.960,桃園市中壢區中山路100號\n")

        // 3. Single-road camera within 25 m -> computes roadAxis = 90° (east-west 中山路)
        val mpIndex = MilepostIndex.fromCsv("路線,里程,lon,lat\n國道1號,50.0,121.210,24.970\n國道1號,52.0,121.230,24.970\n")
        val fwCam = SpeedCamera("cam-fw-mp", 24.970, 121.220, city = "國道一號", address = "國道一號北向51公里")

        // 4. Suspect intersection (120m from crossing) does NOT receive suggestedLat/Lon (reserved for MISMATCH only)
        val suspectPos = GeoUtils.destinationPoint(LatLng(24.960, 121.220), 120.0, 90.0)
        val suspectInter = SpeedCamera("cam-sus", suspectPos.lat, suspectPos.lon, city = "桃園市", district = "中壢區", address = "中山路與中正路口")

        val checker = LocationChecker(osm, offline = true, mileposts = mpIndex, tgosResults = tgosHits)
        val res = checker.checkAll(
            cameras = listOf(offsetCam, houseCam, pendingHouse, fwCam, suspectInter),
            rejected = emptyList(),
            rowsById = emptyMap(),
            existing = emptyMap(),
            now = "2026-11-13T00:00:00Z",
            verifyAll = true,
        )

        assertEquals(VerdictLevel.OK, res.checks.getValue("cam-offset").verdict)
        val houseChk = res.checks.getValue("cam-house")
        assertEquals(VerdictLevel.MISMATCH, houseChk.verdict)
        assertEquals("tgos", houseChk.method)
        assertNull(houseChk.suggestedLat)
        assertEquals(90, houseChk.roadAxis)
        assertEquals(90, res.checks.getValue("cam-pending").roadAxis)

        assertEquals(VerdictLevel.OK, res.checks.getValue("cam-fw-mp").verdict)

        val susChk = res.checks.getValue("cam-sus")
        assertEquals(VerdictLevel.SUSPECT, susChk.verdict)
        assertNull(susChk.suggestedLat)

        // Upload CSV skips cam-house (already has method=tgos) and includes cam-pending
        val upload = LocationChecker.buildTgosUploadRows(
            listOf(offsetCam, houseCam, pendingHouse, fwCam, suspectInter),
            emptyList(),
            emptyMap(),
            res.checks,
        )
        assertEquals(listOf("cam-pending"), upload.map { it.id })
    }

    @Test
    fun tgosClientParsesXmlWrappedJsonAndVerifiesHouseNumberCamerasOnline() {
        val xmlWrapped = """
            <?xml version="1.0" encoding="utf-8"?>
            <string xmlns="http://tempuri.org/">{&quot;AddressList&quot;:[{&quot;FULL_ADDR&quot;:&quot;桃園市中壢區中山路120號&quot;,&quot;X&quot;:121.230,&quot;Y&quot;:24.960}]}</string>
        """.trimIndent()
        val parsed = TgosClient.parseQueryAddrResponse("cam-1", xmlWrapped)
        assertNotNull(parsed)
        assertEquals(24.960, parsed!!.pos.lat, 1e-6)
        assertEquals(121.230, parsed.pos.lon, 1e-6)
        assertEquals("桃園市中壢區中山路120號", parsed.matchedAddress)

        var calls = 0
        val fakeTgos = TgosClient(
            appId = "test-app",
            apiKey = "test-key",
            minIntervalMs = 0L,
            fetcher = { _, form ->
                calls++
                assertTrue(form.contains("oAPPId=test-app"))
                assertTrue(form.contains("oSRS=EPSG%3A4326"))
                """{"AddressList":[{"FULL_ADDR":"桃園市中壢區中山路120號","X":"121.224","Y":"24.960"}]}"""
            },
        )
        val cam = SpeedCamera("cam-120", 24.960, 121.2242, city = "桃園市", district = "中壢區", address = "中山路120號前")
        val onlineRes = LocationChecker(osm, offline = false, tgos = fakeTgos)
            .checkAll(listOf(cam), emptyList(), emptyMap(), emptyMap(), "2026-11-13T00:00:00Z", verifyAll = true)
        val chk = onlineRes.checks.getValue("cam-120")
        assertEquals(VerdictLevel.OK, chk.verdict)
        assertEquals("tgos", chk.method)
        assertNull(chk.suggestedLat)
        assertEquals(1, calls)

        // Incremental second run skips re-querying TGOS once prev.method == "tgos" and verdict == OK
        LocationChecker(osm, offline = false, tgos = fakeTgos)
            .checkAll(listOf(cam), emptyList(), emptyMap(), onlineRes.checks, "2026-11-20T00:00:00Z", verifyAll = false)
        assertEquals(1, calls)
    }
}

