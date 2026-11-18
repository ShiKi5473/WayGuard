package com.wayguard.ui

import com.waymap.geo.GeoUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.log2
import kotlin.math.roundToInt

private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
private val TIME_ONLY = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
private val HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/** ISO-8601 instant → local "yyyy-MM-dd HH:mm", or the input if unparseable. */
fun formatIsoLocal(iso: String): String = runCatching { DATE_TIME.format(Instant.parse(iso)) }.getOrDefault(iso)

fun formatEpochLocal(ms: Long): String = if (ms <= 0) "尚未檢查" else DATE_TIME.format(Instant.ofEpochMilli(ms))

fun formatTripTime(ms: Long): String = if (ms <= 0) "--" else DATE_TIME.format(Instant.ofEpochMilli(ms))

fun formatTimeOfDay(ms: Long): String = if (ms <= 0) "--" else TIME_ONLY.format(Instant.ofEpochMilli(ms))

/** Local "HH:mm". */
fun formatHourMinute(ms: Long): String = if (ms <= 0) "--" else HOUR_MINUTE.format(Instant.ofEpochMilli(ms))

/** "1:05:09" / "5:09" for an elapsed duration. */
fun formatElapsed(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0L) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
}

fun formatDuration(ms: Long): String {
    val totalMin = (ms / 60_000L).coerceAtLeast(0L)
    val h = totalMin / 60L
    val m = totalMin % 60L
    return when {
        h > 0L -> "${h} 小時 ${m} 分"
        totalMin > 0L -> "${m} 分鐘"
        else -> "${(ms / 1000L).coerceAtLeast(0L)} 秒"
    }
}

/** "350 m" / "1.2 km". */
fun formatDistance(m: Double): String =
    if (m < 1000) "${m.roundToInt()} m" else "${"%.1f".format(m / 1000)} km"

fun formatBytes(bytes: Long): String = when {
    bytes < 0L -> "--"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    else -> "${"%.1f".format(Locale.ROOT, bytes / (1024.0 * 1024.0))} MB"
}

private val COMPASS_NAMES = listOf("北", "東北", "東", "東南", "南", "西南", "西", "西北")

/** 0° → "往北"; empty → "雙向". */
fun formatHeadings(headings: List<Int>): String =
    if (headings.isEmpty()) "雙向／不限方向"
    else headings.joinToString("、") { "往" + COMPASS_NAMES[(GeoUtils.normalizeBearing(it) + 22) / 45 % 8] }

/**
 * 跟隨模式下依速度決定的地圖縮放：讓「首次警示距離」剛好落在游標（畫面中心）與畫面上緣之間。
 * 台灣緯度下每 dp 約 70 914 / 2^z 公尺，可用的半個畫面高度約 400 dp，
 * 故 z = log2(70 914 * 400 / (approachM * 1.1)) ≈ log2(28 000 000 / (approachM * 1.1))；限制在 14.0–17.5。
 */
fun followZoomFor(approachM: Double): Double = log2(28_000_000.0 / (approachM * 1.1)).coerceIn(14.0, 17.5)
