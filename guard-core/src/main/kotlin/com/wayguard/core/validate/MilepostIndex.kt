package com.wayguard.core.validate

import com.wayguard.core.parse.AddressParser
import com.waymap.geo.GeoUtils
import com.waymap.geo.LatLng
import kotlin.math.abs

data class MilepostMarker(
    val routeRef: String,
    val isFreeway: Boolean,
    val km: Double,
    val pos: LatLng,
)

/**
 * Indexes highway/freeway milepost coordinates and linearly interpolates coordinates
 * for addresses like `臺14線70.7公里處` or `國道一號南向279公里`.
 */
class MilepostIndex(markers: List<MilepostMarker>) {

    private data class Key(val routeRef: String, val isFreeway: Boolean)

    private val byRoute: Map<Key, List<MilepostMarker>> = markers
        .groupBy { Key(it.routeRef, it.isFreeway) }
        .mapValues { (_, list) -> list.sortedBy { it.km } }

    val size: Int = markers.size

    fun isEmpty(): Boolean = byRoute.isEmpty()

    /**
     * Estimates the coordinate at [km] on route ([routeRef], [isFreeway]). Returns null if
     * the route has no mileposts nearby or the bracketing gap exceeds [maxBracketKm].
     */
    fun interpolate(routeRef: String, isFreeway: Boolean, km: Double, maxBracketKm: Double = 5.0): LatLng? {
        val list = byRoute[Key(routeRef, isFreeway)] ?: return null
        if (list.isEmpty()) return null

        val nearest = list.minBy { abs(it.km - km) }
        if (abs(nearest.km - km) <= 0.05) return nearest.pos

        val idx = list.binarySearchBy(km) { it.km }
        val ins = if (idx >= 0) return list[idx].pos else -idx - 1
        if (ins == 0 || ins >= list.size) return null

        val prev = list[ins - 1]
        val next = list[ins]
        val spanKm = next.km - prev.km
        if (spanKm <= 0.0 || spanKm > maxBracketKm) return null

        // Sanity guard against disjoint branches on the same ref: physical spacing ~ km spacing.
        val physicalKm = GeoUtils.haversine(prev.pos, next.pos) / 1000.0
        if (physicalKm > spanKm * 3.0 + 1.0) return null

        val t = ((km - prev.km) / spanKm).coerceIn(0.0, 1.0)
        return LatLng(
            prev.pos.lat + t * (next.pos.lat - prev.pos.lat),
            prev.pos.lon + t * (next.pos.lon - prev.pos.lon),
        )
    }

    companion object {
        private val KM_VAL_RE = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:[kK]|公里)?(?:\\s*\\+\\s*(\\d+))?")

        /** Parses a highway/freeway milepost CSV (WGS84 or TWD97 coordinates). */
        fun fromCsv(csvText: String): MilepostIndex {
            val lines = csvText.lineSequence()
                .map { it.trimStart('\uFEFF').trimEnd('\r') }
                .filter { it.isNotBlank() }
                .toList()
            if (lines.size < 2) return MilepostIndex(emptyList())

            val delim = TgosCsv.detectDelimiter(lines.first())
            val headers = TgosCsv.splitCsvLine(lines.first(), delim).map { it.trim().lowercase() }

            fun col(vararg candidates: String): Int {
                for (c in candidates) {
                    val idx = headers.indexOf(c)
                    if (idx >= 0) return idx
                }
                for (c in candidates) {
                    val idx = headers.indexOfFirst { c in it }
                    if (idx >= 0) return idx
                }
                return -1
            }

            val routeIdx = col("路線編號", "路線名稱", "公路編號", "國道", "省道", "route", "ref")
            val kmIdx = col("里程", "公里", "牌面里程", "km", "milepost")
            val xIdx = col("經度", "x", "lon", "lng", "twd97_x", "x坐標")
            val yIdx = col("緯度", "y", "lat", "twd97_y", "y坐標")
            if (routeIdx < 0 || kmIdx < 0 || xIdx < 0 || yIdx < 0) return MilepostIndex(emptyList())

            val markers = ArrayList<MilepostMarker>()
            for (line in lines.drop(1)) {
                val cols = TgosCsv.splitCsvLine(line, delim)
                val rawRoute = cols.getOrNull(routeIdx)?.trim().orEmpty()
                val rawKm = cols.getOrNull(kmIdx)?.trim().orEmpty()
                val x = cols.getOrNull(xIdx)?.trim()?.toDoubleOrNull() ?: continue
                val y = cols.getOrNull(yIdx)?.trim()?.toDoubleOrNull() ?: continue
                val pos = TgosCsv.toWgs84(x, y) ?: continue
                if (!CountyBounds.TAIWAN.contains(pos.lat, pos.lon)) continue

                val isFreeway = "國道" in rawRoute || "freeway" in rawRoute.lowercase()
                val ref = AddressParser.canonicalRouteRef(rawRoute) ?: continue
                val km = parseKmToken(rawKm) ?: continue
                markers += MilepostMarker(ref, isFreeway, km, pos)
            }
            return MilepostIndex(markers)
        }

        internal fun parseKmToken(raw: String): Double? {
            val cleaned = raw.trim().replace('Ｋ', 'K').replace('ｋ', 'k').replace('＋', '+')
            cleaned.toDoubleOrNull()?.let { return it }
            val m = KM_VAL_RE.find(cleaned) ?: return null
            val base = m.groupValues[1].toDoubleOrNull() ?: return null
            val plus = m.groupValues[2].toDoubleOrNull() ?: 0.0
            return base + plus / 1000.0
        }
    }
}

