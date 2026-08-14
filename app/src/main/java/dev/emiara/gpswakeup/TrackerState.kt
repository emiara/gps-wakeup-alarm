package dev.emiara.gpswakeup

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class TrackingStatus(
    val serviceRunning: Boolean = false,
    val targetName: String? = null,
    val targetRadius: Int = Prefs.DEFAULT_RADIUS,
    val distanceMeters: Float? = null,
    val closestSoFarMeters: Float? = null,
    val accuracyMeters: Float? = null,
    val provider: String? = null,
    val lastFixAtMillis: Long = 0L,
    val fixCount: Int = 0,
    val routeName: String? = null,
    val legIndex: Int = 0,
    val legCount: Int = 0,
    val nextLegName: String? = null,
    val alarming: Boolean = false,
    val alarmReason: String? = null,
    val snoozedUntilMillis: Long = 0L,
    val backstopAtMillis: Long = 0L,
    val message: String? = null,
)

/**
 * Live tracking state shared between the service and the UI. Same process, so a plain
 * singleton flow is enough — no binder or broadcast plumbing needed.
 */
object TrackerState {
    private val _status = MutableStateFlow(TrackingStatus())
    val status: StateFlow<TrackingStatus> = _status

    fun update(block: (TrackingStatus) -> TrackingStatus) {
        _status.value = block(_status.value)
    }

    fun reset() {
        _status.value = TrackingStatus()
    }
}
