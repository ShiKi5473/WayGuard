package com.wayguard.core.validate

import com.waymap.geo.LatLng
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class TgosRow(val id: String, val address: String)

data class TgosHit(val id: String, val pos: LatLng, val matchedAddress: String = "")

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

    /** Formats a CSV upload file (`id,address`) for TGOS batch geocoding. */
    fun formatUploadCsv(rows: List<TgosRow>): String = buildString {
        appendLine("id,address")
        for (r in rows) {
            append(csvEscape(r.id)).append(',').appendLine(csvEscape(r.address))
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

    /** Parses TGOS batch result CSV (`id`, `X`/`經度`/`橫坐標`, `Y`/`緯度`/`縱坐標`, optional address). */
    fun parseResults(csvText: String): Map<String, TgosHit> {
        val lines = csvText.lineSequence()
            .map { it.trimStart('\uFEFF').trimEnd('\r') }
            .filter { it.isNotBlank() }
            .toList()
        if (lines.size < 2) return emptyMap()

        val delim = detectDelimiter(lines.first())
        val headers = splitCsvLine(lines.first(), delim).map { it.trim() }

        val idIdx = findCol(headers, "id", "序號", "編號", "識別碼")
        val xIdx = findCol(headers, "x", "經度", "橫坐標", "lon", "lng", "twd97_x", "x坐標")
        val yIdx = findCol(headers, "y", "緯度", "縱坐標", "lat", "twd97_y", "y坐標")
        val addrIdx = findCol(headers, "matched_address", "比對地址", "門牌地址", "完整地址", "address", "地址")
        if (idIdx < 0 || xIdx < 0 || yIdx < 0) return emptyMap()

        val out = LinkedHashMap<String, TgosHit>()
        for (line in lines.drop(1)) {
            val cols = splitCsvLine(line, delim)
            val id = cols.getOrNull(idIdx)?.trim().orEmpty()
            val x = cols.getOrNull(xIdx)?.trim()?.toDoubleOrNull() ?: continue
            val y = cols.getOrNull(yIdx)?.trim()?.toDoubleOrNull() ?: continue
            if (id.isEmpty()) continue
            val addr = addrIdx.takeIf { it >= 0 }?.let { cols.getOrNull(it)?.trim() }.orEmpty()
            val pos = toWgs84(x, y, isPenghu = "澎湖" in addr) ?: continue
            if (!CountyBounds.TAIWAN.contains(pos.lat, pos.lon)) continue
            out[id] = TgosHit(id, pos, addr)
        }
        return out
    }

    internal fun toWgs84(x: Double, y: Double, isPenghu: Boolean = false): LatLng? = when {
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

