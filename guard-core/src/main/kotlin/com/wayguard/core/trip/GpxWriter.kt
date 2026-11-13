package com.wayguard.core.trip

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Exports a recorded trip as standard GPX 1.1 XML (`<wpt>` for camera/section events, `<trk>` for GPS path).
 */
object GpxWriter {

    fun write(detail: TripDetail): String {
        val s = detail.summary
        val sb = StringBuilder(4096)
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append(
            """<gpx version="1.1" creator="WayGuard" xmlns="http://www.topografix.com/GPX/1/1">"""
        ).append('\n')
        sb.append("  <metadata>\n")
        sb.append("    <name>").append(escapeXml(tripTitle(s))).append("</name>\n")
        sb.append("    <time>").append(formatIso(s.startedAtMs)).append("</time>\n")
        sb.append("  </metadata>\n")

        for (e in detail.events) {
            sb.append(String.format(Locale.US, """  <wpt lat="%.6f" lon="%.6f">""", e.lat, e.lon)).append('\n')
            sb.append("    <time>").append(formatIso(e.tMs)).append("</time>\n")
            sb.append("    <name>").append(escapeXml(eventLabel(e))).append("</name>\n")
            val desc = eventDescription(e)
            if (desc.isNotBlank()) {
                sb.append("    <desc>").append(escapeXml(desc)).append("</desc>\n")
            }
            sb.append("  </wpt>\n")
        }

        sb.append("  <trk>\n")
        sb.append("    <name>").append(escapeXml(tripTitle(s))).append("</name>\n")
        sb.append("    <trkseg>\n")
        for (p in detail.points) {
            sb.append(String.format(Locale.US, """      <trkpt lat="%.6f" lon="%.6f">""", p.lat, p.lon)).append('\n')
            sb.append("        <time>").append(formatIso(p.tMs)).append("</time>\n")
            sb.append("      </trkpt>\n")
        }
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }

    private fun tripTitle(s: TripSummary): String =
        s.destination?.takeIf { it.isNotBlank() }?.let { "WayGuard 旅程（往 $it）" } ?: "WayGuard 旅程 ${s.id}"

    private fun eventLabel(e: TripEvent): String = when (e.type) {
        TripEventType.APPROACH -> "測速提醒"
        TripEventType.NEAR -> "近距提醒"
        TripEventType.OVERSPEED -> "超速警告"
        TripEventType.PASS -> "已通過測速點"
        TripEventType.SECTION_ENTER -> "進入區間測速"
        TripEventType.SECTION_OVER -> "區間均速超速"
        TripEventType.SECTION_EXIT -> "離開區間測速"
    }

    private fun eventDescription(e: TripEvent): String {
        val parts = ArrayList<String>()
        e.camera?.address?.takeIf { it.isNotBlank() }?.let { parts += it }
        e.limitKmh?.let { parts += "速限 $it km/h" }
        parts += String.format(Locale.US, "車速 %.0f km/h", e.speedKmh)
        e.sectionAvgKmh?.let { parts += String.format(Locale.US, "均速 %.1f km/h", it) }
        return parts.joinToString("｜")
    }

    private fun formatIso(epochMs: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMs))

    private fun escapeXml(s: String): String = buildString(s.length) {
        for (ch in s) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }
}

