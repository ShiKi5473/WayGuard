package com.wayguard.sync

import com.wayguard.core.dataset.DatasetBuilder
import com.wayguard.core.model.SpeedCamera
import com.wayguard.core.parse.NpaRow
import com.wayguard.core.parse.RejectedRow
import com.wayguard.core.validate.LocationCheck
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
}

