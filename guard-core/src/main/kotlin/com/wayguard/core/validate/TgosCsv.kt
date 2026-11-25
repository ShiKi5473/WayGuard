package com.wayguard.core.validate

import com.waymap.geo.GeoUtils
import com.waymap.geo.LatLng
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class TgosRow(val id: String, val address: String)

/** One house-number location TGOS returned for a query. */
data class TgosCandidate(val pos: LatLng, val address: String)

data class TgosHit(
    val id: String,
    /** Centroid of [candidates]. */
    val pos: LatLng,
    val matchedAddress: String = "",
    /** Every location TGOS returned; fuzzy matching may return several (`;`-separated in the batch CSV). */
    val candidates: List<TgosCandidate> = listOf(TgosCandidate(pos, matchedAddress)),
    /** The address uploaded for this row (batch `Address` column), possibly corrected by hand; empty if unknown. */
    val queryAddress: String = "",
)

/** How well the address TGOS matched agrees with the house number queried. */
enum class TgosMatchQuality {
    /** Same road, house number and 之 sub-number. */
    EXACT,

    /** Same road and house number, different (or missing) 之 sub-number. */
    SAME_BASE,

    /** Several neighbouring numbers on the same road that bracket the queried one. */
    BRACKETED,

    /** The result carries no matched address (generic CSV): fit for verification only. */
    UNKNOWN,
}

/** A TGOS result that can be trusted for [pos]. */
data class TgosMatch(val pos: LatLng, val quality: TgosMatchQuality) {
    /** True when the position is pinned to the queried house number (not just any TGOS coordinate). */
    val pinsHouse: Boolean get() = quality != TgosMatchQuality.UNKNOWN
}

/**
 * Helpers for Taiwan TGOS batch house-number geocoding CSV export and result parsing
 * (supporting both WGS84 and TWD97 TM2 EPSG:3826 / EPSG:3825 coordinates).
 */
object TgosCsv {

    private val HOUSE_CUT_RE = Regex("^(.*?\\d+(?:之\\d+|[-－]\\d+)?號).*$")
    private val PARENS_RE = Regex("[(（][^()（）]*[)）]")

    /** Normalizes an NPA address into a clean postal query string for TGOS batch upload. */
    fun cleanAddressForTgos(city: String, district: String, rawAddress: String): String {
        val normCity = CountyBounds.normalizeCity(city)
        val normDist = CountyBounds.normalizeCity(district)
        var body = rawAddress.trim()
            .replace(PARENS_RE, "")
            .replace('台', '臺')
            .trim()
        HOUSE_CUT_RE.find(body)?.let { body = it.groupValues[1] }

        val prefix = buildString {
            if (normCity.isNotEmpty() && !body.startsWith(normCity)) append(normCity)
            if (normDist.isNotEmpty() && !body.contains(normDist)) append(normDist)
        }
        return "$prefix$body".trim()
    }

    /**
     * Header TGOS batch house-number matching requires verbatim: exactly these five columns, the
     * `Response_*` ones left empty for TGOS to fill in.
     */
    const val UPLOAD_HEADER = "id,Address,Response_Address,Response_X,Response_Y"

    /** Formats a CSV upload file ([UPLOAD_HEADER]) for TGOS batch geocoding. */
    fun formatUploadCsv(rows: List<TgosRow>): String = buildString {
        appendLine(UPLOAD_HEADER)
        for (r in rows) {
            append(csvEscape(r.id)).append(',').append(csvEscape(r.address)).appendLine(",,,")
        }
    }

