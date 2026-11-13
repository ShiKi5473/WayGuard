package com.wayguard.sync

import com.wayguard.core.dataset.BuildResult
import com.wayguard.core.dataset.Place
import com.wayguard.core.dataset.PlaceRef
import com.wayguard.core.model.DatasetJson
import com.wayguard.core.parse.PlaceTarget
import com.wayguard.core.validate.CountyBounds
import com.waymap.geo.GeoUtils
import com.waymap.geo.LatLng
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Curation aid, run by hand (`--suggest-places`), never by the weekly job: geocodes the
 * destination names that data/source/places.json doesn't know yet via OpenStreetMap
 * Nominatim and writes candidates for review. Nothing is applied automatically — a
 * human checks the places and copies good entries into places.json. (Whether each
 * camera's direction is then trusted is decided automatically by the road check.)
 *
 * Strategies, by what the name looks like:
 * - `市區` → the office of the camera's own district (scoped to that district);
 * - a district/town name of the camera's city (`大溪` → 大溪區) → its district office;
 * - a city name (`臺北市`) → the city hall;
 * - a road (`興豐路`, `國道二號`) → the point of that road nearest the camera(s), scoped
 *   to the cameras' district and marked `road` (a far "nearest point" is not trusted);
 * - anything else → the best search hit near the cameras, then a district of another city.
 */
internal class PlaceSuggester(private val nominatim: Nominatim) {

    data class Suggestion(
        val ref: PlaceRef,
        val cameras: List<LatLng>,
        val method: String,
        val query: String,
        val place: Place?,
        val osmLabel: String = "",
    )

    private val prettyJson = Json(DatasetJson) { prettyPrint = true }
    /** (city, name) → result, so a town used from several districts is looked up once. */
    private val cityLevelCache = HashMap<Pair<String, String>, Suggestion>()

    /** Writes `place-suggestions.json` / `.md` into [outDir]; returns how many were found. */
    fun run(result: BuildResult, outDir: File): Int {
        val districts = result.dataset.cameras.groupBy({ it.city }, { it.district })
            .mapValues { (_, v) -> v.filter(String::isNotBlank).toSet() }
        val suggestions = result.report.unmatchedPlaces.map { (ref, cams) -> suggest(ref, cams, districts) }
        val places = suggestions.mapNotNull { it.place }.distinct()
        outDir.mkdirs()
        File(outDir, "place-suggestions.json")
            .writeText(prettyJson.encodeToString(ListSerializer(Place.serializer()), places) + "\n")
        File(outDir, "place-suggestions.md").writeText(markdown(suggestions))
        return suggestions.count { it.place != null }
    }

    private fun suggest(ref: PlaceRef, cams: List<LatLng>, districts: Map<String, Set<String>>): Suggestion {
        val center = centroid(cams)
        val name = ref.name
        if (name == "市區" && ref.district.isNotBlank()) {
            return office(ref, cams, "${ref.district}公所", scopeDistrict = true, method = "所在行政區公所")
        }
        val base = name.removeSuffix("市區").ifBlank { name }
        val key = PlaceTarget.key(base)
        // A whole county/city: 往台北市 → 臺北市政府. Only real counties — the dataset's
        // highway-police rows use road names (國道二號) as their "city".
        districts.keys.firstOrNull { CountyBounds.isKnown(it) && PlaceTarget.key(it) == key }?.let { city ->
            return cityLevel(ref, base, cams) { office(ref, cams, "${city}政府", scopeDistrict = false, method = "縣市政府") }
        }
        val suffixes = listOf("區", "鄉", "鎮", "市")
        fun districtIn(city: String) = districts[city].orEmpty().firstOrNull { d ->
            PlaceTarget.key(d) == key || suffixes.any { PlaceTarget.key(d) == key + it }
        }
        // 1) A district/town of the camera's own city.
        districtIn(ref.city)?.let { district ->
            return cityLevel(ref, base, cams) { office(ref, cams, "${district}公所", scopeDistrict = false, method = "鄉鎮市區公所") }
        }
        if (isRoad(name)) return road(ref, cams, center)
        // 2) A place near the cameras (往埔心 from 平鎮 is 楊梅's 埔心, not 彰化縣埔心鄉).
        val near = cityLevel(ref, name, cams) { landmark(ref, cams, center) }
        if (near.place != null) return near
        // 3) A district/town of another city (往新莊 from 桃園).
        districts.keys.filter(CountyBounds::isKnown).firstNotNullOfOrNull(::districtIn)?.let { district ->
            return cityLevel(ref, "$base@other", cams) { office(ref, cams, "${district}公所", scopeDistrict = false, method = "他縣市鄉鎮市區公所") }
        }
        return near
    }

    /** Looks [name] up once per city; the result is re-targeted at each ref's own cameras. */
    private fun cityLevel(ref: PlaceRef, name: String, cams: List<LatLng>, lookup: () -> Suggestion): Suggestion {
        val s = cityLevelCache.getOrPut(ref.city to name, lookup)
        return s.copy(ref = ref, cameras = cams, place = s.place?.copy(name = ref.name))
    }

    private fun office(ref: PlaceRef, cams: List<LatLng>, query: String, scopeDistrict: Boolean, method: String): Suggestion {
        val hits = nominatim.search(query, Nominatim.Box.around(centroid(cams), 0.4))
        // Must be that very office: a fuzzy "北埔鄉公所" for "埔心鄉公所" is no match.
        val hit = hits.firstOrNull { it.label.startsWith(query) && it.type == "townhall" }
            ?: hits.firstOrNull { it.label.startsWith(query) }
        return toSuggestion(ref, cams, method, query, hit?.pos, hit?.label, scopeDistrict, road = false)
    }

    private fun landmark(ref: PlaceRef, cams: List<LatLng>, center: LatLng): Suggestion {
        val hits = nominatim.search(ref.name, Nominatim.Box.around(center, 0.15))
        val hit = hits.firstOrNull { it.category != "highway" } ?: hits.firstOrNull()
        return toSuggestion(ref, cams, "地標搜尋", ref.name, hit?.pos, hit?.label, scopeDistrict = false, road = false)
    }

    /** Nearest point of the named road to the cameras (searches its geometry). */
    private fun road(ref: PlaceRef, cams: List<LatLng>, center: LatLng): Suggestion {
        val queries = buildList {
            add(ref.name)
            // 台66快速道路 → 台66線
            Regex("[台臺](\\d+)").find(ref.name)?.let { add("台${it.groupValues[1]}線") }
        }
        for (q in queries) {
            val nearest = nominatim.search(q, Nominatim.Box.around(center, 0.05), geometry = true)
                .filter { it.category == "highway" }
                .mapNotNull { h -> Nominatim.nearestOnLines(h.lines, center)?.let { h.label to it.first } }
                .minByOrNull { GeoUtils.haversine(center, it.second) }
            if (nearest != null) {
                return toSuggestion(ref, cams, "道路最近點", q, nearest.second, nearest.first, scopeDistrict = true, road = true)
            }
        }
        return Suggestion(ref, cams, "道路最近點", queries.joinToString(" / "), null)
    }

    private fun toSuggestion(
        ref: PlaceRef, cams: List<LatLng>, method: String, query: String,
        pos: LatLng?, osmLabel: String?, scopeDistrict: Boolean, road: Boolean,
    ): Suggestion {
        if (pos == null) return Suggestion(ref, cams, method, query, null)
        val label = osmLabel.orEmpty().split(", ").take(3).joinToString(", ")
        val place = Place(
            name = ref.name,
            city = ref.city,
            district = ref.district.takeIf { scopeDistrict && it.isNotBlank() },
            lat = round6(pos.lat),
            lon = round6(pos.lon),
            ref = "$label（OSM，$method）",
            road = road,
        )
        return Suggestion(ref, cams, method, query, place, label)
    }

    private fun centroid(pts: List<LatLng>) = LatLng(pts.map { it.lat }.average(), pts.map { it.lon }.average())

    private fun isRoad(name: String) =
        listOf("路", "街", "大道", "道路", "公路").any { name.endsWith(it) } || "國道" in name || "快速" in name

    private fun round6(v: Double) = (v * 1e6).roundToInt() / 1e6

    private fun markdown(list: List<Suggestion>): String = buildString {
        appendLine("# 地名對照候選")
        appendLine()
        appendLine("由 Nominatim（OpenStreetMap）查得，**未經確認**。請確認地點正確後，把 `place-suggestions.json` 中的項目")
        appendLine("複製到 `data/source/places.json`（可合併成 aliases）。各測速點是否採用推算方向，之後由道路走向比對自動決定。")
        appendLine()
        appendLine("| 範圍 | 地名 | 方法 | 查詢 | OSM 結果 | 座標 | 各測速點：目的地方位／距離 |")
        appendLine("|---|---|---|---|---|---|---|")
        for (s in list.sortedWith(compareBy({ it.place == null }, { it.ref.city }, { it.ref.name }, { it.ref.district }))) {
            val p = s.place
            val coord = p?.let { "${it.lat}, ${it.lon}" } ?: "—"
            val dirs = if (p == null) "—" else s.cameras.joinToString("<br>") { c ->
                val h = (GeoUtils.bearing(c, p.pos).roundToInt() % 360 + 360) % 360
                val d = GeoUtils.haversine(c, p.pos)
                "${h}°（${compass(h)}）／${String.format(Locale.ROOT, "%.1f", d / 1000)} km"
            }
            appendLine("| ${s.ref.city}${s.ref.district} | ${s.ref.name} | ${s.method} | ${s.query} | ${s.osmLabel.ifBlank { "找不到" }} | $coord | $dirs |")
        }
    }

    private fun compass(h: Int) = listOf("北", "東北", "東", "東南", "南", "西南", "西", "西北")[((h + 22) / 45) % 8]
}
