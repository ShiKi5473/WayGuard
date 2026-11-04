package com.wayguard.data

import android.content.Context
import android.util.Log
import com.wayguard.core.index.GridIndex
import com.wayguard.core.model.CameraDataset
import com.wayguard.core.model.DATASET_SCHEMA
import com.wayguard.core.model.DatasetIo
import com.wayguard.core.model.Section
import com.wayguard.core.model.SpeedCamera
import com.wayguard.core.model.Suppression
import com.wayguard.settings.UserCameraStore
import com.wayguard.settings.UserData
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DataOrigin { NONE, BUNDLED, DOWNLOADED }

/** Everything the map and the guard need, derived from the dataset plus user data. */
data class CameraData(
    val dataset: CameraDataset? = null,
    val origin: DataOrigin = DataOrigin.NONE,
    /** Official + user cameras that should alert. */
    val active: List<SpeedCamera> = emptyList(),
    /** Cameras the user marked as false alarms (drawn grey, never alert). */
    val suppressed: List<SpeedCamera> = emptyList(),
    val sections: List<Section> = emptyList(),
    val index: GridIndex<SpeedCamera> = GridIndex(emptyList(), { it.pos }),
) {
    private val byId: Map<String, SpeedCamera> by lazy { (active + suppressed).associateBy { it.id } }

    fun find(id: String): SpeedCamera? = byId[id]

    fun isSuppressed(id: String): Boolean = suppressed.any { it.id == id }
}

/**
 * Loads the speed-camera dataset — the downloaded copy in filesDir, or the snapshot
 * bundled in the APK, whichever is newer — and merges it with [UserCameraStore].
 * Call [init] once at startup.
 */
object CameraRepository {

    private const val TAG = "CameraRepository"
    private const val ASSET = "cameras.json"
    private const val FILE = "cameras.json"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val loaded = MutableStateFlow<Pair<CameraDataset, DataOrigin>?>(null)

    lateinit var state: StateFlow<CameraData>
        private set

    private lateinit var appContext: Context

    /** Version of the dataset in use, or null before the first load. */
    val currentVersion: String? get() = loaded.value?.first?.version

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        state = combine(loaded, UserCameraStore.state, ::derive)
            .flowOn(Dispatchers.Default)
            .stateIn(scope, SharingStarted.Eagerly, CameraData())
        scope.launch { reload() }
    }

    /** Re-reads the files and publishes the newer of downloaded/bundled. */
    suspend fun reload() = withContext(Dispatchers.IO) {
        val downloaded = runCatching { readFile(File(appContext.filesDir, FILE)) }
            .onFailure { Log.w(TAG, "Downloaded dataset unreadable", it) }.getOrNull()
        val bundled = runCatching { appContext.assets.open(ASSET).use { DatasetIo.decode(it.readBytes().decodeToString()) } }
            .onFailure { Log.w(TAG, "Bundled dataset unreadable", it) }.getOrNull()
            ?.takeIf { it.schema == DATASET_SCHEMA }
        // ISO-8601 UTC timestamps compare correctly as strings.
        loaded.value = when {
            downloaded != null && (bundled == null || downloaded.generatedAt >= bundled.generatedAt) ->
                downloaded to DataOrigin.DOWNLOADED
            bundled != null -> bundled to DataOrigin.BUNDLED
            else -> null
        }
    }

    /**
     * Validates [bytes] as a dataset, writes it atomically as the downloaded copy and
     * publishes it. Throws if the bytes are not a usable dataset.
     */
    suspend fun install(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val ds = DatasetIo.decode(bytes.decodeToString())
        require(ds.schema == DATASET_SCHEMA) { "資料格式版本不符（${ds.schema}）" }
        require(ds.cameras.isNotEmpty()) { "資料內沒有測速點" }
        val target = File(appContext.filesDir, FILE)
        val tmp = File(appContext.filesDir, "$FILE.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "無法寫入資料檔" }
        }
        loaded.value = ds to DataOrigin.DOWNLOADED
    }

    private fun readFile(f: File): CameraDataset? =
        if (!f.isFile) null
        else DatasetIo.decode(f.readText()).takeIf { it.schema == DATASET_SCHEMA }

    private fun derive(loaded: Pair<CameraDataset, DataOrigin>?, user: UserData): CameraData {
        val official = loaded?.first?.cameras.orEmpty()
        val (active, suppressed) = Suppression.partition(official + user.cameras, user.suppressed)
        return CameraData(
            dataset = loaded?.first,
            origin = loaded?.second ?: DataOrigin.NONE,
            active = active,
            suppressed = suppressed,
            sections = loaded?.first?.sections.orEmpty(),
            index = GridIndex(active, { it.pos }),
        )
    }
}
