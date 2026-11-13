package com.wayguard.sync

import com.wayguard.core.model.DatasetJson
import com.waymap.geo.GeoUtils
import com.waymap.geo.LatLng
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Minimal OpenStreetMap Nominatim client. Follows the usage policy: identifying
 * User-Agent and at most one request per second (shared by all calls).
 */
internal class Nominatim(private val userAgent: String) {

    data class Box(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double) {
        companion object {
            fun around(c: LatLng, deg: Double) = Box(c.lat - deg, c.lat + deg, c.lon - deg, c.lon + deg)
        }
    }

    /** A result with its line geometry (empty when not requested / not a line). */
    data class Hit(
        val pos: LatLng,
        val label: String,
        val name: String,
        val category: String,
        val type: String,
        val lines: List<List<LatLng>> = emptyList(),
    )

    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
    private var lastRequestMs = 0L

    fun search(q: String, viewbox: Box, geometry: Boolean = false): List<Hit> {
        val url = buildString {
            append("$BASE/search?format=jsonv2&countrycodes=tw&limit=10&accept-language=zh-TW&bounded=1")
            append("&viewbox=${viewbox.minLon},${viewbox.maxLat},${viewbox.maxLon},${viewbox.minLat}")
            if (geometry) append("&polygon_geojson=1&polygon_threshold=0.00005")
            append("&q=").append(URLEncoder.encode(q, Charsets.UTF_8))
        }
        val body = get(url) ?: return emptyList()
        return DatasetJson.parseToJsonElement(body).jsonArray.mapNotNull { hit(it.jsonObject) }
    }

    /** The street nearest [p] (zoom 17 = major and minor streets), with its geometry. */
    fun reverseStreet(p: LatLng): Hit? {
        val url = "$BASE/reverse?format=jsonv2&zoom=17&accept-language=zh-TW&polygon_geojson=1" +
            "&polygon_threshold=0.00002&lat=${p.lat}&lon=${p.lon}"
        val body = get(url) ?: return null
        val o = DatasetJson.parseToJsonElement(body) as? JsonObject ?: return null
        if (o["error"] != null) return null
        return hit(o)
    }

    private fun hit(o: JsonObject): Hit? {
        val lat = o["lat"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return null
        val lon = o["lon"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return null
        return Hit(
            pos = LatLng(lat, lon),
            label = o["display_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            category = o["category"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            type = o["type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            lines = lines(o["geojson"] as? JsonObject),
        )
    }

    private fun lines(geo: JsonObject?): List<List<LatLng>> {
        val coords = geo?.get("coordinates") as? JsonArray ?: return emptyList()
        return when (geo["type"]?.jsonPrimitive?.contentOrNull) {
            "LineString" -> listOf(coords.mapNotNull(::toLatLng))
            "MultiLineString" -> coords.map { it.jsonArray.mapNotNull(::toLatLng) }
            else -> emptyList()
        }
    }

    private fun toLatLng(e: JsonElement): LatLng? {
        val a = e as? JsonArray ?: return null
        val lon = a.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return null
        val lat = a.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return null
        return LatLng(lat, lon)
    }

    /** Body of a 200 response, or null on any failure (callers treat it as "unknown"). */
    private fun get(url: String): String? {
        val wait = 1_100 - (System.currentTimeMillis() - lastRequestMs)
        if (wait > 0) Thread.sleep(wait)
        lastRequestMs = System.currentTimeMillis()
        return try {
            val resp = client.send(
                HttpRequest.newBuilder(URI.create(url)).header("User-Agent", userAgent)
                    .timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString(Charsets.UTF_8),
            )
            if (resp.statusCode() == 200) resp.body()
            else { System.err.println("Nominatim HTTP ${resp.statusCode()}：$url"); null }
        } catch (e: Exception) {
            System.err.println("Nominatim 連線失敗：$e")
            null
        }
    }

    companion object {
        private const val BASE = "https://nominatim.openstreetmap.org"

        /** Nearest point on any of [lines] and the bearing of the segment it lies on. */
        fun nearestOnLines(lines: List<List<LatLng>>, p: LatLng): Pair<LatLng, Double>? =
            lines.mapNotNull { nearestOnLine(it, p) }.minByOrNull { GeoUtils.haversine(p, it.first) }

        /** Nearest point on a polyline and the bearing of the segment it lies on. */
        fun nearestOnLine(pts: List<LatLng>, p: LatLng): Pair<LatLng, Double>? {
            if (pts.size < 2) return null
            return pts.zipWithNext()
                .filter { (a, b) -> GeoUtils.haversine(a, b) > 0.5 } // duplicate vertices have no direction
                .map { (a, b) -> GeoUtils.projectOnSegment(p, a, b) to GeoUtils.bearing(a, b) }
                .minByOrNull { GeoUtils.haversine(p, it.first) }
        }
    }
}
