package com.wayguard.sync

import com.wayguard.core.model.DatasetJson
import com.wayguard.core.validate.CountyBounds
import com.wayguard.core.validate.TgosCsv
import com.wayguard.core.validate.TgosHit
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Online client for Taiwan TGOS house-number geocoding Web Service (`QueryAddr.asmx/QueryAddr`).
 *
 * Requires paired [appId] and [apiKey] issued for the authorized service URL ([referer], typically
 * `https://www.tgos.tw/TGOS/Addr/addrCompare`). Only house-number queries are sent here, and
 * returned coordinates are consumed in-memory for distance verification without ever being written
 * to public repository files.
 */
internal class TgosClient(
    private val appId: String,
    private val apiKey: String,
    private val referer: String = DEFAULT_REFERER,
    private val fetcher: ((url: String, formBody: String) -> String?)? = null,
    private val minIntervalMs: Long = 150L,
    private val maxConsecutiveFailures: Int = 3,
    private val logger: (String) -> Unit = { System.err.println(it) },
) {
    private val client by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    }
    private val cache = HashMap<String, TgosHit?>()
    private var lastRequestMs = 0L
    private var consecutiveFailures = 0
    private var aborted = false
    private var loggedFirstFailure = false

    /** Looks up a single cleaned address via TGOS QueryAddr, caching responses per cleaned address. */
    fun query(id: String, cleanedAddress: String): TgosHit? {
        val addr = cleanedAddress.trim()
        if (addr.isEmpty() || appId.isBlank() || apiKey.isBlank() || aborted) return null
        if (addr in cache) {
            return cache[addr]?.copy(id = id)
        }
        val hit = queryEndpoints(id, addr)
        // Do not negative-cache circuit-broken/failed lookups unless a valid response arrived.
        if (consecutiveFailures == 0) {
            cache[addr] = hit
        }
        return hit
    }

    private fun queryEndpoints(id: String, address: String): TgosHit? {
        var lastError: String? = null
        val v40Body = buildFormBody(address, includeV40Fields = true)
        val (rawV40, errV40) = invokeEndpoint(ENDPOINT_V40, v40Body)
        if (rawV40 != null) {
            val parsed = parseQueryAddrResponse(id, rawV40, address)
            if (parsed != null || looksLikeValidEmptyResult(rawV40)) {
                consecutiveFailures = 0
                return parsed
            }
            lastError = extractErrorSummary(rawV40) ?: "unparseable v40 payload"
        } else {
            lastError = errV40
        }

        val v30Body = buildFormBody(address, includeV40Fields = false)
        val (rawV30, errV30) = invokeEndpoint(ENDPOINT_V30, v30Body)
        if (rawV30 != null) {
            val parsed = parseQueryAddrResponse(id, rawV30, address)
            if (parsed != null || looksLikeValidEmptyResult(rawV30)) {
                consecutiveFailures = 0
                return parsed
            }
            lastError = extractErrorSummary(rawV30) ?: "unparseable v30 payload"
        } else {
            lastError = errV30 ?: lastError
        }

        recordFailure(lastError ?: "unknown error")
        return null
    }

    private fun recordFailure(detail: String) {
        consecutiveFailures++
        if (!loggedFirstFailure) {
            loggedFirstFailure = true
            logger("TGOS 線上查詢失敗（將重試，達 $maxConsecutiveFailures 次自動停用本輪查詢）：$detail")
        }
        if (maxConsecutiveFailures > 0 && consecutiveFailures >= maxConsecutiveFailures) {
            aborted = true
            logger("TGOS 連續失敗 $consecutiveFailures 次，已熔斷本輪線上查詢。")
        }
    }

    private fun buildFormBody(address: String, includeV40Fields: Boolean): String {
        val params = LinkedHashMap<String, String>()
        params["oAPPId"] = appId
        params["oAPIKey"] = apiKey
        params["oAddress"] = address
        params["oSRS"] = "EPSG:4326"
        params["oFuzzyType"] = "0"
        params["oResultDataType"] = "JSON"
        params["oFuzzyBuffer"] = "0"
        params["oIsOnlyFullMatch"] = "false"
        if (includeV40Fields) {
            params["oIsSupportPast"] = "true"
            params["oIsShowCodeBase"] = "false"
        }
        params["oIsLockCounty"] = "true"
        params["oIsLockTown"] = "true"
        params["oIsLockVillage"] = "false"
        params["oIsLockRoadSection"] = "false"
        params["oIsLockLane"] = "false"
        params["oIsLockAlley"] = "false"
        params["oIsLockArea"] = "false"
        params["oIsSameNumber_SubNumber"] = "true"
        params["oCanIgnoreVillage"] = "true"
        params["oCanIgnoreNeighborhood"] = "true"
        params["oReturnMaxCount"] = "1"
        return params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, Charsets.UTF_8)}=${URLEncoder.encode(v, Charsets.UTF_8)}"
        }
    }

    private fun invokeEndpoint(url: String, formBody: String): Pair<String?, String?> {
        if (fetcher != null) {
            val res = fetcher.invoke(url, formBody)
            return res to if (res == null) "null response" else null
        }
        val wait = minIntervalMs - (System.currentTimeMillis() - lastRequestMs)
        if (wait > 0) Thread.sleep(wait)
        lastRequestMs = System.currentTimeMillis()
        return try {
            val builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            if (referer.isNotBlank()) builder.header("Referer", referer)
            val req = builder.POST(HttpRequest.BodyPublishers.ofString(formBody, Charsets.UTF_8)).build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            if (resp.statusCode() == 200) {
                resp.body() to null
            } else {
                null to "HTTP ${resp.statusCode()}"
            }
        } catch (e: Exception) {
            null to (e::class.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }

    internal companion object {
        const val DEFAULT_REFERER = "https://www.tgos.tw/TGOS/Addr/addrCompare"
        private const val ENDPOINT_V40 = "https://addr.tgos.tw/addrws/v40/QueryAddr.asmx/QueryAddr"
        private const val ENDPOINT_V30 = "https://addr.tgos.tw/addrws/v30/QueryAddr.asmx/QueryAddr"
        private val XML_STRING_RE = Regex("<string[^>]*>([\\s\\S]*?)</string>", RegexOption.IGNORE_CASE)

        fun extractErrorSummary(raw: String): String? {
            val jsonText = unwrapJsonPayload(raw)
            if (jsonText != null) {
                val root = runCatching { DatasetJson.parseToJsonElement(jsonText) as? JsonObject }.getOrNull()
                val info = root?.get("Info")
                if (info is JsonArray) {
                    val msg = info.firstOrNull()?.let { it as? JsonObject }
                        ?.get("InAddress")?.jsonPrimitive?.contentOrNull
                    val err = info.firstOrNull()?.let { it as? JsonObject }
                        ?.entries?.firstOrNull { it.key.contains("Err", ignoreCase = true) || it.key.contains("Msg", ignoreCase = true) }
                        ?.value?.jsonPrimitive?.contentOrNull
                    if (!err.isNullOrBlank()) return err.take(120)
                    if (!msg.isNullOrBlank()) return "Info present without AddressList"
                }
            }
            val stripped = raw.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
            return stripped.takeIf { it.isNotEmpty() }?.take(120)
        }

        fun parseQueryAddrResponse(id: String, raw: String, queryAddress: String = ""): TgosHit? {
            val jsonText = unwrapJsonPayload(raw) ?: return null
            val root = runCatching { DatasetJson.parseToJsonElement(jsonText) as? JsonObject }.getOrNull() ?: return null
            val list = root["AddressList"] as? JsonArray ?: return null
            val first = list.firstOrNull()?.let { it as? JsonObject } ?: return null
            val x = first["X"]?.jsonPrimitive?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() } ?: return null
            val y = first["Y"]?.jsonPrimitive?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() } ?: return null
            val fullAddr = first["FULL_ADDR"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val isPenghu = "澎湖" in fullAddr || "澎湖" in queryAddress
            val pos = TgosCsv.toWgs84(x, y, isPenghu = isPenghu) ?: return null
            if (!CountyBounds.TAIWAN.contains(pos.lat, pos.lon)) return null
            return TgosHit(id = id, pos = pos, matchedAddress = fullAddr)
        }

        private fun looksLikeValidEmptyResult(raw: String): Boolean {
            val jsonText = unwrapJsonPayload(raw) ?: return false
            val root = runCatching { DatasetJson.parseToJsonElement(jsonText) as? JsonObject }.getOrNull() ?: return false
            return root["AddressList"] is JsonArray
        }

        private fun unwrapJsonPayload(raw: String): String? {
            val trimmed = raw.trim().trimStart('\uFEFF')
            if (trimmed.startsWith("{")) return trimmed
            val inner = XML_STRING_RE.find(trimmed)?.groupValues?.get(1)?.trim() ?: return null
            val unescaped = inner
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
            return unescaped.trim().takeIf { it.startsWith("{") }
        }
    }
}

