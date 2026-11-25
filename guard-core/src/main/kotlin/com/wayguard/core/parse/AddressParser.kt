package com.wayguard.core.parse

import com.wayguard.core.dataset.CameraRoad
import com.wayguard.core.validate.CountyBounds

/** High-level classification of an NPA camera address for location cross-verification. */
enum class AddressKind {
    /** Names two or more intersecting roads: `建德路與長興路口`, `中華路龍壽街口`. */
    INTERSECTION,

    /** Provincial, county, or municipal route with or without milepost: `臺14線70.7公里處`, `139線47.5公里處`. */
    PROVINCIAL_KM,

    /** Single named road with a house/building number: `華陽路850號`, `中山路1段524號`. */
    HOUSE_NUMBER,

    /** Single named road without a house number: `明德三路(長春貨櫃)`, `光明路七堵停車場前`. */
    ROAD_ONLY,

    /** National freeway with milepost: `國道一號北向105.5公里`, `國道三甲西向3.4公里`. */
    FREEWAY_KM,

    /** Unstructured landmark or tunnel/bridge name without a road or numbered route. */
    OTHER,
}

data class ParsedAddress(
    val kind: AddressKind,
    val roads: List<String> = emptyList(),
    val routeRef: String? = null,
    val isFreeway: Boolean = false,
    val km: Double? = null,
    val houseNumber: String? = null,
    val offsetMeters: Double? = null,
    val isRampOrSpan: Boolean = false,
    /** Normalized lane/alley token (`…巷` / `…弄`) per base road name when the address specifies one. */
    val roadLanes: Map<String, String> = emptyMap(),
)

/**
 * Parses published NPA addresses into structured components ([AddressKind], base road names,
 * canonical route ref, milepost km, house number) for cross-verification against OSM.
 */
object AddressParser {

    private val CN_DIGITS = mapOf(
        '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
        '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10,
    )

    private val FREEWAY_RE = Regex("國道\\s*([一二三四五六七八九十]+|\\d+)\\s*(?:號|([甲乙丙丁]))?")
    private val PROVINCIAL_NUM_RE = Regex("臺\\s*(\\d+)\\s*([甲乙丙丁己]?)\\s*(?:線|快速公路|快速道路)?")
    private val PROVINCIAL_CN_RE = Regex("臺\\s*([一二三四五六七八九十]+)\\s*([甲乙丙丁己]?)\\s*線")
    private val COUNTY_NUM_RE = Regex("(?:縣道|市道)?\\s*(\\d{2,3})\\s*([甲乙丙丁]?)\\s*(?:線|縣道|市道)")
    private val TOWNSHIP_NUM_RE = Regex("(?<![以往向東西南北])(?:澎|北|桃|竹|苗|中|投|彰|雲|嘉|南|高|屏|宜|花|東|金|連)\\s*(\\d{1,3})\\s*(?:號|線|鄉道|區道)")
    private val ROUTE_CITY_RE = Regex("^(?:國道|臺\\d|台\\d).*")

