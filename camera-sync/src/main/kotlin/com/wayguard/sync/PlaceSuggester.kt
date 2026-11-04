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
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Curation aid, run by hand (`--suggest-places`), never by the weekly job: geocodes the
 * destination names that data/source/places.json doesn't know yet via OpenStreetMap
 * Nominatim and writes candidates for review. Nothing is applied automatically — a
 * human checks each bearing and copies good entries into places.json.
 *
 * Strategies, by what the name looks like:
 * - `市區` → the office of the camera's own district (scoped to that district);
 * - a district/town name (`大溪` → 大溪區) → its district office;
 * - a city name (`臺北市`) → the city hall;
 * - a road (`興豐路`, `國道二號`) → the point of that road nearest the camera(s), scoped
 *   to the cameras' district because a long road's relevant point differs per area;
 * - anything else → the best search hit near the cameras.
 *
 * Respects Nominatim's usage policy: identifying User-Agent, at most one request/s.
 */
internal class PlaceSuggester(private val userAgent: String) {

    data class Suggestion(
        val ref: PlaceRef,
        val cameras: List<LatLng>,
        val method: String,
        val query: String,
        val place: Place?,
        val osmLabel: String = "",
    )

    private data class Hit(val pos: LatLng, val label: String, val category: String, val type: String)

    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
    private val prettyJson = Json(DatasetJson) { prettyPrint = true }
    private var lastRequestMs = 0L
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
        val hits = search(query, viewbox = box(centroid(cams), 0.4))
        // Must be that very office: a fuzzy "北埔鄉公所" for "埔心鄉公所" is no match.
        val hit = hits.firstOrNull { it.label.startsWith(query) && it.type == "townhall" }
            ?: hits.firstOrNull { it.label.startsWith(query) }
        return toSuggestion(ref, cams, method, query, hit, scopeDistrict)
    }

    private fun landmark(ref: PlaceRef, cams: List<LatLng>, center: LatLng): Suggestion {
        val hits = search(ref.name, viewbox = box(center, 0.15))
        val hit = hits.firstOrNull { it.category != "highway" } ?: hits.firstOrNull()
        return toSuggestion(ref, cams, "地標搜尋", ref.name, hit, scopeDistrict = false)
    }

    /** Nearest point of the named road to the cameras (searches its geometry). */
    private fun road(ref: PlaceRef, cams: List<LatLng>, center: LatLng): Suggestion {
        val queries = buildList {
            add(ref.name)
            // 台66快速道路 → 台66線
            Regex("[台臺](\\d+)").find(ref.name)?.let { add("台${it.groupValues[1]}線") }
        }
        for (q in queries) {
            val lines = searchGeometry(q, box(center, 0.05))
            val nearest = lines.flatMap { (label, pts) -> nearestOn(pts, center)?.let { listOf(label to it) } ?: emptyList() }
                .minByOrNull { GeoUtils.haversine(center, it.second) }
            if (nearest != null) {
                val hit = Hit(nearest.second, nearest.first, "highway", "")
                return toSuggestion(ref, cams, "道路最近點", q, hit, scopeDistrict = true)
            }
        }
        return Suggestion(ref, cams, "道路最近點", queries.joinToString(" / "), null)
    }

    private fun toSuggestion(ref: PlaceRef, cams: List<LatLng>, method: String, query: String, hit: Hit?, scopeDistrict: Boolean): Suggestion {
        if (hit == null) return Suggestion(ref, cams, method, query, null)
        val label = hit.label.split(", ").take(3).joinToString(", ")
        val place = Place(
            name = ref.name,
            city = ref.city,
            district = ref.district.takeIf { scopeDistrict && it.isNotBlank() },
            lat = round6(hit.pos.lat),
            lon = round6(hit.pos.lon),
            ref = "$label（OSM，$method）",
        )
        return Suggestion(ref, cams, method, query, place, label)
    }

    // ---------------- Nominatim ----------------

    private data class Box(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double)

    private fun box(c: LatLng, deg: Double) = Box(c.lat - deg, c.lat + deg, c.lon - deg, c.lon + deg)

    private fun search(q: String, viewbox: Box): List<Hit> =
        request(q, viewbox, polygon = false).mapNotNull { o ->
            val lat = o["lat"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = o["lon"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
            Hit(
                LatLng(lat, lon),
                o["display_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                o["category"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                o["type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }

    /** Highway results with their line geometry: label → points. */
    private fun searchGeometry(q: String, viewbox: Box): List<Pair<String, List<LatLng>>> =
        request(q, viewbox, polygon = true)
            .filter { it["category"]?.jsonPrimitive?.contentOrNull == "highway" }
            .mapNotNull { o ->
                val geo = o["geojson"]?.jsonObject ?: return@mapNotNull null
                val coords = geo["coordinates"] as? JsonArray ?: return@mapNotNull null
                val pts = when (geo["type"]?.jsonPrimitive?.contentOrNull) {
                    "LineString" -> coords.map(::toLatLng)
                    "MultiLineString" -> coords.flatMap { it.jsonArray.map(::toLatLng) }
                    "Point" -> listOf(toLatLng(coords))
                    else -> emptyList()
                }.filterNotNull()
                (o["display_name"]?.jsonPrimitive?.contentOrNull.orEmpty()) to pts
            }

    private fun toLatLng(e: kotlinx.serialization.json.JsonElement): LatLng? {
        val a = e as? JsonArray ?: return null
        val lon = a.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return null
        val lat = a.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return null
        return LatLng(lat, lon)
    }

    private fun request(q: String, b: Box, polygon: Boolean): List<JsonObject> {
        // Nominatim allows at most one request per second.
        val wait = 1_100 - (System.currentTimeMillis() - lastRequestMs)
        if (wait > 0) Thread.sleep(wait)
        lastRequestMs = System.currentTimeMillis()
        val url = buildString {
            append("https://nominatim.openstreetmap.org/search?format=jsonv2&countrycodes=tw&limit=10")
            append("&accept-language=zh-TW&bounded=1")
            append("&viewbox=").append("${b.minLon},${b.maxLat},${b.maxLon},${b.minLat}")
            if (polygon) append("&polygon_geojson=1&polygon_threshold=0.00005")
            append("&q=").append(URLEncoder.encode(q, Charsets.UTF_8))
        }
        return try {
            val resp = client.send(
                HttpRequest.newBuilder(URI.create(url)).header("User-Agent", userAgent)
                    .timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString(Charsets.UTF_8),
            )
            if (resp.statusCode() != 200) emptyList()
            else DatasetJson.parseToJsonElement(resp.body()).jsonArray.map { it.jsonObject }
        } catch (e: Exception) {
            System.err.println("查詢失敗：$q（$e）")
            emptyList()
        }
    }

    // ---------------- geometry / output ----------------

    private fun nearestOn(pts: List<LatLng>, p: LatLng): LatLng? = when (pts.size) {
        0 -> null
        1 -> pts[0]
        else -> pts.zipWithNext { a, b -> GeoUtils.projectOnSegment(p, a, b) }
            .minByOrNull { GeoUtils.haversine(p, it) }
    }

    private fun centroid(pts: List<LatLng>) = LatLng(pts.map { it.lat }.average(), pts.map { it.lon }.average())

    private fun isRoad(name: String) =
        listOf("路", "街", "大道", "道路", "公路").any { name.endsWith(it) } || "國道" in name || "快速" in name

    private fun round6(v: Double) = (v * 1e6).roundToInt() / 1e6

    private fun markdown(list: List<Suggestion>): String = buildString {
        appendLine("# 地名對照候選")
        appendLine()
        appendLine("由 Nominatim（OpenStreetMap）查得，**未經確認**。請檢查每個測速點的推算方向是否符合道路走向，")
        appendLine("確認後把 `place-suggestions.json` 中正確的項目複製到 `data/source/places.json`（可合併成 aliases）。")
        appendLine()
        appendLine("| 範圍 | 地名 | 方法 | 查詢 | OSM 結果 | 座標 | 各測速點：推算方向／距離 |")
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