    /**
     * Converts TWD97 TM2 coordinates ([x] easting, [y] northing in meters) to WGS84.
     * Uses central meridian 121°E (EPSG:3826) by default, or 119°E (EPSG:3825) when [penghu] is true.
     */
    fun twd97ToWgs84(x: Double, y: Double, penghu: Boolean = false): LatLng {
        val a = 6378137.0
        val f = 1.0 / 298.257222101
        val b = a * (1.0 - f)
        val e2 = (a * a - b * b) / (a * a)
        val ePrime2 = e2 / (1.0 - e2)
        val k0 = 0.9999
        val dx = 250000.0
        val lon0 = Math.toRadians(if (penghu) 119.0 else 121.0)

        val xAdj = x - dx
        val m = y / k0
        val mu = m / (a * (1.0 - e2 / 4.0 - 3.0 * e2 * e2 / 64.0 - 5.0 * e2.pow(3) / 256.0))
        val e1 = (1.0 - sqrt(1.0 - e2)) / (1.0 + sqrt(1.0 - e2))

        val j1 = 3.0 * e1 / 2.0 - 27.0 * e1.pow(3) / 32.0
        val j2 = 21.0 * e1 * e1 / 16.0 - 55.0 * e1.pow(4) / 32.0
        val j3 = 151.0 * e1.pow(3) / 96.0
        val j4 = 1097.0 * e1.pow(4) / 512.0

        val fp = mu + j1 * sin(2.0 * mu) + j2 * sin(4.0 * mu) + j3 * sin(6.0 * mu) + j4 * sin(8.0 * mu)
        val sinFp = sin(fp)
        val cosFp = cos(fp)
        val tanFp = tan(fp)

        val c1 = ePrime2 * cosFp * cosFp
        val t1 = tanFp * tanFp
        val r1 = a * (1.0 - e2) / (1.0 - e2 * sinFp * sinFp).pow(1.5)
        val n1 = a / sqrt(1.0 - e2 * sinFp * sinFp)
        val d = xAdj / (n1 * k0)

        val q1 = n1 * tanFp / r1
        val q2 = d * d / 2.0
        val q3 = (5.0 + 3.0 * t1 + 10.0 * c1 - 4.0 * c1 * c1 - 9.0 * ePrime2) * d.pow(4) / 24.0
        val q4 = (61.0 + 90.0 * t1 + 298.0 * c1 + 45.0 * t1 * t1 - 3.0 * c1 * c1 - 252.0 * ePrime2) * d.pow(6) / 720.0
        val latRad = fp - q1 * (q2 - q3 + q4)

        val q5 = d
        val q6 = (1.0 + 2.0 * t1 + c1) * d.pow(3) / 6.0
        val q7 = (5.0 - 2.0 * c1 + 28.0 * t1 - 3.0 * c1 * c1 + 8.0 * ePrime2 + 24.0 * t1 * t1) * d.pow(5) / 120.0
        val lonRad = lon0 + (q5 - q6 + q7) / cosFp

        return LatLng(latRad * 180.0 / PI, lonRad * 180.0 / PI)
    }

    /**
     * Parses TGOS batch result CSV: the batch format (`id,Address,Response_Address,Response_X,Response_Y`)
     * or generic `id` + `X`/`經度`/`橫坐標` + `Y`/`緯度`/`縱坐標` (+ optional address) columns.
     * Rows TGOS could not match (empty `Response_X`/`Response_Y`) are skipped. Fuzzy matching may
     * return several `;`-separated candidates; each becomes one of [TgosHit.candidates].
     */
    fun parseResults(csvText: String): Map<String, TgosHit> {
        val lines = csvText.lineSequence()
            .map { it.trimStart('\uFEFF').trimEnd('\r') }
            .filter { it.isNotBlank() }
            .toList()
        if (lines.size < 2) return emptyMap()

        val delim = detectDelimiter(lines.first())
        val headers = splitCsvLine(lines.first(), delim).map { it.trim() }

        val idIdx = findCol(headers, "id", "序號", "編號", "識別碼")
        val xIdx = findCol(headers, "response_x", "x", "經度", "橫坐標", "lon", "lng", "twd97_x", "x坐標")
        val yIdx = findCol(headers, "response_y", "y", "緯度", "縱坐標", "lat", "twd97_y", "y坐標")
        // Prefer the address TGOS matched over the one we uploaded.
        val addrIdx = findCol(headers, "response_address", "matched_address", "比對地址", "門牌地址", "完整地址", "address", "地址")
        // The uploaded query (batch `Address` column), kept apart from the matched address.
        val queryIdx = headers.indexOfFirst { it.equals("address", ignoreCase = true) }.takeIf { it != addrIdx } ?: -1
        if (idIdx < 0 || xIdx < 0 || yIdx < 0) return emptyMap()

        val out = LinkedHashMap<String, TgosHit>()
        for (line in lines.drop(1)) {
            val cols = splitCsvLine(line, delim)
            val id = cols.getOrNull(idIdx)?.trim().orEmpty()
            if (id.isEmpty()) continue
            val xs = cols.getOrNull(xIdx)?.split(';').orEmpty()
            val ys = cols.getOrNull(yIdx)?.split(';').orEmpty()
            if (xs.isEmpty() || xs.size != ys.size) continue
            val addr = addrIdx.takeIf { it >= 0 }?.let { cols.getOrNull(it)?.trim() }.orEmpty()
            val addrs = addr.split(';').map { it.trim() }.takeIf { it.size == xs.size }
            val candidates = xs.indices.mapNotNull { i ->
                val x = xs[i].trim().toDoubleOrNull() ?: return@mapNotNull null
                val y = ys[i].trim().toDoubleOrNull() ?: return@mapNotNull null
                val candAddr = addrs?.get(i) ?: if (xs.size == 1) addr else ""
                val pos = toWgs84(x, y, isPenghu = "澎湖" in addr) ?: return@mapNotNull null
                if (!CountyBounds.TAIWAN.contains(pos.lat, pos.lon)) return@mapNotNull null
                TgosCandidate(pos, candAddr)
            }
            // Drop the whole row when any candidate is unreadable: a partial set could mislead.
            if (candidates.size != xs.size) continue
            val query = if (queryIdx >= 0) cols.getOrNull(queryIdx)?.trim().orEmpty() else ""
            out[id] = TgosHit(id, centroid(candidates.map { it.pos }), addr, candidates, query)
        }
        return out
    }

