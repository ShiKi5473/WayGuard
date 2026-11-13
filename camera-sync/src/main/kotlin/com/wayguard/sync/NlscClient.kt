package com.wayguard.sync

import com.wayguard.core.validate.CountyBounds
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Client for the National Land Surveying and Mapping Center (國土測繪中心) free, keyless
 * TownVillagePointQuery API (`https://api.nlsc.gov.tw/other/TownVillagePointQuery/{lon}/{lat}/4326`).
 *
 * Used strictly as a secondary verification on points already flagged suspect/mismatch by local
 * OSM boundaries, or on newly suggested coordinates, at most 1 request per second.
 */
internal class NlscClient(
    private val fetcher: ((String) -> String?)? = null,
    private val minIntervalMs: Long = 1_100L,
) {
    data class AdminResult(val city: String, val district: String)

    private val client by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    }
    private var lastRequestMs = 0L

    /**
     * Queries the Taiwan county/city and township/district containing ([lat], [lon]).
     * Returns `null` when offline, rate-limited out, or the service cannot be reached.
     */
    fun query(lat: Double, lon: Double): AdminResult? {
        val url = "$BASE_URL/$lon/$lat/4326"
        val body = if (fetcher != null) fetcher.invoke(url) else httpGet(url)
        return body?.let(::parseResponse)
    }

    private fun httpGet(url: String): String? {
        val wait = minIntervalMs - (System.currentTimeMillis() - lastRequestMs)
        if (wait > 0) Thread.sleep(wait)
        lastRequestMs = System.currentTimeMillis()
        return try {
            val req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            if (resp.statusCode() == 200) resp.body() else null
        } catch (_: Exception) {
            null
        }
    }

    internal companion object {
        private const val BASE_URL = "https://api.nlsc.gov.tw/other/TownVillagePointQuery"
        private val XML_CTY = Regex("<ctyName>\\s*([^<]+?)\\s*</ctyName>")
        private val XML_TOWN = Regex("<townName>\\s*([^<]+?)\\s*</townName>")
        private val JSON_CTY = Regex("\"ctyName\"\\s*:\\s*\"([^\"]+)\"")
        private val JSON_TOWN = Regex("\"townName\"\\s*:\\s*\"([^\"]+)\"")

        fun parseResponse(body: String): AdminResult? {
            val cty = XML_CTY.find(body)?.groupValues?.get(1)
                ?: JSON_CTY.find(body)?.groupValues?.get(1)
                ?: return null
            val town = XML_TOWN.find(body)?.groupValues?.get(1)
                ?: JSON_TOWN.find(body)?.groupValues?.get(1)
                ?: ""
            val normCity = CountyBounds.normalizeCity(cty)
            val normTown = CountyBounds.normalizeCity(town)
            if (normCity.isBlank()) return null
            return AdminResult(normCity, normTown)
        }
    }
}

