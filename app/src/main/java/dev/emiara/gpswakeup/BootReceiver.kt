package dev.emiara.gpswakeup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms tracking after a reboot or an app update, if an alarm was still armed. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        if (!Prefs.isArmed(ctx)) return

        val stopId = Prefs.armedStopId(ctx) ?: return
        Alarms.scheduleWatchdog(ctx, delayMillis = 10_000L)
        Prefs.backstopAt(ctx).takeIf { it > System.currentTimeMillis() }
            ?.let { Alarms.scheduleBackstop(ctx, it) }

        TrackingService.send(
            ctx,
            Intent(ctx, TrackingService::class.java)
                .setAction(TrackingService.ACTION_START)
                .putExtra(TrackingService.EXTRA_STOP_ID, stopId),
        )
    }
}
