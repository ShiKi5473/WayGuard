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
        private val LANE_SECTION_RE = Regex("^\\s*((?:[一二三四五六七八九十]+|[0-9０-９]+)段)")
        private const val NUMBERED_LANE_OR_ALLEY = "(?:[0-9０-９]+(?:[之\\-－][0-9０-９]+)?|[一二三四五六七八九十百千]+)"
        private const val NAMED_LANE_PREFIX =
            "(?!(?:小|暗|死|無名|防火|對面|旁|前|後|內|外|底|口|近|往))[^與及、,，()（）\\s0-9０-９路街道段巷弄號線縣市區鄉鎮]{2,6}(?:[一二三四五六七八九十]+)?"
        private val LANE_PART_RE = Regex("^\\s*(${NUMBERED_LANE_OR_ALLEY}巷|${NAMED_LANE_PREFIX}巷)")
        private val ALLEY_PART_RE = Regex("^\\s*(${NUMBERED_LANE_OR_ALLEY}弄)")
        private val EXACT_NON_LANE_TOKENS = setOf("後巷", "前巷", "舊巷", "新巷", "單巷")
        private val NON_LANE_TAILS = setOf("小巷", "暗巷", "死巷", "無名巷", "防火巷", "旁巷", "側巷")
        private val NON_LANE_MARKERS = setOf("對面", "旁邊", "附近", "路口", "方向")
        private val BASE_ROAD_RE = Regex(".+?$SUFFIX")
        private val LANE_CHAR_RE = Regex("[巷弄]")
        private val CN_SECTION_DIGITS = arrayOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九")
        private val ARABIC_SECTION_RE = Regex("(?<=[\\u4e00-\\u9fff])(\\d+)段")
        private val INTERNAL_CJK_SPACE_RE = Regex("(?<=[\\u4e00-\\u9fff0-9])\\s+(?=[\\u4e00-\\u9fff0-9])")
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

        private data class RoadMatch(val offset: Int, val base: String, val lane: String?)

        private class KnownRoadIndex(
            val source: Set<String>,
            val sourceSize: Int,
            val orderByRoad: Map<String, Int>,
            val maxLen: Int,
        )

        @Volatile
        private var lastKnownRoadIndex: KnownRoadIndex? = null
        private val knownRoadIndexCache = ArrayList<KnownRoadIndex>(4)
        private val knownRoadIndexLock = Any()

        private fun indexFor(knownRoads: Set<String>): KnownRoadIndex? {
            if (knownRoads.isEmpty()) return null
            val size = knownRoads.size
            val fast = lastKnownRoadIndex
            if (fast != null && fast.source === knownRoads && fast.sourceSize == size) {
                return fast
            }
            synchronized(knownRoadIndexLock) {
                for (i in knownRoadIndexCache.indices) {
                    val entry = knownRoadIndexCache[i]
                    if (entry.source === knownRoads && entry.sourceSize == size) {
                        lastKnownRoadIndex = entry
                        return entry
                    }
                }
                for (i in knownRoadIndexCache.indices) {
                    val entry = knownRoadIndexCache[i]
                    if (entry.sourceSize == size && entry.source == knownRoads) {
                        val alias = KnownRoadIndex(knownRoads, size, entry.orderByRoad, entry.maxLen)
                        cachePut(alias)
                        return alias
                    }
                }
                val built = buildKnownRoadIndex(knownRoads, size)
                cachePut(built)
                return built
            }
        }

        private fun cachePut(entry: KnownRoadIndex) {
            knownRoadIndexCache.removeAll { it.source === entry.source }
            if (knownRoadIndexCache.size >= 4) {
                knownRoadIndexCache.removeAt(0)
            }
            knownRoadIndexCache.add(entry)
            lastKnownRoadIndex = entry
        }

        private fun buildKnownRoadIndex(knownRoads: Set<String>, size: Int): KnownRoadIndex {
            val sorted = knownRoads.asSequence()
                .map(::base)
                .filter { VALID_KNOWN_ROAD.matches(it) && !isLane(it) && it !in GENERIC_ROADS }
                .distinct()
                .sortedByDescending { it.length }
                .toList()
            val orderByRoad = HashMap<String, Int>((sorted.size * 4 / 3) + 1)
            var maxLen = 0
            for (i in sorted.indices) {
                val road = sorted[i]
                orderByRoad[road] = i
                if (road.length > maxLen) maxLen = road.length
            }
            return KnownRoadIndex(knownRoads, size, orderByRoad, maxLen)
        }

        /** Ordered, deduplicated base road names appearing anywhere in [address]. */
        fun roadsInAddress(address: String, knownRoads: Set<String> = emptySet()): List<String> =
            roadsAndLanesInAddress(address, knownRoads).first

        /**
         * Normalized lane/alley token (`…巷` / `…弄`, e.g. `中華路一段2巷`, `瑤鳳路二段416巷`,
         * `中正路二段239巷15弄`) keyed by base road name for roads in [address] that carry one.
         */
        fun roadLanesInAddress(address: String, knownRoads: Set<String> = emptySet()): Map<String, String> =
            roadsAndLanesInAddress(address, knownRoads).second

        /** Extracts both [roadsInAddress] and [roadLanesInAddress] in a single pass. */
        fun roadsAndLanesInAddress(
            address: String,
            knownRoads: Set<String> = emptySet(),
        ): Pair<List<String>, Map<String, String>> {
            val sorted = extractRoadMatches(address, knownRoads).sortedBy { it.offset }
            val roads = sorted.map { it.base }.distinct()
            val lanes = LinkedHashMap<String, String>()
            for (m in sorted) {
                if (m.lane != null) lanes.putIfAbsent(m.base, m.lane)
            }
            return roads to lanes
        }

        private fun extractRoadMatches(address: String, knownRoads: Set<String>): List<RoadMatch> {
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
            val found = ArrayList<RoadMatch>()

            val index = indexFor(knownRoads)
            if (index != null && index.maxLen >= 2) {
                val orderByRoad = index.orderByRoad
                val maxLen = index.maxLen
                val candidates = HashSet<String>()
                for (end in 2..cleaned.length) {
                    val last = cleaned[end - 1]
                    if (last != '路' && last != '街' && last != '道') continue
                    val minStart = maxOf(0, end - maxLen)
                    val maxStart = end - 2
                    for (start in minStart..maxStart) {
                        val sub = cleaned.substring(start, end)
                        if (sub in orderByRoad) {
                            candidates += sub
                        }
                    }
                }
                if (candidates.isNotEmpty()) {
                    val orderedCandidates = if (candidates.size == 1) {
                        candidates
                    } else {
                        candidates.sortedBy { orderByRoad.getValue(it) }
                    }
                    for (k in orderedCandidates) {
                        var from = 0
                        while (true) {
                            val idx = buf.indexOf(k, from)
                            if (idx < 0) break
                            val prevOk = idx == 0 || buf[idx - 1] !in '\u4e00'..'\u9fff' || buf[idx - 1] in KNOWN_BOUNDARY_CHARS
                            val end = idx + k.length
                            val nextOk = end >= buf.length || buf[end] !in "路街道"
                            if (prevOk && nextOk) {
                                val afterBase = buf.substring(end)
                                val lane = extractLaneAfterRoad(k, afterBase)
                                val secLen = (LANE_SECTION_RE.find(afterBase) ?: SECTION_SUFFIX.find(afterBase))?.value?.length ?: 0
                                val totalEnd = end + secLen
                                found += RoadMatch(idx, k, lane)
                                for (p in idx until totalEnd) buf[p] = ','
                                from = totalEnd
                            } else {
                                from = idx + 1
                            }
                        }
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
                        val baseEnd = m.range.first + m.groupValues[1].length
                        val lane = extractLaneAfterRoad(road, stripped.substring(baseEnd))
                        found += RoadMatch(pos + stripShift + m.range.first, road, lane)
                    }
                }
                pos += piece.length + 1
            }

            return found
        }

        private fun extractLaneAfterRoad(roadBase: String, afterBase: String): String? {
            if (afterBase.isEmpty()) return null
            var pos = LANE_SECTION_RE.find(afterBase)?.let { it.range.last + 1 } ?: 0
            var laneEnd = -1

            val laneMatch = LANE_PART_RE.find(afterBase.substring(pos))
            if (laneMatch != null && isValidLaneToken(laneMatch.groupValues[1])) {
                pos += laneMatch.range.last + 1
                laneEnd = pos
                while (true) {
                    val alleyMatch = ALLEY_PART_RE.find(afterBase.substring(pos)) ?: break
                    pos += alleyMatch.range.last + 1
                    laneEnd = pos
                }
            } else {
                while (true) {
                    val alleyMatch = ALLEY_PART_RE.find(afterBase.substring(pos)) ?: break
                    pos += alleyMatch.range.last + 1
                    laneEnd = pos
                }
            }
            if (laneEnd <= 0) return null
            return normalizeLane(roadBase + afterBase.substring(0, laneEnd))
        }

        private fun isValidLaneToken(token: String): Boolean {
            if (token in EXACT_NON_LANE_TOKENS) return false
            if (NON_LANE_TAILS.any { token.endsWith(it) }) return false
            if (NON_LANE_MARKERS.any { it in token }) return false
            return true
        }

        /**
         * Canonicalizes a lane/alley name for symmetric OSM indexing and address querying:
         * trims spaces (and stray spaces between CJK/digit tokens), unifies `台`→`臺` and
         * full-width digits→ASCII, and converts Arabic section numbers before `段` (1..99)
         * to Chinese numerals (`瑤鳳路2段416巷` → `瑤鳳路二段416巷`).
         */
        fun normalizeLane(name: String): String {
            val ascii = buildString(name.length) {
                for (ch in name.trim()) {
                    append(
                        when (ch) {
                            in '０'..'９' -> ('0' + (ch - '０'))
                            '台' -> '臺'
                            else -> ch
                        }
                    )
                }
            }.replace(INTERNAL_CJK_SPACE_RE, "")
            return ARABIC_SECTION_RE.replace(ascii) { m ->
                val num = m.groupValues[1].toIntOrNull()
                val cn = num?.let(::toChineseSection)
                if (cn != null) "${cn}段" else m.value
            }
        }

        private fun toChineseSection(n: Int): String? = when {
            n <= 0 || n >= 100 -> null
            n < 10 -> CN_SECTION_DIGITS[n]
            n == 10 -> "十"
            n < 20 -> "十${CN_SECTION_DIGITS[n % 10]}"
            n % 10 == 0 -> "${CN_SECTION_DIGITS[n / 10]}十"
            else -> "${CN_SECTION_DIGITS[n / 10]}十${CN_SECTION_DIGITS[n % 10]}"
        }

        /** `大興西路三段` → `大興西路`, `中正路二段239巷` → `中正路`; 台/臺 unified. */
        fun base(name: String): String {
            val n = name.trim().replace('台', '臺')
            val end = BASE_ROAD_RE.find(n)?.range?.last ?: return n
            return n.substring(0, end + 1)
        }

        /**
         * A lane or alley, not a through road. Characters inside a road's own base name before
         * `路`/`街`/`大道` (e.g. `大巷街`, `大巷街二段`) do not make it a lane.
         */
        fun isLane(name: String): Boolean {
            val n = name.trim().replace('台', '臺')
            val baseMatch = BASE_ROAD_RE.find(n)
            val afterBase = if (baseMatch != null) n.substring(baseMatch.range.last + 1) else n
            return LANE_CHAR_RE.containsMatchIn(afterBase)
        }
    }
}