    /**
     * The address to [assess] [hit] against: its uploaded query when that was corrected by hand
     * (`宜18-2線溪洲路150號` → `溪洲路150號`), provided its road and house number both still appear
     * in [rawAddress], the camera's own NPA address; otherwise [cleaned]. A query naming another road
     * or number (e.g. pasted onto the wrong row) is never trusted.
     */
    fun queryFor(hit: TgosHit, city: String, district: String, rawAddress: String, cleaned: String): String {
        val query = hit.queryAddress
        if (query.isBlank() || query == cleaned) return cleaned
        val key = houseKey(query, city, district) ?: return cleaned
        val raw = normalizeHouseText(rawAddress)
        val number = if (key.sub != null) "${key.number}之${key.sub}號" else "${key.number}號"
        val numberInRaw = Regex("(?<!\\d)" + Regex.escape(number)).containsMatchIn(raw)
        return if (key.road in raw && numberInRaw) query else cleaned
    }

    /**
     * Keeps a TGOS result only when it really points at [queryAddress]'s house number: every
     * candidate must be on the same road (lane/section included) and either carry the same number
     * or bracket it, all within [LocationVerdict.TGOS_CLUSTER_MAX_M] of each other. Fuzzy matching
     * otherwise falls back to some other number (`中華路一段406號` → `218號`) or road, which is
     * a different place. [city]/[district] are the camera's, stripped from both addresses.
     */
    fun assess(hit: TgosHit, city: String, district: String, queryAddress: String): TgosMatch? {
        val cands = hit.candidates
        if (cands.isEmpty()) return null
        if (cands.all { it.address.isBlank() }) {
            val pts = cands.map { it.pos }
            return if (spreadM(pts) <= LocationVerdict.TGOS_CLUSTER_MAX_M) {
                TgosMatch(centroid(pts), TgosMatchQuality.UNKNOWN)
            } else {
                null
            }
        }
        val q = houseKey(queryAddress, city, district) ?: return null
        val keys = cands.map { houseKey(it.address, city, district) ?: return null }
        if (keys.any { it.road != q.road }) return null

        val sameBase = cands.indices.filter { keys[it].number == q.number }
        val exact = sameBase.filter { keys[it].sub == q.sub }
        val (used, quality) = when {
            exact.isNotEmpty() -> exact to TgosMatchQuality.EXACT
            sameBase.isNotEmpty() -> sameBase to TgosMatchQuality.SAME_BASE
            cands.size >= 2 && q.number in keys.minOf { it.number }..keys.maxOf { it.number } ->
                cands.indices.toList() to TgosMatchQuality.BRACKETED
            else -> return null
        }
        val pts = used.map { cands[it].pos }
        if (spreadM(pts) > LocationVerdict.TGOS_CLUSTER_MAX_M) return null
        return TgosMatch(centroid(pts), quality)
    }

