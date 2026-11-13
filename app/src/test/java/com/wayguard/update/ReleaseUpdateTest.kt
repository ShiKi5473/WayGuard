package com.wayguard.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseUpdateTest {

    @Test
    fun semverParsingAndComparison() {
        assertTrue(ReleasePicker.isNewer("v0.2.0", "0.1.0"))
        assertTrue(ReleasePicker.isNewer("V0.10.0", "0.2.0"))
        assertTrue(ReleasePicker.isNewer("1.0.0", "0.9.9"))
        assertTrue(ReleasePicker.isNewer("v0.2.0", "0.2.0-rc.1"))
        assertTrue(ReleasePicker.isNewer("0.2.0-rc.2", "0.2.0-rc.1"))
        assertTrue(ReleasePicker.isNewer("0.2.0-beta.10", "0.2.0-beta.2"))

        assertFalse(ReleasePicker.isNewer("v0.1.0", "0.1.0"))
        assertFalse(ReleasePicker.isNewer("v0.1.0+123", "0.1.0"))
        assertFalse(ReleasePicker.isNewer("v0.0.9", "0.1.0"))
        assertFalse(ReleasePicker.isNewer("v0.2.0-rc.1", "0.2.0"))
        assertFalse(ReleasePicker.isNewer("not-a-version", "0.1.0"))
        assertNull(AppVersion.parse("v"))
        assertNull(AppVersion.parse("v0.-1.0"))
    }

    @Test
    fun evaluateLatestReleasePicksReleaseApkAndDigest() {
        val hash = "a".repeat(64)
        val json = """
            {
              "tag_name": "v0.2.0",
              "name": "WayGuard v0.2.0",
              "body": null,
              "draft": false,
              "prerelease": false,
              "published_at": "2026-11-13T08:00:00Z",
              "html_url": "https://github.com/ShiKi5473/WayGuard/releases/tag/v0.2.0",
              "assets": [
                {
                  "name": "app-release-unsigned.apk",
                  "size": 1000,
                  "browser_download_url": "https://github.com/ShiKi5473/WayGuard/releases/download/v0.2.0/app-release-unsigned.apk"
                },
                {
                  "name": "WayGuard-v0.2.0-debug.apk",
                  "size": 2000,
                  "browser_download_url": "https://github.com/ShiKi5473/WayGuard/releases/download/v0.2.0/WayGuard-v0.2.0-debug.apk"
                },
                {
                  "name": "WayGuard-v0.2.0.apk",
                  "size": 18500000,
                  "browser_download_url": "https://github.com/ShiKi5473/WayGuard/releases/download/v0.2.0/WayGuard-v0.2.0.apk",
                  "digest": "sha256:${hash.uppercase()}"
                }
              ]
            }
        """.trimIndent()

        val res = ReleasePicker.evaluateLatestRelease(json, "0.1.0", listOf("arm64-v8a", "armeabi-v7a"))
        assertTrue(res is ReleaseParseResult.UpdateAvailable)
        val update = (res as ReleaseParseResult.UpdateAvailable).update
        assertEquals("0.2.0", update.version)
        assertEquals("WayGuard-v0.2.0.apk", update.apkName)
        assertEquals(hash, update.expectedSha256)
        assertEquals("", update.notes)
        assertNull(update.checksumAssetUrl)
    }

    @Test
    fun evaluateLatestReleaseFallsBackToSidecarSha256AndMatchesAbi() {
        val json = """
            {
              "tag_name": "v0.3.0",
              "name": null,
              "body": "Bug fixes",
              "draft": false,
              "prerelease": false,
              "assets": [
                {
                  "name": "WayGuard-v0.3.0-x86_64.apk",
                  "size": 100,
                  "browser_download_url": "https://github.com/ShiKi5473/WayGuard/releases/download/v0.3.0/WayGuard-v0.3.0-x86_64.apk"
                },
                {
                  "name": "WayGuard-v0.3.0-arm64-v8a.apk",
                  "size": 100,
                  "browser_download_url": "https://github.com/ShiKi5473/WayGuard/releases/download/v0.3.0/WayGuard-v0.3.0-arm64-v8a.apk"
                },
                {
                  "name": "WayGuard-v0.3.0-arm64-v8a.apk.sha256",
                  "size": 120,
                  "browser_download_url": "https://github.com/ShiKi5473/WayGuard/releases/download/v0.3.0/WayGuard-v0.3.0-arm64-v8a.apk.sha256"
                }
              ]
            }
        """.trimIndent()

        val res = ReleasePicker.evaluateLatestRelease(json, "0.1.0", listOf("arm64-v8a"))
        val update = (res as ReleaseParseResult.UpdateAvailable).update
        assertEquals("WayGuard-v0.3.0-arm64-v8a.apk", update.apkName)
        assertNull(update.expectedSha256)
        assertEquals(
            "https://github.com/ShiKi5473/WayGuard/releases/download/v0.3.0/WayGuard-v0.3.0-arm64-v8a.apk.sha256",
            update.checksumAssetUrl,
        )
        assertEquals("Bug fixes", update.notes)
    }

    @Test
    fun ignoreDraftPrereleaseUpToDateAndHttpUrls() {
        val upToDateJson = """{"tag_name": "v0.1.0", "assets": []}"""
        assertEquals(ReleaseParseResult.UpToDate, ReleasePicker.evaluateLatestRelease(upToDateJson, "0.1.0"))

        val prereleaseJson = """{"tag_name": "v0.2.0", "prerelease": true, "assets": []}"""
        assertEquals(ReleaseParseResult.UpToDate, ReleasePicker.evaluateLatestRelease(prereleaseJson, "0.1.0"))

        val httpOnlyJson = """
            {
              "tag_name": "v0.2.0",
              "assets": [
                {"name": "WayGuard-v0.2.0.apk", "browser_download_url": "http://example.com/app.apk"}
              ]
            }
        """.trimIndent()
        assertEquals(
            ReleaseParseResult.NoApkAsset("0.2.0"),
            ReleasePicker.evaluateLatestRelease(httpOnlyJson, "0.1.0"),
        )
    }

    @Test
    fun parseChecksumTextSupportsGnuBsdAndBare() {
        val h1 = "0123456789abcdef".repeat(4)
        val h2 = "fedcba9876543210".repeat(4)
        val gnuMulti = """
            $h1  WayGuard-v0.2.0-x86.apk
            $h2 *./WayGuard-v0.2.0.apk
        """.trimIndent()
        assertEquals(h2, ReleasePicker.parseChecksumText(gnuMulti, "WayGuard-v0.2.0.apk"))

        val bsd = "SHA256 (WayGuard-v0.2.0.apk) = ${h1.uppercase()}"
        assertEquals(h1, ReleasePicker.parseChecksumText(bsd, "WayGuard-v0.2.0.apk"))

        assertEquals(h1, ReleasePicker.parseChecksumText("$h1\n", "WayGuard-v0.2.0.apk"))
        assertNull(ReleasePicker.parseChecksumText("$h1  other.apk", "WayGuard-v0.2.0.apk"))
    }
}

