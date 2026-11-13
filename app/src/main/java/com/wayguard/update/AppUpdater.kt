package com.wayguard.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.os.Build
import com.wayguard.BuildConfig
import com.wayguard.guard.GuardController
import com.wayguard.settings.GuardSettingsStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class InstallPauseReason {
    READY,
    PERMISSION_REQUIRED,
    GUARDING_ACTIVE,
}

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data class UpToDate(val currentVersion: String) : AppUpdateState
    data class Available(val release: AvailableUpdate, val showPrompt: Boolean) : AppUpdateState
    data class Downloading(
        val release: AvailableUpdate,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val showPrompt: Boolean,
    ) : AppUpdateState
    data class Verifying(val release: AvailableUpdate, val showPrompt: Boolean) : AppUpdateState
    data class ReadyToInstall(
        val release: AvailableUpdate,
        val apkFile: File,
        val reason: InstallPauseReason,
        val showPrompt: Boolean,
    ) : AppUpdateState
    data class Installing(val release: AvailableUpdate, val showPrompt: Boolean) : AppUpdateState
    data class PendingUserAction(
        val release: AvailableUpdate,
        val confirmIntent: Intent,
        val apkFile: File,
        val showPrompt: Boolean,
    ) : AppUpdateState
    data class Failed(
        val message: String,
        val release: AvailableUpdate? = null,
        val showPrompt: Boolean = false,
    ) : AppUpdateState

    val isBusy: Boolean
        get() = this is Checking || this is Downloading || this is Verifying || this is Installing
}

/**
 * Checks GitHub Releases for newer WayGuard APKs, downloads & verifies SHA-256 +
 * signing certificates, and installs via Android's [PackageInstaller] with
 * [PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED] (falling back to
 * system confirmation when required).
 */
object AppUpdater {

    private const val UPDATES_DIR = "updates"
    private const val TMP_APK = "download.apk.tmp"
    private const val MAX_REDIRECTS = 5
    private const val MAX_JSON_BYTES = 2 * 1024 * 1024
    private val USER_AGENT = "WayGuard/${BuildConfig.VERSION_NAME} (Android)"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    private val _confirmEvents = Channel<Intent>(Channel.BUFFERED)
    val confirmEvents: Flow<Intent> = _confirmEvents.receiveAsFlow()

    private val isForeground = AtomicBoolean(false)

    @Volatile
    private var activeJob: Job? = null

    @Volatile
    private var lastInstallingRelease: AvailableUpdate? = null

    @Volatile
    private var lastInstallingApk: File? = null

    fun setForeground(foreground: Boolean) {
        isForeground.set(foreground)
    }

    /** Manually triggered from Settings. Always shows prompt if a newer release is found. */
    fun checkManual(context: Context) {
        val cur = _state.value
        if (cur.isBusy) return
        val appContext = context.applicationContext
        activeJob = scope.launch {
            runCheck(appContext, manual = true, skippedVersion = "")
        }
    }

    /**
     * Background / startup auto-update check. Skips quietly if another update task
     * is active, if the latest version was skipped by the user, or if no update exists.
     */
    suspend fun runAutoCheckAndUpdate(context: Context, skippedVersion: String): Boolean {
        val appContext = context.applicationContext
        val cur = _state.value
        if (cur.isBusy || cur is AppUpdateState.ReadyToInstall || cur is AppUpdateState.PendingUserAction) {
            return true
        }
        return runCheck(appContext, manual = false, skippedVersion = skippedVersion)
    }

    fun startDownloadAndInstall(context: Context, release: AvailableUpdate, showPrompt: Boolean = true) {
        val cur = _state.value
        if (cur is AppUpdateState.Downloading || cur is AppUpdateState.Verifying || cur is AppUpdateState.Installing) {
            return
        }
        val appContext = context.applicationContext
        activeJob?.cancel()
        activeJob = scope.launch {
            downloadVerifyAndInstall(appContext, release, showPrompt = showPrompt)
        }
    }

