package com.wayguard.core.parse

import com.wayguard.core.model.CameraKind

/**
 * What a free-text `direct` (拍攝方向) value means.
 *
 * @property headings travel bearings enforced (0 = northbound); empty = every direction.
 * @property limitsByHeading per-direction limits when the text carries them
 *   (e.g. `南向60北向70`); the camera is then split into one entry per heading.
 * @property understood false when the text named no recognisable direction; the
 *   camera then alerts in every direction and the text is listed in the sync report.
 */
data class ParsedDirection(
    val headings: List<Int>,
    val kinds: Set<CameraKind>,
    val limitsByHeading: Map<Int, Int> = emptyMap(),
    val understood: Boolean = true,
)

/**
 * Interprets the NPA dataset's direction text. Conventions (Taiwan road usage):
 * - `X向` / `往X` / `X行` = travelling toward X: `北向` = northbound = 0°.
 * - `A向B` / `A往B` = from A toward B: `南向北` = 0°, `東南向西北` = 315°.
 * - `北上` = 0°, `南下` = 180°.
 * - `雙向`, `多向`, `相向`, `南北`, `東西向`… = every direction.
 * - `(區間測速)` adds [CameraKind.SECTION]; `闖紅燈` adds [CameraKind.RED_LIGHT].
 */
object DirectionParser {

    private val COMPASS = mapOf(
        "北" to 0, "東北" to 45, "北東" to 45, "東" to 90, "東南" to 135, "南東" to 135,
        "南" to 180, "西南" to 225, "南西" to 225, "西" to 270, "西北" to 315, "北西" to 315,
    )
    private const val D = "[東西南北]{1,2}"

    private val PER_HEADING_LIMIT = Regex("($D)向(\\d{2,3})")
    private val FROM_TO = Regex("($D)[向往]($D)")
    private val TOWARD = Regex("往($D)")
    private val HEADING_SUFFIX = Regex("($D)[向行]")
    private val AXIS_ONLY = Regex("^(南北|北南|東西|西東)向?$")
    private val PARENS = Regex("\\(.*?\\)")
    private val BOTH_WAYS = listOf("雙向", "多向", "相向")

    fun parse(raw: String): ParsedDirection {
        val s = normalize(raw)
        val kinds = buildSet {
            add(CameraKind.SPEED)
            if ("闖紅燈" in s) add(CameraKind.RED_LIGHT)
            if ("區間" in s) add(CameraKind.SECTION)
        }
        if (s.isEmpty()) return ParsedDirection(emptyList(), kinds, understood = false)

        // 南向60北向70: each direction has its own limit.
        val perHeading = PER_HEADING_LIMIT.findAll(s)
            .mapNotNull { m -> COMPASS[m.groupValues[1]]?.let { it to m.groupValues[2].toInt() } }
            .toMap()
        if (perHeading.size >= 2) return ParsedDirection(perHeading.keys.toList(), kinds, perHeading)

        // Bidirectional markers win, including inside notes: 北往南(雙向).
        if (BOTH_WAYS.any { it in s }) return ParsedDirection(emptyList(), kinds)

        // Notes in parentheses carry kinds only; directions come from the text between
        // them. Each segment is read on its own: 南向北(區間測速)北向南(區間測速).
        val segments = s.split(PARENS).filter { it.isNotEmpty() }
        if (segments.isNotEmpty() && segments.all { AXIS_ONLY.matches(it) }) {
            return ParsedDirection(emptyList(), kinds)
        }
        val headings = linkedSetOf<Int>()
        segments.forEach { headings += segmentHeadings(it) }
        return if (headings.isEmpty()) ParsedDirection(emptyList(), kinds, understood = false)
        else ParsedDirection(headings.toList(), kinds)
    }

    /** Headings named by one segment, trying the most specific form first. */
    private fun segmentHeadings(seg: String): Set<Int> {
        val out = linkedSetOf<Int>()
        // The destination is the part right after 向/往, so fall back to its first char.
        FROM_TO.findAll(seg).forEach { m -> compass(m.groupValues[2], first = true)?.let(out::add) }
        if (out.isNotEmpty()) return out
        TOWARD.findAll(seg).forEach { m -> compass(m.groupValues[1], first = true)?.let(out::add) }
        if (out.isNotEmpty()) return out
        if ("北上" in seg) out += 0
        if ("南下" in seg) out += 180
        if (out.isNotEmpty()) return out
        // The direction is the part right before 向/行, so fall back to its last char.
        HEADING_SUFFIX.findAll(seg).forEach { m -> compass(m.groupValues[1], first = false)?.let(out::add) }
        return out
    }

    /** Bearing of a 1–2 char direction; an unknown pair falls back to its adjacent char. */
    private fun compass(d: String, first: Boolean): Int? =
        COMPASS[d] ?: if (d.length == 2) COMPASS[if (first) d.take(1) else d.takeLast(1)] else null

    /** Half-width parentheses, no whitespace. */
    private fun normalize(raw: String): String =
        raw.replace('（', '(').replace('）', ')').filterNot { it.isWhitespace() }
}
