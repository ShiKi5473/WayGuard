package com.wayguard.core.dataset

import com.wayguard.core.dataset.CameraRoad.Intersection
import com.wayguard.core.dataset.CameraRoad.Known
import com.wayguard.core.dataset.CameraRoad.Unnamed
import com.wayguard.core.dataset.PlaceHeadingRules.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Address / direction texts as they appear in the NPA data. */
class CameraRoadTest {

    @Test
    fun directionTextNamingTheRoadWins() {
        assertEquals(Known("三民路"), CameraRoad.of("三民路三段與永安路口", "三民路上往龜山"))
        assertEquals(Known("西濱路"), CameraRoad.of("西濱路二段與民生路口", "西濱路二段上往觀音方向"))
        assertEquals(Known("復興路"), CameraRoad.of("復興路與民生路口", "復興路往市府方向"))
        assertEquals(Known("中山北路"), CameraRoad.of("中山北路二段與梅獅路一段口", "中山北路上往平鎮"))
    }

    @Test
    fun singleRoadAddresses() {
        assertEquals(Known("中山路"), CameraRoad.of("中山路941號前", "往市府方向"))
        assertEquals(Known("中正路"), CameraRoad.of("中正路二段239巷口", "往中壢方向"))
        assertEquals(Known("康莊路"), CameraRoad.of("康莊路三段632號對面（台4線34K+200處）", "往大溪方向"))
        assertEquals(Known("國光路"), CameraRoad.of("大里區國光路二段505號前", "往X"))
        assertEquals(Known("園區一路"), CameraRoad.of("園區一路", "往東"))
    }

    @Test
    fun intersectionsWithoutSayingWhichRoad() {
        assertEquals(Intersection(listOf("建德路", "長興路")), CameraRoad.of("建德路與長興路口", "往八德"))
        assertEquals(Intersection(listOf("大興西路", "正光路")), CameraRoad.of("大興西路三段與正光路口", "往永安路方向"))
        assertEquals(Intersection(listOf("中華路", "龍壽街")), CameraRoad.of("中華路龍壽街口署立桃園醫院前", "往桃園方向"))
        assertEquals(
            Intersection(listOf("中北路", "中山東路")),
            CameraRoad.of("中北路二段468號前(中山東路與中北路口)", "往中原大學方向"),
        )
    }

    @Test
    fun noStreetName() {
        assertEquals(Unnamed, CameraRoad.of("台七乙線8.8K處", "往大溪"))
        // 路段 = "road section", not a road called 崎頂下坡路.
        assertEquals(Unnamed, CameraRoad.of("崎頂下坡路段", "往大溪方向"))
        assertEquals(Unnamed, CameraRoad.of("中正路三坑下坡路段".removePrefix("中正路"), "往石門水庫"))
        assertEquals(Known("中正路"), CameraRoad.of("中正路三坑下坡路段", "往石門水庫"))
    }

    @Test
    fun baseAndLane() {
        assertEquals("中豐北路", CameraRoad.base("中豐北路二段"))
        assertEquals("中正路", CameraRoad.base("中正路二段239巷"))
        assertEquals("仰德大道", CameraRoad.base("仰德大道2段"))
        assertEquals(CameraRoad.base("台北路"), CameraRoad.base("臺北路"))
        assertTrue(CameraRoad.isLane("中正路二段239巷"))
        assertTrue(CameraRoad.isLane("龍山街426巷"))
        assertTrue(CameraRoad.isLane("枋坪巷"))
        assertTrue(CameraRoad.isLane("大巷街二段10巷"))
        assertFalse(CameraRoad.isLane("環中東路"))
        assertFalse(CameraRoad.isLane("大巷街"))
        assertFalse(CameraRoad.isLane("大巷街二段"))
    }

