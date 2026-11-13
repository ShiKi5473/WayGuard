package com.wayguard.core.dataset

/**
 * Which road a camera stands on, as far as its published texts say. Used to make sure
 * the street found in OpenStreetMap is the camera's own road and not a crossing street.
 */
sealed interface CameraRoad {
    /** One road: `三民路上往龜山` → 三民路, `中山路941號前` → 中山路. */
    data class Known(val base: String) : CameraRoad
    /** An intersection without saying which of the roads: `建德路與長興路口`. */
    data class Intersection(val bases: List<String>) : CameraRoad
    /** No street name (`台七乙線8.8K處`, `崎頂下坡路段`): nothing to compare. */
    data object Unnamed : CameraRoad

    companion object {
        private const val SUFFIX = "(?:路|街|大道)"
        // A road name: up to 6 chars that aren't separators, then 路/街/大道, then an optional
        // numbered 段. A bare 段 right after (`崎頂下坡路段`, `陽明湖路段`) means "road section",
        // not a road name.
        private val ROAD = Regex("([^與及、,，()（）\\s0-9段]{1,6}?$SUFFIX)(?:[一二三四五六七八九十]+段)?(?!段)")
        /** `X路上往…` / `X路往…` — the camera road named in the direction text. */
        private val ROAD_BEFORE_TOWARD = Regex("^([^往]{1,8}?$SUFFIX)(?:[一二三四五六七八九十]+段)?上?往")
        /**
         * Administrative prefixes some addresses carry: `大里區國光路` → 國光路. Not when the
         * unit char is part of a road name (`園區一路`, `市府路`).
         */
        private val ADMIN_PREFIX = Regex("^(?:[\\u4e00-\\u9fff]{1,3}[縣市區鄉鎮](?![一二三四五六七八九十路街]))+")
        /** Split on conjunctions/punctuation; `和`/`接` only split after a road/section/number/placeholder so `和平路` stays intact. */
        private val SEPARATOR = Regex("[與及、,，()（）]|(?<=[路街道段號巷弄線,])(?:和|接)")
        private val TOWARD_IN_ADDRESS = Regex("往[^、,，()（）\\s]+方向")
        private val SECTION_SUFFIX = Regex("^[一二三四五六七八九十]+段")
        private const val KNOWN_BOUNDARY_CHARS = "路街道段巷弄號線縣市區鄉鎮村里鄰與和及、,，()（） 0123456789"
        private val RELATIVE_PARENS = Regex("[(（][^()（）]*(?:以[東西南北]|往[東西南北]|\\d+\\s*(?:公尺|[mM])|間|中段|[-~～至])[^()（）]*[)）]")
        private val ARABIC_ROAD_DIGIT = Regex("(?<=[\\u4e00-\\u9fff])[1-9](?=[路街段])")
        private val CN_DIGITS = mapOf(
            '1' to '一', '2' to '二', '3' to '三', '4' to '四', '5' to '五',
            '6' to '六', '7' to '七', '8' to '八', '9' to '九',
        )
        private val ROUTE_MARKER = Regex(
            "(?:國道\\s*(?:[一二三四五六七八九十]+|\\d+)\\s*[甲乙丙丁]?\\s*號?|" +
                "(?:國道|省道|縣道|市道|鄉道|區道|[臺澎北桃竹苗中投彰雲嘉南高屏宜花東金連])?\\s*" +
                "(?:[一二三四五六七八九十]+|\\d+)\\s*[甲乙丙丁戊己已]?\\s*(?:線|鄉道|區道|縣道|市道)(?:快速公路|快速道路|公路)?(?:[上下])?)",
        )
        private val KM_MARKER = Regex("\\d+(?:\\.\\d+)?\\s*(?:[kK]\\s*\\+\\s*\\d+\\s*[mM]?|[kK]|公里|公尺|[mM])")
        private val GENERIC_ROADS = setOf(
            "高速公路", "快速道路", "快速公路", "堤外道路", "防汛道路",
            "高架道路", "高架聯絡道", "機慢車專用道", "專用道", "聯絡道",
            "便道", "匝道", "地下道", "車行地下道", "人行道", "產業道路",
            "道路", "公路", "車道", "慢車道", "快車道", "機車道",
        )
        private val GENERIC_ROAD_RE = Regex(GENERIC_ROADS.sortedByDescending { it.length }.joinToString("|"))
        private val VALID_KNOWN_ROAD = Regex("^[^0-9\\s與及、,，()（）]{1,8}$SUFFIX$")

        fun of(address: String, directionText: String, knownRoads: Set<String> = emptySet()): CameraRoad {
            roadBeforeToward(directionText)?.let { return Known(it) }
            val roads = roadsInAddress(address, knownRoads)
            return when (roads.size) {
                0 -> Unnamed
                1 -> Known(roads[0])
                else -> Intersection(roads)
            }
        }

        /** Road explicitly named as the camera's own road in direction text, e.g. `三民路上往龜山` → `三民路`. */
        fun roadBeforeToward(directionText: String): String? =
            ROAD_BEFORE_TOWARD.find(directionText.filterNot(Char::isWhitespace))?.let { base(it.groupValues[1]) }

        /** Ordered, deduplicated base road names appearing anywhere in [address]. */
        fun roadsInAddress(address: String, knownRoads: Set<String> = emptySet()): List<String> {
            var cleaned = address.trim()
                .replace('台', '臺')
                .replace(TOWARD_IN_ADDRESS, ",")
            val withoutRelParens = cleaned.replace(RELATIVE_PARENS, ",")
            if (Regex(SUFFIX).containsMatchIn(withoutRelParens)) {
                cleaned = withoutRelParens
            }
            cleaned = cleaned
                .replace(ADMIN_PREFIX, "")
                .replace(Regex("(?<=[路街道段號巷弄線,，(（])近"), ",")
                .replace(ARABIC_ROAD_DIGIT) { m -> CN_DIGITS[m.value[0]]?.toString() ?: m.value }
                .replace("路路口", "路口")
                .replace("街街口", "街口")
                .replace(ROUTE_MARKER, ",")
                .replace(KM_MARKER, ",")
                .replace(GENERIC_ROAD_RE, ",")
            val buf = StringBuilder(cleaned)
            val found = ArrayList<Pair<Int, String>>()

            val sortedKnown = knownRoads.asSequence()
                .map(::base)
                .filter { VALID_KNOWN_ROAD.matches(it) && !isLane(it) && it !in GENERIC_ROADS }
                .distinct()
                .sortedByDescending { it.length }
            for (k in sortedKnown) {
                var from = 0
                while (true) {
                    val idx = buf.indexOf(k, from)
                    if (idx < 0) break
                    val prevOk = idx == 0 || buf[idx - 1] !in '\u4e00'..'\u9fff' || buf[idx - 1] in KNOWN_BOUNDARY_CHARS
                    val end = idx + k.length
                    val nextOk = end >= buf.length || buf[end] !in "路街道"
                    if (prevOk && nextOk) {
                        val secLen = SECTION_SUFFIX.find(buf.substring(end))?.value?.length ?: 0
                        val totalEnd = end + secLen
                        found += idx to k
                        for (p in idx until totalEnd) buf[p] = ','
                        from = totalEnd
                    } else {
                        from = idx + 1
                    }
                }
            }

            // Fallback regex over remaining pieces, tracking approximate offset for original order.
            var pos = 0
            for (piece in buf.toString().split(SEPARATOR)) {
                val stripped = piece.replace(ADMIN_PREFIX, "")
                val stripShift = piece.length - stripped.length
                for (m in ROAD.findAll(stripped)) {
                    val road = base(m.groupValues[1])
                    if (road.length >= 2 && road !in GENERIC_ROADS) {
                        found += (pos + stripShift + m.range.first) to road
                    }
                }
                pos += piece.length + 1
            }

            return found.sortedBy { it.first }.map { it.second }.distinct()
        }

        /** `大興西路三段` → `大興西路`, `中正路二段239巷` → `中正路`; 台/臺 unified. */
        fun base(name: String): String {
            val n = name.trim().replace('台', '臺')
            val end = Regex(".+?$SUFFIX").find(n)?.range?.last ?: return n
            return n.substring(0, end + 1)
        }

        /** A lane or alley, not a through road. */
        fun isLane(name: String): Boolean = Regex("[巷弄]").containsMatchIn(name)
    }
}
