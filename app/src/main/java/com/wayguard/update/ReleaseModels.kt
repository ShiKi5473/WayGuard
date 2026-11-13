package com.wayguard.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val UpdateJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    val name: String,
    val size: Long = 0L,
    @SerialName("browser_download_url") val downloadUrl: String,
    @SerialName("content_type") val contentType: String? = null,
    /** GitHub Releases API returns "sha256:<64-hex>" when available. */
    val digest: String? = null,
)

/** A parsed release update candidate ready for UI display and download. */
data class AvailableUpdate(
    val version: String,
    val tagName: String,
    val releaseName: String,
    val notes: String,
    val publishedAt: String,
    val htmlUrl: String,
    val apkName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val expectedSha256: String?,
    val checksumAssetUrl: String?,
)

/**
 * Semantic version parser and comparator supporting optional `v` prefix,
 * arbitrary dot-separated numeric parts, pre-release suffixes (`-rc.1`),
 * and ignored `+build` metadata.
 */
data class AppVersion(
    val core: List<Int>,
    val preRelease: List<String>?,
    val normalized: String,
) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int {
        val len = maxOf(core.size, other.core.size)
        for (i in 0 until len) {
            val a = core.getOrElse(i) { 0 }
            val b = other.core.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        val aPre = preRelease
        val bPre = other.preRelease
        return when {
            aPre == null && bPre == null -> 0
            aPre == null -> 1
            bPre == null -> -1
            else -> comparePreRelease(aPre, bPre)
        }
    }

    private fun comparePreRelease(a: List<String>, b: List<String>): Int {
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            val left = a[i]
            val right = b[i]
            val leftNum = left.toLongOrNull()
            val rightNum = right.toLongOrNull()
            val cmp = when {
                leftNum != null && rightNum != null -> leftNum.compareTo(rightNum)
                leftNum != null -> -1
                rightNum != null -> 1
                else -> left.compareTo(right)
            }
            if (cmp != 0) return cmp
        }
        return a.size.compareTo(b.size)
    }

    companion object {
        fun parse(raw: String): AppVersion? {
            val trimmed = raw.trim().removePrefix("v").removePrefix("V")
            if (trimmed.isEmpty()) return null
            val withoutBuild = trimmed.substringBefore('+')
            val dashIdx = withoutBuild.indexOf('-')
            val corePart = if (dashIdx >= 0) withoutBuild.substring(0, dashIdx) else withoutBuild
            val prePart = if (dashIdx >= 0) withoutBuild.substring(dashIdx + 1) else null

            val coreSegments = corePart.split('.')
            if (coreSegments.isEmpty()) return null
            val coreNums = ArrayList<Int>(coreSegments.size)
            for (seg in coreSegments) {
                val n = seg.toIntOrNull() ?: return null
                if (n < 0) return null
                coreNums.add(n)
            }
            val preSegments = prePart?.takeIf { it.isNotEmpty() }?.split('.')
            return AppVersion(core = coreNums, preRelease = preSegments, normalized = withoutBuild)
        }
    }
}

sealed interface ReleaseParseResult {
    data class UpdateAvailable(val update: AvailableUpdate) : ReleaseParseResult
    data object UpToDate : ReleaseParseResult
    data class NoApkAsset(val version: String) : ReleaseParseResult
}

object ReleasePicker {

    private val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")
    private val GNU_SUM_LINE = Regex("^([0-9a-fA-F]{64})(?:\\s+\\*?(.+))?$")
    private val BSD_SUM_LINE = Regex("^SHA256\\s*\\((.+)\\)\\s*=\\s*([0-9a-fA-F]{64})$", RegexOption.IGNORE_CASE)
    private val KNOWN_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    fun isNewer(remoteTag: String, currentVersionName: String): Boolean {
        val remote = AppVersion.parse(remoteTag) ?: return false
        val current = AppVersion.parse(currentVersionName) ?: return false
        return remote > current
    }