    @Test
    fun normalizesAndExtractsRoadLanesAndIgnoresNonLaneWords() {
        assertEquals("瑤鳳路二段416巷", CameraRoad.normalizeLane(" 瑤鳳路2段416巷 "))
        assertEquals("瑤鳳路二段416巷", CameraRoad.normalizeLane("瑤鳳路２段４１６巷"))
        assertEquals("臺灣大道十段50巷", CameraRoad.normalizeLane("台灣大道 10段 50巷"))
        assertEquals("臺灣大道二十一段3巷", CameraRoad.normalizeLane("台灣大道21段3巷"))
        assertEquals("臺中路12弄", CameraRoad.normalizeLane(" 台中路 12弄 "))

        assertEquals(mapOf("中華路" to "中華路一段2巷"), CameraRoad.roadLanesInAddress("中華路一段2巷137號"))
        assertEquals(mapOf("大莊路" to "大莊路60巷"), CameraRoad.roadLanesInAddress("岡山區大莊路60巷90號對面"))
        assertEquals(mapOf("光復路" to "光復路一段525巷"), CameraRoad.roadLanesInAddress("新竹市東區光復路一段525巷67號前"))
        assertEquals(mapOf("翠屏路" to "翠屏路112巷"), CameraRoad.roadLanesInAddress("大社區翠屏路112巷1-11號旁"))
        assertEquals(mapOf("東大路" to "東大路三段430巷"), CameraRoad.roadLanesInAddress("公道五路五段、東大路三段430巷口"))
        assertEquals(mapOf("喜樹路" to "喜樹路340巷"), CameraRoad.roadLanesInAddress("濱南路與喜樹路340巷口"))
        assertEquals(mapOf("光興路" to "光興路1343巷"), CameraRoad.roadLanesInAddress("太平區太堤東路與光興路1343巷口"))
        assertEquals(mapOf("瑤鳳路" to "瑤鳳路二段416巷"), CameraRoad.roadLanesInAddress("埔心鄉柳橋東路與瑤鳳路2段416巷口"))

        // 弄 alone, 巷 + 弄 chain, and named lanes
        assertEquals(mapOf("中正路" to "中正路二段239巷15弄"), CameraRoad.roadLanesInAddress("中正路二段239巷15弄8號"))
        assertEquals(mapOf("臺中路" to "臺中路12弄"), CameraRoad.roadLanesInAddress("台中路12弄"))
        assertEquals(mapOf("豐勢路" to "豐勢路九房巷"), CameraRoad.roadLanesInAddress("豐勢路九房巷口"))
        assertEquals(
            mapOf("和平西路" to "和平西路一段100巷"),
            CameraRoad.roadLanesInAddress("和平西路一段100巷5號", setOf("和平西路", "和平路")),
        )

        // Non-lane words containing 巷/弄 and lanes inside relative parens must not produce lane tokens
        assertEquals(emptyMap<String, String>(), CameraRoad.roadLanesInAddress("中正路巷口"))
        assertEquals(emptyMap<String, String>(), CameraRoad.roadLanesInAddress("中正路100號對面小巷"))
        assertEquals(emptyMap<String, String>(), CameraRoad.roadLanesInAddress("中正路無名巷口"))
        assertEquals(emptyMap<String, String>(), CameraRoad.roadLanesInAddress("復興路弄堂餐廳前"))
        assertEquals(emptyMap<String, String>(), CameraRoad.roadLanesInAddress("大巷街二段100號"))
        assertEquals(emptyMap<String, String>(), CameraRoad.roadLanesInAddress("太平區環中東路三段(育才路與樹孝路67巷間)"))
        assertEquals(mapOf("中正路" to "中正路3巷"), CameraRoad.roadLanesInAddress("中正路3巷弄口"))
    }

    // ---- rules ----

    private val town = Place("大溪", lat = 0.0, lon = 0.0)
    private fun check(road: String, bearing: Int = 0) = RoadCheck(bearing, road, 5, "t")

    @Test
    fun streetFoundMustBeTheCamerasRoad() {
        // 三民路上往龜山, but OSM returned the crossing 永安路 → not trusted.
        val o = PlaceHeadingRules.decide(town, 3000.0, 10, check("永安路"), Known("三民路"))
        assertEquals(Outcome.Skip(SkipReason.WRONG_ROAD), o)
        // The camera's own road, with 段 → trusted.
        assertTrue(PlaceHeadingRules.decide(town, 3000.0, 10, check("三民路三段"), Known("三民路")) is Outcome.Apply)
    }