    internal data class HouseKey(val road: String, val number: Int, val sub: Int?)

    private val VILLAGE_RE = Regex("^[^\\d]{1,8}?[村里]\\d+鄰")
    private val HOUSE_NO_RE = Regex("(\\d+)(?:之(\\d+))?號")
    private val SUB_AFTER_RE = Regex("號之(\\d+)")
    private val SECTION_NO_RE = Regex("(\\d+)段")
    private val NEAR_RE = Regex("近(?=\\d)")
    private const val CN_DIGITS = "一二三四五六七八九"

    /**
     * Road (with section/lane) + house number of [address], after dropping [city], [district] and
     * TGOS's `XX里N鄰`. `3段`→`三段`, `18-1號`/`18號之1`→`18之1號`, `近100號`→`100號`.
     */
    internal fun houseKey(address: String, city: String, district: String): HouseKey? {
        val s = normalizeHouseText(address)
            .removePrefix(normalizeHouseText(city))
            .removePrefix(normalizeHouseText(district))
            .replaceFirst(VILLAGE_RE, "")
        val m = HOUSE_NO_RE.find(s) ?: return null
        val road = s.substring(0, m.range.first)
        if (road.isEmpty()) return null
        val number = m.groupValues[1].toIntOrNull() ?: return null
        val sub = m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull()
        return HouseKey(road, number, sub)
    }

    private fun normalizeHouseText(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch in '０'..'９' -> sb.append('0' + (ch - '０'))
                ch.isWhitespace() -> Unit
                ch == '台' -> sb.append('臺')
                ch == '-' || ch == '－' || ch == '‐' || ch == '—' -> sb.append('之')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
            .replace(SUB_AFTER_RE, "之$1號")
            .replace(SECTION_NO_RE) { "${chineseNumber(it.groupValues[1].toInt())}段" }
            .replace(NEAR_RE, "")
    }

    private fun chineseNumber(n: Int): String = when (n) {
        in 1..9 -> CN_DIGITS[n - 1].toString()
        10 -> "十"
        in 11..19 -> "十" + CN_DIGITS[n - 11]
        else -> n.toString()
    }

    private fun centroid(pts: List<LatLng>): LatLng =
        LatLng(pts.sumOf { it.lat } / pts.size, pts.sumOf { it.lon } / pts.size)

    private fun spreadM(pts: List<LatLng>): Double {
        var max = 0.0
        for (i in pts.indices) for (j in i + 1 until pts.size) {
            max = maxOf(max, GeoUtils.haversine(pts[i], pts[j]))
        }
        return max
    }

    fun toWgs84(x: Double, y: Double, isPenghu: Boolean = false): LatLng? = when {
        // Already WGS84 lon/lat
        x in 118.0..123.0 && y in 21.0..26.5 -> LatLng(y, x)
        // TWD97 TM2 meters (typically X ~100,000..400,000, Y ~2,400,000..2,850,000)
        x in 50_000.0..500_000.0 && y in 2_200_000.0..3_000_000.0 -> twd97ToWgs84(x, y, isPenghu)
        else -> null
    }

    internal fun detectDelimiter(header: String): Char = when {
        '\t' in header -> '\t'
        ';' in header && ',' !in header -> ';'
        else -> ','
    }

    internal fun splitCsvLine(line: String, delim: Char): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        cur.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                ch == delim && !inQuotes -> {
                    out += cur.toString()
                    cur.clear()
                }
                else -> cur.append(ch)
            }
            i++
        }
        out += cur.toString()
        return out
    }

    private fun findCol(headers: List<String>, vararg names: String): Int {
        val lower = headers.map { it.lowercase() }
        for (n in names) {
            val idx = lower.indexOf(n.lowercase())
            if (idx >= 0) return idx
        }
        for (n in names) {
            val idx = lower.indexOfFirst { n.lowercase() in it }
            if (idx >= 0) return idx
        }
        return -1
    }

    private fun csvEscape(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"${s.replace("\"", "\"\"")}\"" else s
}

