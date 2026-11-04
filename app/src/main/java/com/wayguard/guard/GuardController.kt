package com.wayguard.guard

import com.wayguard.core.engine.ActiveCamera
import com.wayguard.core.engine.SectionStatus
import com.waymap.geo.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Live guard state shown by the UI, notification and floating panel. */
data class GuardState(
    val running: Boolean = false,
    val position: LatLng? = null,
    val speedKmh: Double? = null,
    val heading: Double? = null,
    /** A recent GPS fix exists (false while searching / in a tunnel). */
    val gpsOk: Boolean = false,
    /** The nearest camera being approached. */
    val active: ActiveCamera? = null,
    /** Progress through an average-speed section. */
    val section: SectionStatus? = null,
    /** The last announcement, for on-screen display. */
    val lastMessage: String? = null,
    /** Shown when guarding can't work (GPS off, permission revoked…). */
    val error: String? = null,
) {
    /** Over the limit of the camera ahead, or over the section average. */
    val overspeed: Boolean get() = active?.overspeed == true || section?.over == true
}

/** Single source of truth for [GuardState]; written by [GuardService]. */
object GuardController {
    private val _state = MutableStateFlow(GuardState())
    val state: StateFlow<GuardState> = _state.asStateFlow()

    val current: GuardState get() = _state.value

    fun update(transform: (GuardState) -> GuardState) = _state.update(transform)

    /** Back to idle, keeping the last position for the map. */
    fun reset() = _state.update { GuardState(position = it.position) }
}
