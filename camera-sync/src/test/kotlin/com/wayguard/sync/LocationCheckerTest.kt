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
    fun subordinateLanesAreNotMergedIntoParentRoadIndex() {
        assertEquals("豐勢路", OsmExtract.indexName("豐勢路"))
        assertEquals("大興西路", OsmExtract.indexName("大興西路三段"))
        assertEquals("豐勢路九房巷", OsmExtract.indexName("豐勢路九房巷"))
        assertEquals("中正路二段239巷", OsmExtract.indexName("中正路二段239巷"))
        assertEquals("枋坪巷", OsmExtract.indexName("枋坪巷"))
        assertEquals("臺中路12弄", OsmExtract.indexName("台中路12弄"))

        // 豐勢路 at lat 24.2740; alley 豐勢路九房巷 ~11 m away at lat 24.2741; standalone 枋坪巷.
        val extract = OsmExtract.fromText(
            listOf(
                """{"type":"Feature","properties":{"highway":"primary","name":"豐勢路"},"geometry":{"type":"LineString","coordinates":[[120.780,24.2740],[120.790,24.2740]]}}""",
                """{"type":"Feature","properties":{"highway":"service","name":"豐勢路九房巷"},"geometry":{"type":"LineString","coordinates":[[120.780,24.2760],[120.790,24.2760]]}}""",
                """{"type":"Feature","properties":{"highway":"residential","name":"枋坪巷"},"geometry":{"type":"LineString","coordinates":[[120.785,24.270],[120.785,24.280]]}}""",
            ).joinToString("\n") { "\u001e$it" },
        )
        // Point sits on the alley (lat 24.2760), ~222 m from the real 豐勢路.
        val onAlley = LatLng(24.2760, 120.783)
        val dParent = extract.distanceToRoad(onAlley, "豐勢路")!!
        assertTrue("alley must not count as 豐勢路, got $dParent", dParent > 200.0)
        assertTrue(extract.distanceToRoad(onAlley, "豐勢路九房巷")!! < 1.0)
        assertTrue(extract.distanceToRoad(LatLng(24.275, 120.785), "枋坪巷")!! < 1.0)
        assertTrue("豐勢路九房巷" in extract.knownRoadNames)
        assertTrue("枋坪巷" in extract.knownRoadNames)
    }

    @Test
    fun laneAwareLookupFixesLaneAddressesIntersectionsAndRoadAxisWhileKeepingMainRoadsStrict() {
        assertEquals("瑤鳳路二段416巷", OsmExtract.indexName("瑤鳳路2段416巷"))
        assertEquals("瑤鳳路二段416巷", OsmExtract.indexName("瑤鳳路２段４１６巷"))
        assertEquals("臺灣大道十段50巷", OsmExtract.indexName("台灣大道 10段 50巷"))
        assertEquals("大巷街", OsmExtract.indexName("大巷街二段"))
        assertEquals("大巷街二段10巷", OsmExtract.indexName("大巷街二段10巷"))

        // Synthetic extract in 桃園市 / 中壢區:
        // - 中華路 runs N-S (bearing 0°) at lon 121.2200
        // - 中華路一段2巷 runs E-W (bearing 90°) at lat 24.9600, lon 121.222..121.228 (~200-800m east of 中華路)
        // - 柳橋東路 runs N-S at lon 121.2350, crossing 瑤鳳路二段416巷 (E-W at lat 24.9620)
        // - 大莊路 runs E-W at lat 24.9500, with sibling lane 大莊路58巷 at lat 24.9510 (大莊路60巷 itself unnamed here)
        // - An unrelated 高雄市大莊路60巷 far south (22.62, 120.30) must not block city-scoped fallback in 桃園市.
        val laneExtract = OsmExtract.fromText(
            listOf(
                """{"type":"Feature","properties":{"boundary":"administrative","admin_level":"4","name":"桃園市"},"geometry":{"type":"Polygon","coordinates":[[[121.19,24.93],[121.31,24.93],[121.31,24.99],[121.19,24.99],[121.19,24.93]]]}}""",
                """{"type":"Feature","properties":{"boundary":"administrative","admin_level":"7","name":"中壢區"},"geometry":{"type":"Polygon","coordinates":[[[121.20,24.94],[121.26,24.94],[121.26,24.98],[121.20,24.98],[121.20,24.94]]]}}""",
                """{"type":"Feature","properties":{"highway":"primary","name":"中華路"},"geometry":{"type":"LineString","coordinates":[[121.2200,24.955],[121.2200,24.965]]}}""",
                """{"type":"Feature","properties":{"highway":"residential","name":"中華路一段2巷"},"geometry":{"type":"LineString","coordinates":[[121.22005,24.9600],[121.2280,24.9600]]}}""",
                """{"type":"Feature","properties":{"highway":"secondary","name":"柳橋東路"},"geometry":{"type":"LineString","coordinates":[[121.2350,24.958],[121.2350,24.966]]}}""",
                """{"type":"Feature","properties":{"highway":"residential","name":"瑤鳳路二段416巷"},"geometry":{"type":"LineString","coordinates":[[121.2300,24.9620],[121.2400,24.9620]]}}""",
                """{"type":"Feature","properties":{"highway":"secondary","name":"公道五路"},"geometry":{"type":"LineString","coordinates":[[121.2420,24.958],[121.2420,24.966]]}}""",
                """{"type":"Feature","properties":{"highway":"residential","name":"東大路3段430巷"},"geometry":{"type":"LineString","coordinates":[[121.2400,24.9640],[121.2450,24.9640]]}}""",
                """{"type":"Feature","properties":{"highway":"secondary","name":"大莊路"},"geometry":{"type":"LineString","coordinates":[[121.2150,24.9500],[121.2250,24.9500]]}}""",
                """{"type":"Feature","properties":{"highway":"residential","name":"大莊路58巷"},"geometry":{"type":"LineString","coordinates":[[121.2150,24.9510],[121.2250,24.9510]]}}""",
                """{"type":"Feature","properties":{"highway":"residential","name":"大莊路60巷"},"geometry":{"type":"LineString","coordinates":[[120.3000,22.6200],[120.3100,22.6200]]}}""",
            ).joinToString("\n") { "\u001e$it" },
        )

        // Direct OsmExtract lane-aware API checks
        assertTrue(laneExtract.distanceToRoad(LatLng(24.9600, 121.2260), "中華路", laneName = "中華路一段2巷")!! < 1.0)
        assertTrue(laneExtract.distanceToRoad(LatLng(24.9640, 121.2430), "東大路", laneName = "東大路三段430巷")!! < 1.0)
        assertTrue(laneExtract.hasRoad("中華路", laneName = "中華路一段2巷"))
        assertTrue(laneExtract.hasRoad("大莊路", laneName = "大莊路999巷")) // falls back to parent/sibling

        // 1. Lane camera sitting on its named lane (~600 m from parent 中華路) -> OK, roadAxis from lane (90°).
        val onNamedLane = SpeedCamera("cam-lane-ok", 24.9600, 121.2260, city = "桃園市", district = "中壢區", address = "中華路一段2巷137號")
        // 2. Named lane exists in city, but camera sits on parent 中華路 ~200m from lane start? Use 24.9560 (440m south on 中華路).
        val offNamedLane = SpeedCamera("cam-lane-bad", 24.9560, 121.2200, city = "桃園市", district = "中壢區", address = "中華路一段2巷137號")
        // 3. Main-road address sitting on the alley ~600m from 中華路 -> still NOT masked by alley -> MISMATCH.
        val mainOnAlley = SpeedCamera("cam-main-on-alley", 24.9600, 121.2260, city = "桃園市", district = "中壢區", address = "中華路100號")
        // 4. Main-road camera sitting on 中華路 5 m from perpendicular 中華路一段2巷 -> roadAxis comes from 中華路 (0°), NOT 90°.
        val mainNearAlley = SpeedCamera("cam-main-axis", 24.9600, 121.2200, city = "桃園市", district = "中壢區", address = "中華路100號")
        // 5. Unnamed lane (大莊路60巷 absent in 桃園市, though present in 高雄市) near sibling 大莊路58巷 -> fallback OK.
        val unnamedLane = SpeedCamera("cam-lane-fallback", 24.9510, 121.2200, city = "桃園市", district = "中壢區", address = "大莊路60巷90號對面")
        // 6. ROAD_ONLY lane-mouth camera on main road ~44m upstream of 中華路一段2巷 (and another right at the junction) -> OK + main roadAxis (0°).
        val roadOnlyUpstream = SpeedCamera("cam-lane-mouth-upstream", 24.9596, 121.2200, city = "桃園市", district = "中壢區", address = "中華路一段2巷口")
        val roadOnlyJunction = SpeedCamera("cam-lane-mouth-junction", 24.9600, 121.22004, city = "桃園市", district = "中壢區", address = "中華路一段2巷口")
        // 7. Intersection with a lane using 2段↔二段 (and OSM Arabic 3段 ↔ address 三段) normalization.
        val interLaneOk = SpeedCamera("cam-inter-ok", 24.9620, 121.2350, city = "桃園市", district = "中壢區", address = "柳橋東路與瑤鳳路2段416巷口")
        val interLaneRevOk = SpeedCamera("cam-inter-rev-ok", 24.9640, 121.2420, city = "桃園市", district = "中壢區", address = "公道五路五段、東大路三段430巷口")
        val interLaneBad = SpeedCamera("cam-inter-bad", 24.9550, 121.2350, city = "桃園市", district = "中壢區", address = "柳橋東路與瑤鳳路2段416巷口")

        val checker = LocationChecker(laneExtract, offline = true)
        val res = checker.checkAll(
            cameras = listOf(
                onNamedLane, offNamedLane, mainOnAlley, mainNearAlley, unnamedLane,
                roadOnlyUpstream, roadOnlyJunction, interLaneOk, interLaneRevOk, interLaneBad,
            ),
            rejected = emptyList(),
            rowsById = emptyMap(),
            existing = emptyMap(),
            now = "2026-11-16T00:00:00Z",
            verifyAll = true,
        )

        val namedChk = res.checks.getValue("cam-lane-ok")
        assertEquals(VerdictLevel.OK, namedChk.verdict)
        assertEquals("road", namedChk.method)
        assertEquals(90, namedChk.roadAxis)

        assertEquals(VerdictLevel.MISMATCH, res.checks.getValue("cam-lane-bad").verdict)
        assertEquals(VerdictLevel.MISMATCH, res.checks.getValue("cam-main-on-alley").verdict)
        val mainAxisChk = res.checks.getValue("cam-main-axis")
        assertEquals(VerdictLevel.OK, mainAxisChk.verdict)
        assertEquals(0, mainAxisChk.roadAxis)
        assertEquals(VerdictLevel.OK, res.checks.getValue("cam-lane-fallback").verdict)
        val mouthUpstreamChk = res.checks.getValue("cam-lane-mouth-upstream")
        assertEquals(VerdictLevel.OK, mouthUpstreamChk.verdict)
        assertEquals(0, mouthUpstreamChk.roadAxis)
        val mouthJunctionChk = res.checks.getValue("cam-lane-mouth-junction")
        assertEquals(VerdictLevel.OK, mouthJunctionChk.verdict)
        assertEquals(0, mouthJunctionChk.roadAxis)
        assertEquals(VerdictLevel.OK, res.checks.getValue("cam-inter-ok").verdict)
        assertEquals(VerdictLevel.OK, res.checks.getValue("cam-inter-rev-ok").verdict)

        val interBadChk = res.checks.getValue("cam-inter-bad")
        assertEquals(VerdictLevel.MISMATCH, interBadChk.verdict)
        assertEquals(24.9620, interBadChk.suggestedLat!!, 1e-4)
        assertEquals(121.2350, interBadChk.suggestedLon!!, 1e-4)
    }

    @Test
    fun laneIntersectionNeverFallsBackToParentRoad() {
        // Regression for npa-d58d814a44 (臺南市安南區「臺江大道三段近安中路320巷口」):
        // - 臺江大道 runs E-W at lat 23.0617; the camera sits on it at lon 120.1700
        // - 安中路五段 crosses 臺江大道 2.67 km west at lon 120.1441 (the wrong auto-fix target)
        // - 安中路四段320巷129弄 lies ~400 m north of the camera and never meets 臺江大道
        val base = listOf(
            """{"type":"Feature","properties":{"boundary":"administrative","admin_level":"4","name":"臺南市"},"geometry":{"type":"Polygon","coordinates":[[[120.10,22.95],[120.25,22.95],[120.25,23.15],[120.10,23.15],[120.10,22.95]]]}}""",
            """{"type":"Feature","properties":{"boundary":"administrative","admin_level":"7","name":"安南區"},"geometry":{"type":"Polygon","coordinates":[[[120.10,23.00],[120.22,23.00],[120.22,23.12],[120.10,23.12],[120.10,23.00]]]}}""",
            """{"type":"Feature","properties":{"highway":"primary","name":"臺江大道三段"},"geometry":{"type":"LineString","coordinates":[[120.1400,23.0617],[120.1800,23.0617]]}}""",
            """{"type":"Feature","properties":{"highway":"secondary","name":"安中路五段"},"geometry":{"type":"LineString","coordinates":[[120.1441,23.0500],[120.1441,23.0750]]}}""",
            """{"type":"Feature","properties":{"highway":"residential","name":"安中路四段320巷129弄"},"geometry":{"type":"LineString","coordinates":[[120.1680,23.0653],[120.1720,23.0653]]}}""",
        )
        val noLane = OsmExtract.fromText(base.joinToString("\n") { "\u001e$it" })
        // 1. Lane absent in OSM: no fallback to 安中路 trunk, and the 129弄 sub-alley does not count.
        assertTrue(noLane.findIntersectionCandidates("臺江大道", "安中路", "臺南市", "安南區", null, "安中路320巷").isEmpty())

        val cam = SpeedCamera(
            "npa-d58d814a44", 23.06164, 120.17002, city = "臺南市", district = "安南區",
            address = "臺江大道三段近安中路320巷口", directionText = "雙向",
        )
        val chk = LocationChecker(noLane, offline = true).checkAll(
            cameras = listOf(cam),
            rejected = emptyList(),
            rowsById = emptyMap(),
            existing = emptyMap(),
            now = "2026-11-17T00:00:00Z",
            verifyAll = true,
        ).checks.getValue("npa-d58d814a44")
        assertEquals(VerdictLevel.MISMATCH, chk.verdict)
        assertNull(chk.suggestedLat)
        assertNull(chk.suggestedSource)

        // 2. Section omitted in the address but present in OSM (安中路320巷 ↔ 安中路四段320巷) -> matched.
        val withLane = OsmExtract.fromText(
            (base + """{"type":"Feature","properties":{"highway":"residential","name":"安中路四段320巷"},"geometry":{"type":"LineString","coordinates":[[120.1700,23.0617],[120.1700,23.0700]]}}""")
                .joinToString("\n") { "\u001e$it" },
        )
        val hits = withLane.findIntersectionCandidates("臺江大道", "安中路", "臺南市", "安南區", null, "安中路320巷")
        assertEquals(1, hits.size)
        assertEquals(23.0617, hits.single().lat, 1e-4)
        assertEquals(120.1700, hits.single().lon, 1e-4)
        // 3. A lane that already names a different section gets no relaxation.
        assertTrue(withLane.findIntersectionCandidates("臺江大道", "安中路", "臺南市", "安南區", null, "安中路三段320巷").isEmpty())
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
        val mpIndex = MilepostIndex.fromCsv("路線編號,里程,lon,lat\n國道1號,50.0,121.210,24.970\n國道1號,52.0,121.230,24.970\n")
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

    @Test
    fun mismatchIntersectionShifts50mUpstreamAndMismatchMilepostSuggestsOnRoutePoint() {
        // Directional intersection MISMATCH (heading = 90° eastbound) shifts 50 m west (270°) from (24.960, 121.220).
        val dirBadInter = SpeedCamera(
            "cam-dir-inter",
            24.951,
            121.220,
            headings = listOf(90),
            city = "桃園市",
            district = "中壢區",
            address = "中山路與中正路口",
        )
        // Freeway MISMATCH (>1 km from interpolated milepost at 24.970, 121.220) -> suggests milepost pos.
        val mpIndex = MilepostIndex.fromCsv("路線編號,里程,lon,lat\n國道1號,50.0,121.210,24.970\n國道1號,52.0,121.230,24.970\n")
        val badFw = SpeedCamera(
            "cam-fw-bad",
            24.950,
            121.220,
            city = "國道一號",
            address = "國道一號北向51公里",
        )
        val res = LocationChecker(osm, offline = true, mileposts = mpIndex).checkAll(
            cameras = listOf(dirBadInter, badFw),
            rejected = emptyList(),
            rowsById = emptyMap(),
            existing = emptyMap(),
            now = "2026-11-16T00:00:00Z",
            verifyAll = true,
        )

        val interChk = res.checks.getValue("cam-dir-inter")
        assertEquals(VerdictLevel.MISMATCH, interChk.verdict)
        assertEquals("intersection", interChk.suggestedSource)
        val expectedShifted = GeoUtils.destinationPoint(LatLng(24.960, 121.220), 50.0, 270.0)
        assertEquals(expectedShifted.lat, interChk.suggestedLat!!, 1e-4)
        assertEquals(expectedShifted.lon, interChk.suggestedLon!!, 1e-4)

        val fwChk = res.checks.getValue("cam-fw-bad")
        assertEquals(VerdictLevel.MISMATCH, fwChk.verdict)
        assertEquals("milepost", fwChk.suggestedSource)
        assertEquals(24.970, fwChk.suggestedLat!!, 1e-4)
        assertEquals(121.220, fwChk.suggestedLon!!, 1e-4)
    }

    @Test
    fun tgosHouseNumberFixesOffRoadCoordinatesRescuesRejectedRowsAndSurvivesRunsWithoutTgos() {
        // ~1.1 km south of 中山路 (lat 24.960): OSM says the coordinate is off its road.
        val offRoad = SpeedCamera("cam-off", 24.950, 121.221, city = "桃園市", district = "中壢區", address = "中山路100號前")
        // On 中山路 but ~800 m from its house number: flagged, coordinate kept.
        val onRoad = SpeedCamera("cam-on", 24.960, 121.216, city = "桃園市", district = "中壢區", address = "中山路200號前")
        // Fuzzy result on another number: ignored.
        val wrongNo = SpeedCamera("cam-wrong", 24.960, 121.218, city = "桃園市", district = "中壢區", address = "中山路300號前")
        val rejRow = NpaRow(9, "桃園市", "中壢區", "中山路120號前", "局", "分", "120.40", "24.05", "往東", "50")
        val rejId = DatasetBuilder.stableId(rejRow)
        val rejected = listOf(RejectedRow(9, "經緯度不在桃園市範圍內", "raw", rejId))
        val tgosHits = TgosCsv.parseResults(
            """
            id,Address,Response_Address,Response_X,Response_Y
            cam-off,桃園市中壢區中山路100號,桃園市中壢區中山里5鄰中山路100號,121.2212,24.9603
            cam-on,桃園市中壢區中山路200號,桃園市中壢區中山里5鄰中山路200號,121.2240,24.9602
            cam-wrong,桃園市中壢區中山路300號,桃園市中壢區中山里5鄰中山路58號,121.2240,24.9602
            $rejId,桃園市中壢區中山路120號,桃園市中壢區中山里5鄰中山路120號,121.2222,24.9603
            """.trimIndent(),
        )
        val cams = listOf(offRoad, onRoad, wrongNo)
        val rowsById = mapOf(rejId to rejRow)
        val res = LocationChecker(osm, offline = true, tgosResults = tgosHits)
            .checkAll(cams, rejected, rowsById, emptyMap(), "2026-11-20T00:00:00Z", verifyAll = true)

        val offChk = res.checks.getValue("cam-off")
        assertEquals(VerdictLevel.MISMATCH, offChk.verdict)
        assertEquals("tgos", offChk.suggestedSource)
        // Projected onto 中山路 in front of the house, not the raw TGOS point.
        assertEquals(24.960, offChk.suggestedLat!!, 1e-5)
        assertEquals(121.2212, offChk.suggestedLon!!, 1e-5)

        val onChk = res.checks.getValue("cam-on")
        assertEquals(VerdictLevel.MISMATCH, onChk.verdict)
        assertEquals("tgos", onChk.method)
        assertNull(onChk.suggestedLat)

        val wrongChk = res.checks.getValue("cam-wrong")
        assertEquals("road", wrongChk.method)
        assertEquals(VerdictLevel.OK, wrongChk.verdict)

        val rejChk = res.checks.getValue(rejId)
        assertEquals("tgos", rejChk.suggestedSource)
        assertEquals(24.960, rejChk.suggestedLat!!, 1e-5)
        assertEquals(121.2222, rejChk.suggestedLon!!, 1e-5)

        // A later run without TGOS (CI) keeps the TGOS conclusions instead of dropping them.
        val rerun = LocationChecker(osm, offline = true)
            .checkAll(cams, rejected, rowsById, res.checks, "2026-11-27T00:00:00Z", verifyAll = true)
        assertEquals(offChk, rerun.checks.getValue("cam-off"))
        assertEquals(rejChk, rerun.checks.getValue(rejId))

        // Neither is exported for another TGOS upload.
        val upload = LocationChecker.buildTgosUploadRows(cams, rejected, rowsById, res.checks)
        assertEquals(listOf("cam-wrong"), upload.map { it.id })
    }
}

