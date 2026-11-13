package com.wayguard.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TgosClientTest {

    @Test
    fun circuitBreakerStopsAfterThreeConsecutiveFailures() {
        var calls = 0
        val logs = ArrayList<String>()
        val client = TgosClient(
            appId = "test-id",
            apiKey = "test-key",
            fetcher = { _, _ ->
                calls++
                null
            },
            maxConsecutiveFailures = 3,
            logger = { logs += it },
        )

        assertNull(client.query("cam-1", "臺北市中山區松江路1號"))
        assertNull(client.query("cam-2", "臺北市中山區松江路2號"))
        assertNull(client.query("cam-3", "臺北市中山區松江路3號"))
        // 3 queries * 2 endpoints (v40 + v30) = 6 calls.
        assertEquals(6, calls)

        // 4th and 5th queries must be short-circuited without hitting fetcher.
        assertNull(client.query("cam-4", "臺北市中山區松江路4號"))
        assertNull(client.query("cam-5", "臺北市中山區松江路5號"))
        assertEquals(6, calls)
        assertTrue(logs.size == 2)
        assertTrue(logs[1].contains("熔斷"))
    }

    @Test
    fun validEmptyAddressListDoesNotTripCircuitBreaker() {
        var calls = 0
        val logs = ArrayList<String>()
        val client = TgosClient(
            appId = "test-id",
            apiKey = "test-key",
            fetcher = { url, _ ->
                calls++
                if ("/v40/" in url) {
                    """{"AddressList": []}"""
                } else {
                    null
                }
            },
            maxConsecutiveFailures = 2,
            logger = { logs += it },
        )

        repeat(4) { i ->
            assertNull(client.query("cam-$i", "臺北市中山區不存在路${i}號"))
        }
        // Only v40 called once per address; never fell back to v30 or tripped circuit breaker.
        assertEquals(4, calls)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun parsesXmlWrappedResponseAndResetsFailureCounter() {
        var step = 0
        val client = TgosClient(
            appId = "test-id",
            apiKey = "test-key",
            fetcher = { _, _ ->
                step++
                when (step) {
                    1, 2 -> null // 1st query fails (v40 + v30)
                    else -> """
                        <?xml version="1.0" encoding="utf-8"?>
                        <string xmlns="http://tgos.url.tw/">{&quot;AddressList&quot;:[{&quot;FULL_ADDR&quot;:&quot;臺北市信義區市府路1號&quot;,&quot;X&quot;:121.564099,&quot;Y&quot;:25.037542}]}</string>
                    """.trimIndent()
                }
            },
            maxConsecutiveFailures = 2,
            logger = {},
        )

        assertNull(client.query("cam-1", "臺北市中山區失敗路1號"))
        val hit = client.query("cam-2", "臺北市信義區市府路1號")
        assertNotNull(hit)
        assertEquals("cam-2", hit!!.id)
        assertEquals(25.037542, hit.pos.lat, 1e-6)
        assertEquals(121.564099, hit.pos.lon, 1e-6)
    }
}