    fun evaluateLatestRelease(
        jsonText: String,
        currentVersionName: String,
        supportedAbis: List<String> = emptyList(),
    ): ReleaseParseResult {
        val release = UpdateJson.decodeFromString(GitHubRelease.serializer(), jsonText)
        if (release.draft || release.prerelease) return ReleaseParseResult.UpToDate
        if (!isNewer(release.tagName, currentVersionName)) return ReleaseParseResult.UpToDate

        val version = AppVersion.parse(release.tagName)?.normalized
            ?: release.tagName.trim().removePrefix("v").removePrefix("V")
        val apkAsset = selectApkAsset(release.assets, supportedAbis)
            ?: return ReleaseParseResult.NoApkAsset(version)

        val expectedSha = extractSha256FromDigest(apkAsset.digest)
        val checksumAsset = if (expectedSha == null) {
            findChecksumAsset(release.assets, apkAsset.name)
        } else {
            null
        }

        return ReleaseParseResult.UpdateAvailable(
            AvailableUpdate(
                version = version,
                tagName = release.tagName,
                releaseName = release.name?.takeIf { it.isNotBlank() } ?: release.tagName,
                notes = release.body?.trim().orEmpty(),
                publishedAt = release.publishedAt.orEmpty(),
                htmlUrl = release.htmlUrl.orEmpty(),
                apkName = apkAsset.name,
                downloadUrl = apkAsset.downloadUrl,
                sizeBytes = apkAsset.size,
                expectedSha256 = expectedSha,
                checksumAssetUrl = checksumAsset?.downloadUrl,
            )
        )
    }

    /** Picks the best installable HTTPS `.apk` asset, preferring signed release & matching ABI. */
    fun selectApkAsset(assets: List<GitHubAsset>, supportedAbis: List<String> = emptyList()): GitHubAsset? {
        val candidates = assets.filter { asset ->
            asset.name.endsWith(".apk", ignoreCase = true) &&
                asset.downloadUrl.startsWith("https://", ignoreCase = true) &&
                !asset.name.contains("unsigned", ignoreCase = true)
        }
        if (candidates.isEmpty()) return null
        return candidates.minByOrNull { asset ->
            val lower = asset.name.lowercase()
            val debugPenalty = if ("debug" in lower) 100 else 0
            val abiPenalty = abiScore(lower, supportedAbis)
            debugPenalty + abiPenalty
        }
    }

    private fun abiScore(lowerName: String, supportedAbis: List<String>): Int {
        if (supportedAbis.isEmpty()) return 0
        val matchedIdx = supportedAbis.indexOfFirst { it.lowercase() in lowerName }
        if (matchedIdx >= 0) return matchedIdx
        if ("universal" in lowerName) return supportedAbis.size
        // Neutral if filename carries no ABI suffix; penalize foreign-ABI builds.
        val carriesOtherAbi = KNOWN_ABIS.any { it in lowerName }
        return if (carriesOtherAbi) 50 else supportedAbis.size + 1
    }

    fun extractSha256FromDigest(digest: String?): String? {
        val raw = digest?.trim() ?: return null
        if (!raw.startsWith("sha256:", ignoreCase = true)) return null
        val hex = raw.substringAfter(':').trim()
        return hex.lowercase().takeIf { SHA256_HEX.matches(it) }
    }

    fun findChecksumAsset(assets: List<GitHubAsset>, apkName: String): GitHubAsset? {
        val httpsAssets = assets.filter { it.downloadUrl.startsWith("https://", ignoreCase = true) }
        val exact = httpsAssets.firstOrNull { it.name.equals("$apkName.sha256", ignoreCase = true) }
            ?: httpsAssets.firstOrNull { it.name.equals("$apkName.sha256sum", ignoreCase = true) }
        if (exact != null) return exact
        return httpsAssets.firstOrNull {
            it.name.equals("sha256sums.txt", ignoreCase = true) ||
                it.name.equals("SHA256SUMS", ignoreCase = true) ||
                it.name.equals("checksums.txt", ignoreCase = true)
        }
    }

    fun parseChecksumText(text: String, targetFileName: String): String? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return null
        var singleBareHash: String? = null
        for (line in lines) {
            BSD_SUM_LINE.matchEntire(line)?.let { m ->
                val name = m.groupValues[1].trim().removePrefix("./")
                val hash = m.groupValues[2].lowercase()
                if (name == targetFileName || name.substringAfterLast('/') == targetFileName) return hash
            }
            GNU_SUM_LINE.matchEntire(line)?.let { m ->
                val hash = m.groupValues[1].lowercase()
                val name = m.groupValues[2].trim().removePrefix("./")
                if (name.isEmpty()) {
                    if (lines.size == 1) singleBareHash = hash
                } else if (name == targetFileName || name.substringAfterLast('/') == targetFileName) {
                    return hash
                }
            }
        }
        return singleBareHash
    }
}

