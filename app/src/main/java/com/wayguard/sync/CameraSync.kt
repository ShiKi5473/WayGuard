package com.wayguard.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wayguard.BuildConfig
import com.wayguard.core.dataset.DatasetBuilder
import com.wayguard.core.model.DATASET_SCHEMA
import com.wayguard.core.model.DatasetIo
import com.wayguard.core.model.DatasetMeta
import com.wayguard.data.CameraRepository
import com.wayguard.settings.GuardSettingsStore
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface SyncOutcome {
    data object UpToDate : SyncOutcome
    data class Updated(val meta: DatasetMeta) : SyncOutcome
    data class Failed(val message: String) : SyncOutcome
}

/**
 * Checks the published `meta.json` and, when its version differs from the data in
 * use, downloads `cameras.json`, verifies its SHA-256 and installs it.
 */
object CameraSync {

    private const val USER_AGENT = "WayGuard/${BuildConfig.VERSION_NAME} (Android)"

    suspend fun run(context: Context): SyncOutcome = withContext(Dispatchers.IO) {
        try {
            val base = BuildConfig.DATA_BASE_URL
            val meta = DatasetIo.decodeMeta(get(base + "meta.json").decodeToString())
            if (meta.schema != DATASET_SCHEMA) {
                return@withContext SyncOutcome.Failed("資料格式版本不符，請更新 App")
            }
            if (meta.version == CameraRepository.currentVersion) {
                recordCheck(context)
                return@withContext SyncOutcome.UpToDate
            }
            val bytes = get(base + meta.file)
            if (DatasetBuilder.sha256Hex(bytes) != meta.sha256) {
                return@withContext SyncOutcome.Failed("下載內容校驗失敗")
            }
            CameraRepository.install(bytes)
            recordCheck(context)
            SyncOutcome.Updated(meta)
        } catch (e: Exception) {
            SyncOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun recordCheck(context: Context) {
        val now = System.currentTimeMillis()
        GuardSettingsStore.update(context) { it.copy(lastSyncCheckMs = now) }
    }

    private fun get(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}

/** Periodic background update of the camera data. */
class CameraSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (CameraSync.run(applicationContext)) {
        is SyncOutcome.Failed -> if (runAttemptCount < 3) Result.retry() else Result.failure()
        else -> Result.success()
    }
}

object SyncScheduler {

    private const val PERIODIC = "camera-sync"
    private const val CATCH_UP = "camera-sync-catch-up"
    /** Check right away at startup when the last check is older than this. */
    private val STALE_MS = TimeUnit.DAYS.toMillis(14)

    private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    /** Weekly update; re-run with UPDATE so a changed Wi-Fi-only setting takes effect. */
    fun schedule(context: Context, wifiOnly: Boolean) {
        val request = PeriodicWorkRequestBuilder<CameraSyncWorker>(7, TimeUnit.DAYS)
            .setConstraints(constraints(wifiOnly))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** One-off check when the data hasn't been checked for a long time (e.g. phone was off). */
    fun catchUpIfStale(context: Context, lastCheckMs: Long, wifiOnly: Boolean) {
        if (System.currentTimeMillis() - lastCheckMs < STALE_MS) return
        val request = OneTimeWorkRequestBuilder<CameraSyncWorker>()
            .setConstraints(constraints(wifiOnly))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(CATCH_UP, ExistingWorkPolicy.KEEP, request)
    }
}
