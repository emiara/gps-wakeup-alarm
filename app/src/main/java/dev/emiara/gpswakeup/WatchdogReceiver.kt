package dev.emiara.gpswakeup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Handles all three AlarmManager alarms. Each firing also re-arms the watchdog, so tracking
 * keeps getting resurrected for as long as an alarm is armed.
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        when (intent.action) {
            Alarms.ACTION_BACKSTOP -> {
                if (!Prefs.isArmed(ctx)) return
                Log.i(TAG, "Backstop time reached — ringing")
                TrackingService.sendAction(
                    ctx,
                    TrackingService.ACTION_TRIGGER_ALARM,
                    ctx.getString(R.string.reason_backstop),
                )
            }

            Alarms.ACTION_SNOOZE_FIRE -> {
                if (!Prefs.isArmed(ctx)) return
                TrackingService.sendAction(
                    ctx,
                    TrackingService.ACTION_TRIGGER_ALARM,
                    ctx.getString(R.string.reason_snooze_over),
                )
            }

            else -> {
                if (!Prefs.isArmed(ctx)) return
                if (!TrackingService.isRunning) {
                    Log.w(TAG, "Tracking service was not running — restarting it")
                    TrackingService.send(
                        ctx,
                        Intent(ctx, TrackingService::class.java)
                            .setAction(TrackingService.ACTION_START)
                            .putExtra(TrackingService.EXTRA_STOP_ID, Prefs.armedStopId(ctx)),
                    )
                }
                Alarms.scheduleWatchdog(ctx)
            }
        }
    }

    private companion object {
        const val TAG = "WatchdogReceiver"
    }
}
