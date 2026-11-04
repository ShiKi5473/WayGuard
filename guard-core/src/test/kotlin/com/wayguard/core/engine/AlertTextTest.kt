package com.wayguard.core.engine

import com.wayguard.core.model.CameraKind
import com.wayguard.core.model.Section
import com.wayguard.core.model.SpeedCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlertTextTest {

    private val cam = SpeedCamera("c", 25.0, 121.5, 60, listOf(0))

    @Test
    fun approachRoundsDistanceAndNamesKindAndLimit() {
        assertEquals("前方500公尺，測速照相，速限60", AlertText.speech(AlertEvent(AlertType.APPROACH, cam, 487.0, 60.0)))
        val red = cam.copy(kinds = setOf(CameraKind.SPEED, CameraKind.RED_LIGHT), limitKmh = null)
        assertEquals("前方300公尺，測速兼闖紅燈照相", AlertText.speech(AlertEvent(AlertType.APPROACH, red, 310.0, 40.0)))
    }

    @Test
    fun nearOverspeedAndPassed() {
        assertEquals("注意，測速照相就在前方，速限60", AlertText.speech(AlertEvent(AlertType.NEAR, cam, 140.0, 60.0)))
        assertEquals("超速，速限60，請減速", AlertText.speech(AlertEvent(AlertType.OVERSPEED, cam, 140.0, 80.0)))
        assertNull(AlertText.speech(AlertEvent(AlertType.PASSED, cam, 40.0, 60.0)))
    }

    @Test
    fun userKindsWinOverOfficialOnes() {
        assertEquals("流動測速", AlertText.kindName(cam.copy(kinds = setOf(CameraKind.MOBILE))))
        assertEquals("區間測速", AlertText.kindName(cam.copy(kinds = setOf(CameraKind.SPEED, CameraKind.SECTION))))
    }

    @Test
    fun sectionTexts() {
        val s = Section("s", "x", 25.0, 121.5, 25.02, 121.5, 2250.0, 90)
        val st = SectionStatus(s, 0.0, 0.0, null, 2250.0, 90.0, false)
        assertEquals("進入區間測速，速限90，全長2.3公里", AlertText.speech(SectionEvent(SectionEventType.ENTER, st)))
        assertEquals(
            "離開區間測速，平均時速84",
            AlertText.speech(SectionEvent(SectionEventType.COMPLETE, st.copy(averageKmh = 84.4))),
        )
    }
}