    private val KM_RE = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:[kK]|公里)(?:\\s*\\+\\s*(\\d+)\\s*[mM]?)?")
    private val HOUSE_RE = Regex("(\\d+(?:[之\\-]\\d+)?)\\s*號")
    private val OFFSET_RE = Regex("(?:往[東西南北]|以[東西南北]|離[^\\s,，()（）]+?)\\s*約?\\s*(\\d+(?:\\.\\d+)?)\\s*公尺")
    private val RAMP_OR_SPAN_RE = Regex("(?:匝道|交流道|系統交流道|至)")
    private val INTERSECTION_MARK_RE = Regex("[與和及、口/／&＆至間~～]|交叉|跨")

    fun parse(
        address: String,
        city: String = "",
        directionText: String = "",
        knownRoads: Set<String> = emptySet(),
    ): ParsedAddress {
        val norm = normalizeText(address)
        val normCity = normalizeText(city)

        val (addressRoads, addressRoadLanes) = CameraRoad.roadsAndLanesInAddress(address, knownRoads)
        val allRoads = if (addressRoads.isNotEmpty()) {
            addressRoads
        } else {
            listOfNotNull(CameraRoad.roadBeforeToward(directionText))
        }
        val allRoadLanes = if (addressRoads.isNotEmpty()) addressRoadLanes else emptyMap()

        val (routeRef, isFreeway) = extractRoute(norm) ?: extractRoute(normCity) ?: (null to false)
        val km = extractKm(norm)
        val house = if (routeRef == null || allRoads.isNotEmpty()) {
            HOUSE_RE.find(norm)?.groupValues?.get(1)
        } else {
            null
        }
        val offsetMeters = OFFSET_RE.find(norm)?.groupValues?.get(1)?.toDoubleOrNull()
        val isRampOrSpan = RAMP_OR_SPAN_RE.containsMatchIn(norm)
        // A bare `X路段` beside a house number on another road is that house, not an intersection.
        val stretchHouse = if (house != null) stretchHouseRoad(norm, allRoads) else null
        val roads = stretchHouse?.let { listOf(it) } ?: allRoads
        val roadLanes = if (stretchHouse != null) allRoadLanes.filterKeys { it == stretchHouse } else allRoadLanes

        val kind = when {
            roads.size >= 2 -> AddressKind.INTERSECTION
            isFreeway && roads.isEmpty() -> AddressKind.FREEWAY_KM
            routeRef != null && roads.isEmpty() -> AddressKind.PROVINCIAL_KM
            roads.size == 1 && house != null -> AddressKind.HOUSE_NUMBER
            roads.size == 1 -> AddressKind.ROAD_ONLY
            else -> AddressKind.OTHER
        }
        return ParsedAddress(
            kind = kind,
            roads = roads,
            routeRef = routeRef,
            isFreeway = isFreeway,
            km = km,
            houseNumber = house,
            offsetMeters = offsetMeters,
            isRampOrSpan = isRampOrSpan,
            roadLanes = roadLanes,
        )
    }

    /**
     * Highway-police rows carry the route name in `city` (`國道一號`, `臺2已線`) instead of
     * an administrative county/city. Admin-area comparison must be skipped for those rows.
     */
    fun isRouteCity(city: String): Boolean {
        val normalized = CountyBounds.normalizeCity(city)
        if (normalized.isEmpty() || CountyBounds.isKnown(normalized)) return false
        return ROUTE_CITY_RE.matches(normalizeText(normalized))
    }

    /**
     * Normalizes an OSM `ref` tag value into the same canonical format produced by [parse]
     * (e.g. `"台14線"` -> `"14"`, `"台7乙線"` -> `"7乙"`, `"國道1號"` -> `"1"`, `"縣道139線"` -> `"139"`).
     */
    fun canonicalRouteRef(rawRef: String): String? {
        val norm = normalizeText(rawRef).replace("號", "").replace("線", "")
            .replace("國道", "").replace("省道", "").replace("縣道", "").replace("市道", "")
            .replace("鄉道", "").replace("區道", "")
            .replace("臺", "").replace(" ", "")
            .replace(Regex("^(?:澎|北|桃|竹|苗|中|投|彰|雲|嘉|南|高|屏|宜|花|東|金|連)(?=\\d)"), "")
        if (norm.isEmpty()) return null
        val m = Regex("^([一二三四五六七八九十]+|\\d+)([甲乙丙丁己]?)$").find(norm) ?: return null
        val num = parseCnOrInt(m.groupValues[1]) ?: return null
        return "$num${m.groupValues[2]}"
    }

    /**
     * `旗楠路段〈鳳旗路44號之10旁〉`: the bare `旗楠路段` only names the stretch, while the house number on
     * 鳳旗路 pins the spot. Returns the road carrying the house number, or null whenever the address
     * could still be an intersection (any `與`/`口`/`至`… marker, or not exactly one stretch + one house road).
     */
    private fun stretchHouseRoad(norm: String, roads: List<String>): String? {
        if (roads.size != 2 || INTERSECTION_MARK_RE.containsMatchIn(norm)) return null
        val stretch = roads.filter { Regex(Regex.escape(it) + "段").containsMatchIn(norm) }
        val housed = roads.filter {
            Regex(Regex.escape(it) + "(?:[一二三四五六七八九十\\d]+段)?\\s*\\d+(?:[之\\-]\\d+)?\\s*號").containsMatchIn(norm)
        }
        if (stretch.size != 1 || housed.size != 1 || stretch == housed) return null
        return housed.single()
    }

    private fun extractRoute(text: String): Pair<String, Boolean>? {
        FREEWAY_RE.find(text)?.let { m ->
            val num = parseCnOrInt(m.groupValues[1]) ?: return null
            val branch = m.groupValues[2]
            return "$num$branch" to true
        }
        PROVINCIAL_NUM_RE.find(text)?.let { m ->
            return "${m.groupValues[1]}${m.groupValues[2]}" to false
        }
        PROVINCIAL_CN_RE.find(text)?.let { m ->
            val num = parseCnOrInt(m.groupValues[1]) ?: return null
            return "$num${m.groupValues[2]}" to false
        }
        COUNTY_NUM_RE.find(text)?.let { m ->
            return "${m.groupValues[1]}${m.groupValues[2]}" to false
        }
        TOWNSHIP_NUM_RE.find(text)?.let { m ->
            return m.groupValues[1] to false
        }
        return null
    }

    private fun extractKm(text: String): Double? {
        val m = KM_RE.find(text) ?: return null
        val baseKm = m.groupValues[1].toDoubleOrNull() ?: return null
        val plusMeters = m.groupValues[2].toDoubleOrNull() ?: 0.0
        return baseKm + plusMeters / 1000.0
    }

    private fun parseCnOrInt(token: String): String? {
        token.toIntOrNull()?.let { return it.toString() }
        if (token.isEmpty()) return null
        if (token.length == 1) return CN_DIGITS[token[0]]?.toString()
        if (token == "十") return "10"
        if (token.startsWith("十")) {
            val ones = CN_DIGITS[token[1]] ?: return null
            return (10 + ones).toString()
        }
        return null
    }

    /** Unifies full-width digits/letters and known NPA typos (`已` vs `己`). */
    private fun normalizeText(s: String): String = buildString(s.length) {
        for (ch in s.trim()) {
            append(
                when (ch) {
                    in '０'..'９' -> ('0' + (ch - '０'))
                    'Ｋ', 'ｋ' -> 'K'
                    'Ｍ', 'ｍ' -> 'M'
                    '．' -> '.'
                    '＋' -> '+'
                    '台' -> '臺'
                    '已' -> '己'
                    else -> ch
                }
            )
        }
    }
}