    @Test
    fun lanesAndUnspecifiedIntersectionsAreNotTrusted() {
        assertEquals(
            Outcome.Skip(SkipReason.LANE),
            PlaceHeadingRules.decide(town, 3000.0, 10, check("中正路二段239巷"), Known("中正路")),
        )
        assertEquals(
            Outcome.Skip(SkipReason.INTERSECTION),
            PlaceHeadingRules.decide(town, 3000.0, 10, check("建德路"), Intersection(listOf("建德路", "長興路"))),
        )
        // Unnamed: nothing to compare, the bearing check alone decides.
        assertTrue(PlaceHeadingRules.decide(town, 3000.0, 10, check("台7乙線"), Unnamed) is Outcome.Apply)
    }

    @Test
    fun roadNamesStartingWithConjunctionCharactersStayIntact() {
        assertEquals(Known("和平西路"), CameraRoad.of("和平西路一段100號", "往東", setOf("和平西路", "和平路")))
        assertEquals(Intersection(listOf("和強路", "介壽路")), CameraRoad.of("桃園市八德區和強路與介壽路口", "往南", setOf("和強路")))
        assertEquals(Intersection(listOf("中正路", "仁愛路")), CameraRoad.of("中正路和仁愛路口", "往北"))
        assertEquals(
            Intersection(listOf("振興路", "西勢湖路")),
            CameraRoad.of("振興路1051之1號前(振興路與西勢湖路口)", "往西勢湖路方向", setOf("振興路", "西勢湖路")),
        )
    }

    @Test
    fun osmDictionaryPollutionAndRoadStartingWithLuStayClean() {
        val pollutedDict = setOf(
            "40", "101", "6-1", "臺中", "大坑", "公館", "番路", "3巷", "東明橋", "道路", "機車道",
            "蘆興南路", "中科路", "科雅東路", "斗苑路", "路平路",
        )
        assertEquals(Known("蘆興南路"), CameraRoad.of("蘆興南路408號旁", "往南崁", pollutedDict))
        assertEquals(
            Intersection(listOf("中科路", "科雅東路")),
            CameraRoad.of("臺中市西屯區中科路段近科雅東路口(快車道)", "往東", pollutedDict),
        )
        assertEquals("路平路", CameraRoad.base("路平路"))
        assertEquals(
            Intersection(listOf("斗苑路", "路平路")),
            CameraRoad.of("芳苑鄉斗苑路頂後段與路平路口", "往東", pollutedDict),
        )
        assertEquals(Unnamed, CameraRoad.of("東勢鄉153線與158甲線縣道路口", "往北", pollutedDict))
    }

    @Test
    fun knownRoadsIndexHandlesAvenuesOverlapsMutationsAndLargeSets() {
        val dict = linkedSetOf("凱達格蘭大道", "凱達格蘭大", "和平路", "和平西路一段", "市區路", "路平街", "大莊路60巷")
        assertEquals(Known("凱達格蘭大道"), CameraRoad.of("凱達格蘭大道1號前", "往東", dict))
        assertEquals(Known("和平西路"), CameraRoad.of("和平西路一段100號", "往東", dict))
        assertEquals(Intersection(listOf("市區路", "路平街")), CameraRoad.of("市區路路平街口", "往北", dict))
        assertEquals(mapOf("大莊路" to "大莊路60巷"), CameraRoad.roadLanesInAddress("大莊路60巷90號", dict))

        // Mutating a cached MutableSet invalidates the cached index cleanly.
        val mutable = linkedSetOf("中正路")
        assertEquals(Intersection(listOf("中正路", "強路")), CameraRoad.of("中正路和強路口", "往東", mutable))
        mutable.add("和強路")
        assertEquals(Intersection(listOf("中正路", "和強路")), CameraRoad.of("中正路和強路口", "往東", mutable))

        // Large dictionary (simulating full Taiwan OSM road names) stays fast across thousands of calls.
        val large = HashSet<String>(50_000)
        for (i in 0 until 45_000) {
            large += "測試第${i}路"
        }
        large += "和平西路"
        large += "凱達格蘭大道"
        val startNs = System.nanoTime()
        repeat(2_000) {
            assertEquals(Known("和平西路"), CameraRoad.of("和平西路一段100號", "往東", large))
            assertEquals(Known("凱達格蘭大道"), CameraRoad.of("凱達格蘭大道1號", "往北", large))
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        assertTrue("Expected < 2000ms for 4,000 lookups over 45k roads, took ${elapsedMs}ms", elapsedMs < 2_000)
    }
}