    fun installDownloaded(context: Context, release: AvailableUpdate, apkFile: File, showPrompt: Boolean = true) {
        val appContext = context.applicationContext
        scope.launch {
            proceedToInstall(appContext, release, apkFile, showPrompt = showPrompt)
        }
    }

    fun cancelDownload(context: Context) {
        activeJob?.cancel()
        activeJob = null
        File(updatesDir(context.applicationContext), TMP_APK).delete()
        val cur = _state.value
        val rel = when (cur) {
            is AppUpdateState.Downloading -> cur.release
            is AppUpdateState.Verifying -> cur.release
            else -> null
        }
        _state.value = if (rel != null) AppUpdateState.Available(rel, showPrompt = false) else AppUpdateState.Idle
    }

    fun dismissPrompt() {
        _state.value = when (val cur = _state.value) {
            is AppUpdateState.Available -> if (cur.showPrompt) AppUpdateState.Idle else cur
            is AppUpdateState.Downloading -> cur.copy(showPrompt = false)
            is AppUpdateState.Verifying -> cur.copy(showPrompt = false)
            is AppUpdateState.ReadyToInstall -> cur.copy(showPrompt = false)
            is AppUpdateState.Installing -> cur.copy(showPrompt = false)
            is AppUpdateState.PendingUserAction -> cur.copy(showPrompt = false)
            is AppUpdateState.Failed -> AppUpdateState.Idle
            else -> cur
        }
    }

    fun showPromptIfUpdatePending(context: Context) {
        val appContext = context.applicationContext
        when (val cur = _state.value) {
            is AppUpdateState.Available -> _state.value = cur.copy(showPrompt = true)
            is AppUpdateState.Downloading -> _state.value = cur.copy(showPrompt = true)
            is AppUpdateState.Verifying -> _state.value = cur.copy(showPrompt = true)
            is AppUpdateState.ReadyToInstall -> _state.value = cur.copy(showPrompt = true)
            is AppUpdateState.PendingUserAction -> {
                _state.value = cur.copy(showPrompt = true)
                _confirmEvents.trySend(cur.confirmIntent)
            }
            is AppUpdateState.Idle, is AppUpdateState.UpToDate, is AppUpdateState.Failed -> {
                scope.launch {
                    val cached = findLatestCachedApk(appContext) ?: return@launch
                    val (version, file) = cached
                    val synthetic = AvailableUpdate(
                        version = version,
                        tagName = "v$version",
                        releaseName = "WayGuard v$version",
                        notes = "",
                        publishedAt = "",
                        htmlUrl = "",
                        apkName = file.name,
                        downloadUrl = "",
                        sizeBytes = file.length(),
                        expectedSha256 = null,
                        checksumAssetUrl = null,
                    )
                    proceedToInstall(appContext, synthetic, file, showPrompt = true)
                }
            }
            is AppUpdateState.Installing, AppUpdateState.Checking -> Unit
        }
    }

    /**
     * Called when [MainActivity] resumes (e.g. after granting unknown-sources permission)
     * or when guarding stops while a verified APK is waiting.
     */
    fun resumePendingInstallIfReady(context: Context) {
        val appContext = context.applicationContext
        val cur = _state.value
        if (cur is AppUpdateState.ReadyToInstall) {
            if (GuardController.current.running) return
            if (!appContext.packageManager.canRequestPackageInstalls()) return
            // Only auto-resume if it was paused waiting for permission or guard stop.
            if (cur.reason == InstallPauseReason.PERMISSION_REQUIRED || cur.reason == InstallPauseReason.GUARDING_ACTIVE) {
                scope.launch {
                    proceedToInstall(appContext, cur.release, cur.apkFile, showPrompt = cur.showPrompt)
                }
            }
        }
    }

    fun consumePendingConfirmIntent(): Intent? {
        val cur = _state.value
        if (cur !is AppUpdateState.PendingUserAction) return null
        return cur.confirmIntent
    }

