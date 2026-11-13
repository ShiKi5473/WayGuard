package com.wayguard.trip

import android.content.Context
import com.wayguard.core.trip.GpxWriter
import com.wayguard.core.trip.TripDetail
import com.wayguard.core.trip.TripSummary
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Process-wide coordinator for [TripStorage], keeping a sorted [StateFlow] of [TripSummary]
 * and serializing writes off the main thread.
 */
object TripStore {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val _trips = MutableStateFlow<List<TripSummary>>(emptyList())
    val trips: StateFlow<List<TripSummary>> = _trips.asStateFlow()

    private lateinit var storage: TripStorage
    private var retentionDays: Int = 90

    fun init(context: Context, retentionDays: Int = 90) {
        this.retentionDays = retentionDays
        if (!::storage.isInitialized) {
            val app = context.applicationContext
            storage = TripStorage(
                backupDir = File(app.filesDir, "trips"),
                overflowDir = File(app.noBackupFilesDir, "trips-overflow"),
            )
            scope.launch {
                _trips.value = storage.loadAndRecover(retentionDays = this@TripStore.retentionDays)
            }
        }
    }

    fun updateRetentionDays(days: Int) {
        retentionDays = days
    }

    fun appendLinesAsync(tripId: String, lines: List<String>) {
        if (!::storage.isInitialized || lines.isEmpty()) return
        scope.launch { storage.appendLines(tripId, lines) }
    }

    fun discardActiveFileAsync(tripId: String) {
        if (!::storage.isInitialized) return
        scope.launch { storage.discardActiveFile(tripId) }
    }

    fun finalizeTripAsync(detail: TripDetail) {
        if (!::storage.isInitialized) return
        scope.launch {
            _trips.value = storage.finalizeTrip(detail, retentionDays = retentionDays)
        }
    }

    suspend fun loadDetail(summary: TripSummary): TripDetail? = withContext(Dispatchers.IO) {
        if (!::storage.isInitialized) return@withContext null
        storage.loadDetail(summary)
    }

    fun deleteTrip(id: String) {
        if (!::storage.isInitialized) return
        scope.launch {
            _trips.value = storage.deleteTrip(id)
        }
    }

    suspend fun writeGpxShareFile(context: Context, detail: TripDetail): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "trips").apply { mkdirs() }
        val file = File(dir, "${detail.summary.id}.gpx")
        file.writeText(GpxWriter.write(detail), Charsets.UTF_8)
        file
    }

    suspend fun exportBackupZip(output: OutputStream): Int = withContext(Dispatchers.IO) {
        if (!::storage.isInitialized) 0 else storage.exportZip(output)
    }

    suspend fun importBackupZip(input: InputStream): Int = withContext(Dispatchers.IO) {
        if (!::storage.isInitialized) return@withContext 0
        val (updated, imported) = storage.importZip(input, retentionDays = retentionDays)
        _trips.value = updated
        imported
    }
}
