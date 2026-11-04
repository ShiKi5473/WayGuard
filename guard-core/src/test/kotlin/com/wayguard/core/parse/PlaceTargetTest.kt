package com.wayguard.core.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceTargetTest {

    @Test
    fun extractsTheDestinationAfterTheLast往() {
        val cases = mapOf(
            "往大溪方向" to "大溪",
            "往桃園" to "桃園",
            "往桃園市區方向" to "桃園市區",
            "中山路上往桃園車站方向" to "桃園車站",
            "西濱路二段上往觀音方向" to "觀音",
            "復興路往市府方向" to "市府",
            "往高鐵方向(快、慢車道)" to "高鐵",
            "往高鐵方向（快、慢車道）" to "高鐵",
            "大金往烈嶼(區間測速)" to "烈嶼",
            "往台66快速道路方向" to "台66快速道路",
            "往下山" to "下山",
        )
        for ((text, expected) in cases) assertEquals(text, expected, PlaceTarget.of(text))
    }

    @Test
    fun textsWithoutADestinationGiveNull() {
        assertNull(PlaceTarget.of("南北雙向"))
        assertNull(PlaceTarget.of("往"))
        assertNull(PlaceTarget.of("往方向"))
        assertNull(PlaceTarget.of(""))
    }

    @Test
    fun keyTreats台and臺Alike() {
        assertEquals(PlaceTarget.key("台北市"), PlaceTarget.key("臺北市"))
    }
}
