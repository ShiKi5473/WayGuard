package com.wayguard

import com.wayguard.core.model.CameraKind
import com.wayguard.core.model.SpeedCamera
import com.wayguard.settings.GuardSettings
import com.wayguard.ui.formatBytes
import com.wayguard.ui.formatDistance
import com.wayguard.ui.formatHeadings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {

    @Test
    fun headingsReadAsCompassDirections() {
        assertEquals("雙向／不限方向", formatHeadings(emptyList()))
        assertEquals("往北", formatHeadings(listOf(0)))
        assertEquals("往西北", formatHeadings(listOf(315)))
        assertEquals("往南、往北", formatHeadings(listOf(180, 0)))
        assertEquals("往北", formatHeadings(listOf(350)))
    }

    @Test
    fun distances() {
        assertEquals("350 m", formatDistance(349.6))
        assertEquals("1.2 km", formatDistance(1234.0))
    }

    @Test
    fun bytesFormatting() {
        assertEquals("512 B", formatBytes(512L))
        assertEquals("10 KB", formatBytes(10 * 1024L))
        assertEquals("18.5 MB", formatBytes((18.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun settingsMapToAlertConfig() {
        val cfg = GuardSettings(alertSpeed = false, alertUser = false, approachMidM = 450).alertConfig()
        assertEquals(450.0, cfg.approachMidM, 0.0)
        assertFalse(CameraKind.SPEED in cfg.enabledKinds)
        assertFalse(CameraKind.MOBILE in cfg.enabledKinds)
        assertTrue(CameraKind.RED_LIGHT in cfg.enabledKinds)
        // A speed+red-light camera still alerts via its red-light kind.
        val cam = SpeedCamera("c", 25.0, 121.5, kinds = setOf(CameraKind.SPEED, CameraKind.RED_LIGHT))
        assertTrue(cam.kinds.any { it in cfg.enabledKinds })
    }
}
