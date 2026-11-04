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
        private val ROAD = Regex("([^與和及、,，()（）\\s0-9段]{1,6}?$SUFFIX)(?:[一二三四五六七八九十]+段)?(?!段)")
        /** `X路上往…` / `X路往…` — the camera road named in the direction text. */
        private val ROAD_BEFORE_TOWARD = Regex("^([^往]{1,8}?$SUFFIX)(?:[一二三四五六七八九十]+段)?上?往")
        /**
         * Administrative prefixes some addresses carry: `大里區國光路` → 國光路. Not when the
         * unit char is part of a road name (`園區一路`, `市府路`).
         */
        private val ADMIN_PREFIX = Regex("^[\\u4e00-\\u9fff]{1,3}[區鄉鎮市](?![一二三四五六七八九十路街])")

        fun of(address: String, directionText: String): CameraRoad {
            ROAD_BEFORE_TOWARD.find(directionText.filterNot(Char::isWhitespace))?.let {
                return Known(base(it.groupValues[1]))
            }
            // Every road named anywhere in the address (`中華路龍壽街口` names two).
            val roads = address.split(Regex("[與和及、,，()（）]"))
                .flatMap { piece -> ROAD.findAll(piece.replace(ADMIN_PREFIX, "")).map { it.groupValues[1] } }
                .map(::base)
                .distinct()
            return when (roads.size) {
                0 -> Unnamed
                1 -> Known(roads[0])
                else -> Intersection(roads)
            }
        }

        /** `大興西路三段` → `大興西路`, `中正路二段239巷` → `中正路`; 台/臺 unified. */
        fun base(name: String): String {
            val n = name.trim().replace('台', '臺')
            val end = Regex(SUFFIX).find(n)?.range?.last ?: return n
            return n.substring(0, end + 1)
        }

        /** A lane or alley, not a through road. */
        fun isLane(name: String): Boolean = Regex("[巷弄]").containsMatchIn(name)
    }
}
