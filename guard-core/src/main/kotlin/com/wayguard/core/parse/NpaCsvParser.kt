package com.wayguard.core.parse

/**
 * One data row of the National Police Agency (警政署) "測速執法設置點" CSV.
 * Coordinates and limit stay as text here; [com.wayguard.core.dataset.DatasetBuilder]
 * validates them.
 */
data class NpaRow(
    /** 1-based line number in the source file, for reports. */
    val line: Int,
    val city: String,
    val region: String,
    val address: String,
    val dept: String,
    val branch: String,
    val lon: String,
    val lat: String,
    val direct: String,
    val limit: String,
)

/** A source row left out of the dataset; [id] is set once the row could be identified. */
data class RejectedRow(val line: Int, val reason: String, val raw: String, val id: String? = null)

data class CsvParseResult(val rows: List<NpaRow>, val rejected: List<RejectedRow>)

/**
 * Parses the NPA CSV:
 * `CityName,RegionName,Address,DeptNm,BranchNm,Longitude,Latitude,direct,limit`,
 * followed by a second, Chinese header line. Tolerates a UTF-8 BOM, CRLF, quoted
 * fields and unquoted commas inside the address or direction text (the coordinate
 * pair is located by value instead of by position).
 */
object NpaCsvParser {

    private const val EXPECTED_FIELDS = 9
    private val HEADER_FIRST_FIELDS = setOf("CityName", "設置縣市")

    fun parse(text: String): CsvParseResult {
        val rows = ArrayList<NpaRow>()
        val rejected = ArrayList<RejectedRow>()
        for ((line, fields) in records(text.removePrefix("﻿"))) {
            if (fields.all { it.isBlank() }) continue
            if (fields.first().trim() in HEADER_FIRST_FIELDS) continue
            val row = toRow(line, fields)
            if (row != null) rows += row
            else rejected += RejectedRow(line, "欄位數或經緯度位置無法判讀", fields.joinToString(","))
        }
        return CsvParseResult(rows, rejected)
    }

    /**
     * Maps fields to a row. With exactly 9 fields the layout is positional; otherwise
     * the (lon, lat) pair is found by value and the fields around it are rejoined.
     */
    private fun toRow(line: Int, raw: List<String>): NpaRow? {
        val f = raw.map { it.trim() }
        if (f.size < EXPECTED_FIELDS) return null
        val i = if (f.size == EXPECTED_FIELDS) 5 else findCoordinatePair(f) ?: return null
        // Need city, region, ≥1 address field, dept, branch before; direct, limit after.
        if (i < 5 || i + 3 > f.lastIndex) return null
        return NpaRow(
            line = line,
            city = f[0],
            region = f[1],
            address = f.subList(2, i - 2).joinToString(","),
            dept = f[i - 2],
            branch = f[i - 1],
            lon = f[i],
            lat = f[i + 1],
            direct = f.subList(i + 2, f.lastIndex).joinToString(","),
            limit = f.last(),
        )
    }

    /** Index of the first field of a (lon, lat) — or swapped (lat, lon) — pair in Taiwan. */
    private fun findCoordinatePair(f: List<String>): Int? {
        fun isLon(s: String) = s.toDoubleOrNull()?.let { it in 117.0..123.0 } == true
        fun isLat(s: String) = s.toDoubleOrNull()?.let { it in 20.0..27.0 } == true
        for (i in 5 until f.lastIndex - 2) {
            if ((isLon(f[i]) && isLat(f[i + 1])) || (isLat(f[i]) && isLon(f[i + 1]))) return i
        }
        return null
    }

    /** RFC 4180-style record reader: quotes may contain commas, quotes ("") and newlines. */
    internal fun records(text: String): List<Pair<Int, List<String>>> {
        val out = ArrayList<Pair<Int, List<String>>>()
        val fields = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var line = 1
        var recordLine = 1
        var i = 0
        fun endField() { fields += sb.toString(); sb.setLength(0) }
        fun endRecord() {
            endField()
            out += recordLine to fields.toList()
            fields.clear()
        }
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                inQuotes -> { if (c == '\n') line++; sb.append(c) }
                c == ',' -> endField()
                c == '\r' -> {}
                c == '\n' -> { endRecord(); line++; recordLine = line }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty() || fields.isNotEmpty()) endRecord()
        return out
    }
}
