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
    val alarmOutput: AlarmOutput = AlarmOutput.SPEAKER,
    val outputName: String? = null,
    val alarming: Boolean = false,
    val alarmReason: String? = null,
    val snoozedUntilMillis: Long = 0L,
    val message: String? = null,
)

/**
 * The handful of settings the theme depends on. Read straight from prefs the theme would
 * never recompose when they change, because it sits above everything that does.
 */
object UiPrefs {
    private val _dyslexiaFont = MutableStateFlow(true)
    val dyslexiaFont: StateFlow<Boolean> = _dyslexiaFont

    fun load(ctx: android.content.Context) {
        _dyslexiaFont.value = Prefs.dyslexiaFont(ctx)
    }

    fun setDyslexiaFont(ctx: android.content.Context, value: Boolean) {
        Prefs.setDyslexiaFont(ctx, value)
        _dyslexiaFont.value = value
    }
}

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
