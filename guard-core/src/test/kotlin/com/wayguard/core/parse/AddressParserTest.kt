package com.wayguard.core.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressParserTest {

    @Test
    fun classifiesSixAddressKinds() {
        val inter = AddressParser.parse("建德路與長興路口", "桃園市")
        assertEquals(AddressKind.INTERSECTION, inter.kind)
        assertEquals(listOf("建德路", "長興路"), inter.roads)

        val prov = AddressParser.parse("臺14線70.7公里處", "南投縣")
        assertEquals(AddressKind.PROVINCIAL_KM, prov.kind)
        assertEquals("14", prov.routeRef)
        assertFalse(prov.isFreeway)
        assertEquals(70.7, prov.km!!, 1e-6)

        val house = AddressParser.parse("華陽路850號", "彰化縣")
        assertEquals(AddressKind.HOUSE_NUMBER, house.kind)
        assertEquals(listOf("華陽路"), house.roads)
        assertEquals("850", house.houseNumber)

        val roadOnly = AddressParser.parse("明德三路(長春貨櫃)", "基隆市")
        assertEquals(AddressKind.ROAD_ONLY, roadOnly.kind)
        assertEquals(listOf("明德三路"), roadOnly.roads)
        assertNull(roadOnly.houseNumber)

        val fw = AddressParser.parse("國道一號北向105.5公里", "國道一號")
        assertEquals(AddressKind.FREEWAY_KM, fw.kind)
        assertEquals("1", fw.routeRef)
        assertTrue(fw.isFreeway)
        assertEquals(105.5, fw.km!!, 1e-6)

        val other = AddressParser.parse("高屏大橋屏東端", "屏東縣")
        assertEquals(AddressKind.OTHER, other.kind)
        assertTrue(other.roads.isEmpty())
    }

    @Test
    fun parsesBranchRoutesFullWidthAndCountyRoads() {
        val branch = AddressParser.parse("台7乙線8.8K處", "桃園市")
        assertEquals(AddressKind.PROVINCIAL_KM, branch.kind)
        assertEquals("7乙", branch.routeRef)
        assertEquals(8.8, branch.km!!, 1e-6)

        val fullWidth = AddressParser.parse("196線11ｋ大洲段東向", "宜蘭縣")
        assertEquals(AddressKind.PROVINCIAL_KM, fullWidth.kind)
        assertEquals("196", fullWidth.routeRef)
        assertEquals(11.0, fullWidth.km!!, 1e-6)

        val plusMeters = AddressParser.parse("二崙鄉156甲縣道0k+700m處", "雲林縣")
        assertEquals(AddressKind.PROVINCIAL_KM, plusMeters.kind)
        assertEquals("156甲", plusMeters.routeRef)
        assertEquals(0.7, plusMeters.km!!, 1e-6)

        val fwyBranch = AddressParser.parse("國道三甲西向3.4公里", "國道3甲")
        assertEquals(AddressKind.FREEWAY_KM, fwyBranch.kind)
        assertEquals("3甲", fwyBranch.routeRef)
        assertTrue(fwyBranch.isFreeway)
        assertEquals(3.4, fwyBranch.km!!, 1e-6)

        val typoJi = AddressParser.parse("台2己線北向1.1公里", "臺2已線")
        assertEquals(AddressKind.PROVINCIAL_KM, typoJi.kind)
        assertEquals("2己", typoJi.routeRef)
        assertFalse(typoJi.isFreeway)
    }

    @Test
    fun canonicalRouteRefMatchesOsmTags() {
        assertEquals("1", AddressParser.canonicalRouteRef("1"))
        assertEquals("1", AddressParser.canonicalRouteRef("國道1號"))
        assertEquals("14", AddressParser.canonicalRouteRef("台14線"))
        assertEquals("7乙", AddressParser.canonicalRouteRef("台7乙線"))
        assertEquals("139", AddressParser.canonicalRouteRef("縣道139線"))
        assertEquals("3甲", AddressParser.canonicalRouteRef("國道三甲"))
    }

    @Test
    fun detectsHighwayPoliceRouteCities() {
        assertFalse(AddressParser.isRouteCity("臺北市"))
        assertFalse(AddressParser.isRouteCity("台中市"))
        assertFalse(AddressParser.isRouteCity(""))
        assertTrue(AddressParser.isRouteCity("國道一號"))
        assertTrue(AddressParser.isRouteCity("國道3甲"))
        assertTrue(AddressParser.isRouteCity("臺2已線"))
        assertTrue(AddressParser.isRouteCity("台61線"))
    }

    @Test
    fun preservesKnownRoadsWithConjunctionCharacters() {
        val parsed = AddressParser.parse("和平西路一段100號", "臺北市", knownRoads = setOf("和平西路"))
        assertEquals(AddressKind.HOUSE_NUMBER, parsed.kind)
        assertEquals(listOf("和平西路"), parsed.roads)
    }

    @Test
    fun stripsRouteAndKmPrefixesHandlesArabicDigitsAndFiltersGenericRoads() {
        val yi9 = AddressParser.parse("宜9線3k+100m中央路三段南下", "宜蘭縣")
        assertEquals(AddressKind.ROAD_ONLY, yi9.kind)
        assertEquals(listOf("中央路"), yi9.roads)

        val prov2e = AddressParser.parse("台2戊線12k+215m移山路北上", "宜蘭縣")
        assertEquals(listOf("移山路"), prov2e.roads)

        val ramp = AddressParser.parse("台68線東大路匝道口", "新竹市")
        assertEquals(AddressKind.ROAD_ONLY, ramp.kind)
        assertEquals(listOf("東大路"), ramp.roads)
        assertTrue(ramp.isRampOrSpan)

        val ramp62 = AddressParser.parse("台62線下明燈路3段匝道（往瑞芳方向）", "新北市")
        assertEquals(AddressKind.ROAD_ONLY, ramp62.kind)
        assertEquals(listOf("明燈路"), ramp62.roads)

        val prov17 = AddressParser.parse("臺17線152.6公里大潭寮路口", "臺南市")
        assertEquals(listOf("大潭寮路"), prov17.roads)

        val exp65 = AddressParser.parse("台65線快速公路7公里（往新莊）", "新北市")
        assertEquals(AddressKind.PROVINCIAL_KM, exp65.kind)
        assertEquals("65", exp65.routeRef)
        assertTrue(exp65.roads.isEmpty())

        val kh = AddressParser.parse("東亞南路、平和2路路口", "高雄市")
        assertEquals(AddressKind.INTERSECTION, kh.kind)
        assertEquals(listOf("東亞南路", "平和二路"), kh.roads)

        val fwRamp = AddressParser.parse("高速公路接金福路之高架聯絡道北上（離金福路出口500公尺處)", "高雄市")
        assertEquals(listOf("金福路"), fwRamp.roads)

        val levee = AddressParser.parse("八德街與堤外道路（往鶯歌方向）", "新北市")
        assertEquals(AddressKind.ROAD_ONLY, levee.kind)
        assertEquals(listOf("八德街"), levee.roads)

        val flood = AddressParser.parse("公道五路四段、防汛道路", "新竹市")
        assertEquals(AddressKind.ROAD_ONLY, flood.kind)
        assertEquals(listOf("公道五路"), flood.roads)

        val moto = AddressParser.parse("三商街高速公路口機慢車專用道往南415公尺處", "高雄市")
        assertEquals(AddressKind.ROAD_ONLY, moto.kind)
        assertEquals(listOf("三商街"), moto.roads)
        assertEquals(415.0, moto.offsetMeters!!, 1e-6)
    }

    @Test
    fun stripsRelativeParenthesesAndExtractsIntersectionOffset() {
        val span = AddressParser.parse("太平區環中東路三段(育才路與樹孝路67巷間)", "臺中市")
        assertEquals(AddressKind.ROAD_ONLY, span.kind)
        assertEquals(listOf("環中東路"), span.roads)

        val nanxing = AddressParser.parse("南星路段(沿海路以北700公尺處)", "高雄市")
        assertEquals(AddressKind.OTHER, nanxing.kind)
        assertTrue(nanxing.roads.isEmpty())

        val offset = AddressParser.parse("同盟三路與十全三路口往西220公尺處", "高雄市")
        assertEquals(AddressKind.INTERSECTION, offset.kind)
        assertEquals(listOf("同盟三路", "十全三路"), offset.roads)
        assertEquals(220.0, offset.offsetMeters!!, 1e-6)
    }
}

