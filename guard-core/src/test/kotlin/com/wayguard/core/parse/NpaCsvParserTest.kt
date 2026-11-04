package com.wayguard.core.parse

import org.junit.Assert.assertEquals
import org.junit.Test

class NpaCsvParserTest {

    private fun sample(): String =
        javaClass.getResourceAsStream("/npa-sample.csv")!!.readBytes().toString(Charsets.UTF_8)

    @Test
    fun parsesSampleSkippingBomAndBothHeaders() {
        val r = NpaCsvParser.parse(sample())
        assertEquals(11, r.rows.size)
        assertEquals("金門縣", r.rows.first().city)
        assertEquals(1, r.rejected.size)
        assertEquals(14, r.rejected.single().line)
    }

    @Test
    fun unquotedCommaInAddressIsRejoinedAroundTheCoordinates() {
        val row = NpaCsvParser.parse(sample()).rows.single { it.direct == "往東" }
        assertEquals("園區一路,近竹村一路口", row.address)
        assertEquals("新竹市警察局", row.dept)
        assertEquals("第三分局", row.branch)
        assertEquals("121.00500", row.lon)
        assertEquals("24.78200", row.lat)
        assertEquals("50", row.limit)
    }

    @Test
    fun quotedCommaStaysInOneField() {
        val row = NpaCsvParser.parse(sample()).rows.single { it.direct == "往西" }
        assertEquals("園區二路,近工業東路", row.address)
        assertEquals("121.01000", row.lon)
    }

    @Test
    fun recordsHandleEscapedQuotesNewlinesAndCrlf() {
        val recs = NpaCsvParser.records("a,\"b \"\"q\"\"\",c\r\n\"multi\nline\",x\r\nlast")
        assertEquals(listOf("a", "b \"q\"", "c"), recs[0].second)
        assertEquals(listOf("multi\nline", "x"), recs[1].second)
        assertEquals(2, recs[1].first)
        assertEquals(4, recs[2].first)
        assertEquals(listOf("last"), recs[2].second)
    }
}