    fun onInstallReceiverResult(
        context: Context,
        status: Int,
        statusMessage: String?,
        confirmIntent: Intent?,
    ) {
        val release = lastInstallingRelease
        val apkFile = lastInstallingApk
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (confirmIntent == null || release == null || apkFile == null) {
                    _state.value = AppUpdateState.Failed("安裝程序未回傳確認畫面", release, showPrompt = isForeground.get())
                    return
                }
                _state.value = AppUpdateState.PendingUserAction(
                    release = release,
                    confirmIntent = confirmIntent,
                    apkFile = apkFile,
                    showPrompt = isForeground.get(),
                )
                if (isForeground.get()) {
                    _confirmEvents.trySend(confirmIntent)
                } else {
                    AppUpdateNotifier.notifyInstallReady(
                        context,
                        release.version,
                        "點擊確認安裝 WayGuard v${release.version}",
                    )
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                AppUpdateNotifier.cancel(context)
                cleanupUpdateCache(context)
                _state.value = AppUpdateState.UpToDate(release?.version ?: BuildConfig.VERSION_NAME)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                if (release != null && apkFile != null && apkFile.isFile) {
                    _state.value = AppUpdateState.ReadyToInstall(
                        release = release,
                        apkFile = apkFile,
                        reason = InstallPauseReason.READY,
                        showPrompt = false,
                    )
                } else {
                    _state.value = AppUpdateState.Idle
                }
            }
            else -> {
                val friendly = mapInstallFailure(status, statusMessage)
                _state.value = AppUpdateState.Failed(friendly, release, showPrompt = isForeground.get())
            }
        }
    }

    /** Deletes cached `.tmp` and outdated `.apk` files at app startup. */
    fun cleanupStaleFiles(context: Context) {
        val dir = updatesDir(context.applicationContext)
        if (!dir.isDirectory) return
        val currentVersion = AppVersion.parse(BuildConfig.VERSION_NAME)
        dir.listFiles().orEmpty().forEach { f ->
            if (f.name.endsWith(".tmp")) {
                f.delete()
                return@forEach
            }
            if (f.name.endsWith(".apk")) {
                val verStr = f.name.removePrefix("WayGuard-v").removePrefix("v").removeSuffix(".apk")
                val parsed = AppVersion.parse(verStr)
                if (parsed == null || (currentVersion != null && parsed <= currentVersion)) {
                    f.delete()
                }
            }
        }
    }

    /** Deletes everything in the updates cache directory; returns true if any file existed. */
    fun cleanupUpdateCache(context: Context): Boolean {
        val dir = updatesDir(context.applicationContext)
        val files = dir.listFiles().orEmpty()
        val hadFiles = files.isNotEmpty()
        files.forEach { it.delete() }
        return hadFiles
    }

    // ---------------- internal network + verify + install ----------------

    private suspend fun runCheck(context: Context, manual: Boolean, skippedVersion: String): Boolean {
        if (manual) _state.value = AppUpdateState.Checking
        return try {
            val apiUrl = "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest"
            val json = fetchGitHubReleaseJson(apiUrl)
            val now = System.currentTimeMillis()
            GuardSettingsStore.update(context) { it.copy(lastAppUpdateCheckMs = now) }

            if (json == null) {
                // HTTP 404: repo has no releases published yet.
                if (manual) {
                    _state.value = AppUpdateState.Failed("GitHub 上尚無發行版本")
                } else {
                    _state.value = AppUpdateState.UpToDate(BuildConfig.VERSION_NAME)
                }
                return true
            }

            when (val result = ReleasePicker.evaluateLatestRelease(
                jsonText = json,
                currentVersionName = BuildConfig.VERSION_NAME,
                supportedAbis = Build.SUPPORTED_ABIS.toList(),
            )) {
                ReleaseParseResult.UpToDate -> {
                    _state.value = AppUpdateState.UpToDate(BuildConfig.VERSION_NAME)
                    true
                }
                is ReleaseParseResult.NoApkAsset -> {
                    if (manual) {
                        _state.value = AppUpdateState.Failed("GitHub v${result.version} 尚未附帶 APK 安裝檔")
                    }
                    true
                }
                is ReleaseParseResult.UpdateAvailable -> {
                    val release = resolveExpectedChecksum(result.update)
                    if (manual) {
                        _state.value = AppUpdateState.Available(release, showPrompt = true)
                    } else if (release.version != skippedVersion) {
                        downloadVerifyAndInstall(context, release, showPrompt = false)
                    }
                    true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (manual) {
                _state.value = AppUpdateState.Failed(e.message ?: e.javaClass.simpleName)
            }
            false
        }
    }

    private suspend fun resolveExpectedChecksum(release: AvailableUpdate): AvailableUpdate {
        if (release.expectedSha256 != null || release.checksumAssetUrl == null) return release
        val text = runCatching { downloadSmallText(release.checksumAssetUrl) }.getOrNull() ?: return release
        val hash = ReleasePicker.parseChecksumText(text, release.apkName) ?: return release
        return release.copy(expectedSha256 = hash)
    }

    private suspend fun downloadVerifyAndInstall(
        context: Context,
        release: AvailableUpdate,
        showPrompt: Boolean,
    ) {
        val dir = updatesDir(context)
        dir.mkdirs()
        val targetApk = File(dir, "WayGuard-v${release.version}.apk")

        try {
            // Re-use already downloaded & verified APK if present.
            if (targetApk.isFile && targetApk.length() > 0L) {
                _state.value = AppUpdateState.Verifying(release, showPrompt = showPrompt)
                val cachedOk = verifyChecksumIfExpected(targetApk, release.expectedSha256) == null &&
                    verifyDownloadedApk(context, targetApk) == null
                if (cachedOk) {
                    proceedToInstall(context, release, targetApk, showPrompt = showPrompt)
                    return
                }
                targetApk.delete()
            }

            // Clean old cached APKs before downloading a new version.
            dir.listFiles().orEmpty().forEach { if (it.name != TMP_APK) it.delete() }
            val tmpFile = File(dir, TMP_APK)
            tmpFile.delete()

            val actualSha256 = downloadFileWithProgress(
                url = release.downloadUrl,
                dest = tmpFile,
                expectedSize = release.sizeBytes,
            ) { downloaded, total ->
                val latestPrompt = (_state.value as? AppUpdateState.Downloading)?.showPrompt ?: showPrompt
                _state.value = AppUpdateState.Downloading(release, downloaded, total, latestPrompt)
            }

            val promptNow = (_state.value as? AppUpdateState.Downloading)?.showPrompt ?: showPrompt
            _state.value = AppUpdateState.Verifying(release, showPrompt = promptNow)

            val expected = release.expectedSha256
            if (expected != null && !actualSha256.equals(expected, ignoreCase = true)) {
                tmpFile.delete()
                _state.value = AppUpdateState.Failed(
                    message = "下載檔案校驗失敗（SHA-256 不符）",
                    release = release,
                    showPrompt = promptNow,
                )
                return
            }

            val apkErr = verifyDownloadedApk(context, tmpFile)
            if (apkErr != null) {
                tmpFile.delete()
                _state.value = AppUpdateState.Failed(apkErr, release, showPrompt = promptNow)
                return
            }

            if (!tmpFile.renameTo(targetApk)) {
                targetApk.delete()
                check(tmpFile.renameTo(targetApk)) { "無法儲存更新檔" }
            }

            proceedToInstall(context, release, targetApk, showPrompt = promptNow)
        } catch (e: CancellationException) {
            File(dir, TMP_APK).delete()
            throw e
        } catch (e: Exception) {
            File(dir, TMP_APK).delete()
            val promptNow = when (val s = _state.value) {
                is AppUpdateState.Downloading -> s.showPrompt
                is AppUpdateState.Verifying -> s.showPrompt
                else -> showPrompt
            }
            _state.value = AppUpdateState.Failed(
                message = e.message ?: e.javaClass.simpleName,
                release = release,
                showPrompt = promptNow,
            )
        }
    }

    private suspend fun proceedToInstall(
        context: Context,
        release: AvailableUpdate,
        apkFile: File,
        showPrompt: Boolean,
    ) = withContext(Dispatchers.IO) {
        if (GuardController.current.running) {
            _state.value = AppUpdateState.ReadyToInstall(
                release = release,
                apkFile = apkFile,
                reason = InstallPauseReason.GUARDING_ACTIVE,
                showPrompt = showPrompt,
            )
            if (!isForeground.get()) {
                AppUpdateNotifier.notifyInstallReady(
                    context,
                    release.version,
                    "已下載完成，停止守衛後點擊即可安裝",
                )
            }
            return@withContext
        }

        if (!context.packageManager.canRequestPackageInstalls()) {
            val promptUser = showPrompt || isForeground.get()
            _state.value = AppUpdateState.ReadyToInstall(
                release = release,
                apkFile = apkFile,
                reason = InstallPauseReason.PERMISSION_REQUIRED,
                showPrompt = promptUser,
            )
            if (!isForeground.get()) {
                AppUpdateNotifier.notifyInstallReady(
                    context,
                    release.version,
                    "已下載完成，請點擊授權「安裝未知應用程式」權限並完成更新",
                )
            }
            return@withContext
        }

        commitPackageInstaller(context, release, apkFile, showPrompt)
    }

    private fun commitPackageInstaller(
        context: Context,
        release: AvailableUpdate,
        apkFile: File,
        showPrompt: Boolean,
    ) {
        val pi = context.packageManager.packageInstaller
        pi.mySessions.forEach { info -> runCatching { pi.abandonSession(info.sessionId) } }

        lastInstallingRelease = release
        lastInstallingApk = apkFile
        _state.value = AppUpdateState.Installing(release, showPrompt = showPrompt)

        var session: PackageInstaller.Session? = null
        var sessionId = -1
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apkFile.length())
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            sessionId = pi.createSession(params)
            session = pi.openSession(sessionId)
            session.openWrite("base.apk", 0, apkFile.length()).use { out ->
                FileInputStream(apkFile).use { input ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                    }
                }
                session.fsync(out)
            }
            val callbackIntent = Intent(context, AppUpdateInstallReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                callbackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pendingIntent.intentSender)
            session.close()
            session = null
        } catch (e: Exception) {
            runCatching { session?.abandon() }
            if (sessionId >= 0) runCatching { pi.abandonSession(sessionId) }
            _state.value = AppUpdateState.Failed(
                message = "啟動安裝程序失敗：${e.message ?: e.javaClass.simpleName}",
                release = release,
                showPrompt = showPrompt || isForeground.get(),
            )
        }
    }

    private fun fetchGitHubReleaseJson(apiUrl: String): String? {
        val conn = openHttpsConnection(
            url = apiUrl,
            headers = mapOf(
                "Accept" to "application/vnd.github+json",
                "X-GitHub-Api-Version" to "2022-11-28",
                "User-Agent" to USER_AGENT,
            ),
        )
        try {
            return when (val code = conn.responseCode) {
                200 -> conn.inputStream.use { it.readBytes().decodeToString() }
                404 -> null
                403, 429 -> {
                    val remaining = conn.getHeaderField("X-RateLimit-Remaining")
                    if (remaining == "0" || code == 429) {
                        error("GitHub API 請求次數已達上限，請稍後再試")
                    }
                    error("GitHub 拒絕存取（HTTP $code）")
                }
                else -> error("HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadSmallText(url: String): String {
        val conn = openHttpsConnection(url, mapOf("User-Agent" to USER_AGENT))
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val bytes = conn.inputStream.use { input ->
                val buf = ByteArray(4096)
                val out = java.io.ByteArrayOutputStream()
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_JSON_BYTES) error("校驗檔過大")
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            return bytes.decodeToString()
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun downloadFileWithProgress(
        url: String,
        dest: File,
        expectedSize: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): String {
        val conn = openHttpsConnection(url, mapOf("User-Agent" to USER_AGENT))
        try {
            if (conn.responseCode != 200) error("下載失敗（HTTP ${conn.responseCode}）")
            val contentLength = conn.contentLengthLong.takeIf { it > 0L } ?: expectedSize
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            var lastEmit = 0L

            onProgress(0L, contentLength)
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        digest.update(buf, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 120L) {
                            onProgress(downloaded, contentLength)
                            lastEmit = now
                        }
                    }
                    out.flush()
                }
            }
            onProgress(downloaded, contentLength.coerceAtLeast(downloaded))
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            conn.disconnect()
        }
    }

    private fun openHttpsConnection(url: String, headers: Map<String, String>): HttpURLConnection {
        var current = url
        for (hop in 0..MAX_REDIRECTS) {
            val parsed = URL(current)
            require(parsed.protocol.equals("https", ignoreCase = true)) { "僅支援 HTTPS 下載連線" }
            val conn = (parsed.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = conn.responseCode
            if (code in 301..303 || code == 307 || code == 308) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) error("重新導向缺少目標網址")
                current = parsed.toURI().resolve(loc).toString()
                continue
            }
            return conn
        }
        error("重新導向次數過多")
    }

    private fun verifyChecksumIfExpected(file: File, expectedSha256: String?): String? {
        if (expectedSha256 == null) return null
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return if (actual.equals(expectedSha256, ignoreCase = true)) null else "檔案校驗失敗（SHA-256 不符）"
    }

    private fun verifyDownloadedApk(context: Context, apkFile: File): String? {
        val pm = context.packageManager
        val archiveInfo = getPackageArchiveInfoCompat(pm, apkFile.absolutePath)
            ?: return "下載的檔案不是有效的 APK 安裝包"
        if (archiveInfo.packageName != context.packageName) {
            return "APK 套件名稱不符（${archiveInfo.packageName}）"
        }
        val installedInfo = getPackageInfoCompat(pm, context.packageName)
        if (archiveInfo.longVersionCode < installedInfo.longVersionCode) {
            return "新版 APK 的版本代碼（${archiveInfo.longVersionCode}）低於目前版本（${installedInfo.longVersionCode}）"
        }
        val installedCerts = signingCertDigests(installedInfo.signingInfo)
        val archiveCerts = signingCertDigests(archiveInfo.signingInfo)
        if (installedCerts.isEmpty() || archiveCerts.isEmpty()) {
            return "無法驗證新版 APK 簽章"
        }
        if ((installedCerts intersect archiveCerts).isEmpty()) {
            return "新版 APK 簽章與目前安裝版本不一致（若為測試版，請先手動解除安裝舊版後再安裝）"
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun getPackageArchiveInfoCompat(pm: PackageManager, path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(
                path,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    @Suppress("DEPRECATION")
    private fun getPackageInfoCompat(pm: PackageManager, packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    private fun signingCertDigests(signingInfo: SigningInfo?): Set<String> {
        if (signingInfo == null) return emptySet()
        val signers = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        } ?: return emptySet()
        val md = MessageDigest.getInstance("SHA-256")
        return signers.mapTo(HashSet()) { sig ->
            md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    private fun findLatestCachedApk(context: Context): Pair<String, File>? {
        val dir = updatesDir(context)
        if (!dir.isDirectory) return null
        val currentVersion = AppVersion.parse(BuildConfig.VERSION_NAME) ?: return null
        return dir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(".apk") }
            .mapNotNull { file ->
                val verStr = file.name.removePrefix("WayGuard-v").removePrefix("v").removeSuffix(".apk")
                val parsed = AppVersion.parse(verStr) ?: return@mapNotNull null
                if (parsed > currentVersion && verifyDownloadedApk(context, file) == null) {
                    Triple(parsed, verStr, file)
                } else {
                    null
                }
            }
            .maxByOrNull { it.first }
            ?.let { it.second to it.third }
    }

    private fun mapInstallFailure(status: Int, msg: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "安裝被系統阻擋，請確認權限或安全性設定"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "與已安裝版本衝突（可能為降版或簽章不一致）"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "此版本與目前的裝置或系統不相容"
        PackageInstaller.STATUS_FAILURE_INVALID -> "安裝檔損毀或格式無效"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "裝置儲存空間不足，無法安裝更新"
        else -> msg?.takeIf { it.isNotBlank() }?.let { "安裝失敗：$it" } ?: "安裝失敗（代碼 $status）"
    }

    private fun updatesDir(context: Context): File = File(context.cacheDir, UPDATES_DIR)
}
