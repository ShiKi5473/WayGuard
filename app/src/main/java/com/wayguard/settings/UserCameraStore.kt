package com.wayguard.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.wayguard.core.model.DatasetJson
import com.wayguard.core.model.SpeedCamera
import com.wayguard.core.model.SuppressedMark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

/** Data the user adds on this device; never uploaded. */
@Serializable
data class UserData(
    /** User-added points (kinds MOBILE or CUSTOM). */
    val cameras: List<SpeedCamera> = emptyList(),
    /** Cameras marked as false alarms / removed. */
    val suppressed: List<SuppressedMark> = emptyList(),
)

/**
 * User-added points and false-alarm marks. Held in memory as a [StateFlow] for the
 * UI/service and written through to the settings DataStore (as JSON) on every change.
 * Call [init] once at startup before reading [state].
 */
object UserCameraStore {

    private val KEY = stringPreferencesKey("user_cameras_json")
    // Single-threaded so writes land in order and a stale snapshot never wins.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val _state = MutableStateFlow(UserData())
    val state: StateFlow<UserData> = _state.asStateFlow()

    private lateinit var appContext: Context

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        val json = runBlocking { appContext.settingsDataStore.data.first()[KEY] }
        _state.value = json?.let {
            runCatching { DatasetJson.decodeFromString(UserData.serializer(), it) }.getOrNull()
        } ?: UserData()
    }

    fun addCamera(camera: SpeedCamera) = mutate { it.copy(cameras = it.cameras.filter { c -> c.id != camera.id } + camera) }

    fun removeCamera(id: String) = mutate { it.copy(cameras = it.cameras.filter { c -> c.id != id }) }

    fun suppress(camera: SpeedCamera) = mutate { d ->
        if (d.suppressed.any { it.id == camera.id }) d
        else d.copy(suppressed = d.suppressed + SuppressedMark.of(camera))
    }

    fun unsuppress(camera: SpeedCamera) = mutate { d ->
        d.copy(suppressed = d.suppressed.filterNot { com.wayguard.core.model.Suppression.matches(camera, it) })
    }

    fun clearSuppressed() = mutate { it.copy(suppressed = emptyList()) }

    private fun mutate(transform: (UserData) -> UserData) {
        val next = _state.updateAndGet(transform)
        scope.launch {
            val json = DatasetJson.encodeToString(UserData.serializer(), next)
            appContext.settingsDataStore.edit { it[KEY] = json }
        }
    }
}
