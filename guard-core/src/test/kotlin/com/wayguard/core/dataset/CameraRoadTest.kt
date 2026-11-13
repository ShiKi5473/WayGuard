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
        assertFalse(CameraRoad.isLane("環中東路"))
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
}
