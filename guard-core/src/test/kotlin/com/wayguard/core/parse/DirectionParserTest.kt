package com.wayguard.core.parse

import com.wayguard.core.model.CameraKind.RED_LIGHT
import com.wayguard.core.model.CameraKind.SECTION
import com.wayguard.core.model.CameraKind.SPEED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every `direct` value seen in the NPA dataset, with its expected meaning. */
class DirectionParserTest {

    private val any = emptyList<Int>()

    /** text → expected headings (empty = every direction). */
    private val headingTable: List<Pair<String, List<Int>>> = listOf(
        // Both ways.
        "南北雙向" to any, "東西雙向" to any, "雙向" to any, "南北" to any, "東西向" to any,
        "南北向" to any, "北南雙向" to any, "多向" to any, "南北相向" to any, "雙向測速" to any,
        // One way.
        "北上方向" to listOf(0), "南下方向" to listOf(180), "北上" to listOf(0), "南下" to listOf(180),
        "北上車道" to listOf(0), "南下車道" to listOf(180),
        "北向" to listOf(0), "南向" to listOf(180), "東向" to listOf(90), "西向" to listOf(270),
        "北向南" to listOf(180), "南向北" to listOf(0), "東向西" to listOf(270), "西向東" to listOf(90),
        "北往南" to listOf(180), "南往北" to listOf(0), "東往西" to listOf(270), "西往東" to listOf(90),
        "往北" to listOf(0), "往南" to listOf(180), "往東" to listOf(90), "往西" to listOf(270),
        // Diagonal.
        "東南向西北" to listOf(315), "西北向東南" to listOf(135),
        "東北向西南" to listOf(225), "西南向東北" to listOf(45),
        // With notes.
        "雙向兼闖紅燈" to any, "雙向區間測速" to any, "雙向(區間測速)" to any,
        "南北雙向(區間測速)" to any, "東西雙向(區間測速)" to any, "南北雙向兼南下闖紅燈" to any,
        "東向(區間測速)" to listOf(90), "西向(區間測速)" to listOf(270), "北向(區間測速)" to listOf(0),
        "南北雙向(超速闖紅燈)" to any, "東西雙向(超速、闖紅燈)" to any,
        "南向北(超速闖紅燈)" to listOf(0), "西向東(超速闖紅燈)" to listOf(90), "東向西(超速闖紅燈)" to listOf(270),
        "北往南(雙向)" to any, "東往西(雙向)" to any, "西往東(雙向)" to any,
        "北向南 (區間測速)" to listOf(180), "北向南（區間測速）" to listOf(180),
        "南向北(區間測速) 北向南(區間測速)" to listOf(0, 180),
    )

    @Test
    fun knownTextsMapToHeadings() {
        for ((text, expected) in headingTable) {
            val p = DirectionParser.parse(text)
            assertEquals("headings of '$text'", expected, p.headings)
            assertTrue("'$text' should be understood", p.understood)
            assertTrue(p.limitsByHeading.isEmpty())
        }
    }

    @Test
    fun placeNamesAreNotUnderstoodAndAlertBothWays() {
        for (text in listOf("往下山", "往市區", "往台北市", "烈嶼往大金(區間測速)", "大金往烈嶼(區間測速)", "", "  ")) {
            val p = DirectionParser.parse(text)
            assertFalse("'$text' should not be understood", p.understood)
            assertEquals("'$text'", any, p.headings)
        }
    }

    @Test
    fun notesAddKinds() {
        assertEquals(setOf(SPEED), DirectionParser.parse("南北雙向").kinds)
        assertEquals(setOf(SPEED, RED_LIGHT), DirectionParser.parse("雙向兼闖紅燈").kinds)
        assertEquals(setOf(SPEED, SECTION), DirectionParser.parse("東向(區間測速)").kinds)
        assertEquals(setOf(SPEED, RED_LIGHT), DirectionParser.parse("東西雙向(超速、闖紅燈)").kinds)
        assertEquals(setOf(SPEED, SECTION), DirectionParser.parse("烈嶼往大金(區間測速)").kinds)
    }

    @Test
    fun perDirectionLimitsSplitByHeading() {
        assertEquals(mapOf(180 to 60, 0 to 70), DirectionParser.parse("南向60北向70").limitsByHeading)
        assertEquals(mapOf(180 to 50, 0 to 60), DirectionParser.parse("南向50北向60").limitsByHeading)
        val p = DirectionParser.parse("南向70北向60")
        assertEquals(mapOf(180 to 70, 0 to 60), p.limitsByHeading)
        assertEquals(setOf(180, 0), p.headings.toSet())
    }
}
