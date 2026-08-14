package dev.emiara.gpswakeup

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.getSystemService

/**
 * AlarmManager plumbing. Three separate alarms keep the app honest:
 *
 *  - watchdog: fires every few minutes and restarts tracking if the OS killed the service.
 *  - backstop: an unconditional "wake me by this time" alarm that works with no GPS at all.
 *  - snooze:   re-rings after the snooze interval.
 *
 * All three use exact alarms, which also grants the app an exemption from the
 * background foreground-service-start restrictions on Android 12+.
 */
object Alarms {

    private const val TAG = "Alarms"

    const val ACTION_WATCHDOG = "dev.emiara.gpswakeup.WATCHDOG"
    const val ACTION_BACKSTOP = "dev.emiara.gpswakeup.BACKSTOP"
    const val ACTION_SNOOZE_FIRE = "dev.emiara.gpswakeup.SNOOZE_FIRE"

    private const val RC_WATCHDOG = 100
    private const val RC_BACKSTOP = 101
    private const val RC_SNOOZE = 102

    const val WATCHDOG_INTERVAL_MS = 4 * 60_000L

    private fun manager(ctx: Context): AlarmManager? = ctx.getSystemService<AlarmManager>()

    private fun pending(ctx: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            requestCode,
            Intent(ctx, WatchdogReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun canScheduleExact(ctx: Context): Boolean {
        val am = manager(ctx) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true
    }

    private fun setExact(ctx: Context, triggerAtMillis: Long, pi: PendingIntent) {
        val am = manager(ctx) ?: return
        try {
            if (canScheduleExact(ctx)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            } else {
                // Still wakes the device out of Doze, just with less timing precision.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm refused, falling back: $e")
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi) }
        }
    }

    fun scheduleWatchdog(ctx: Context, delayMillis: Long = WATCHDOG_INTERVAL_MS) {
        setExact(
            ctx,
            System.currentTimeMillis() + delayMillis,
            pending(ctx, ACTION_WATCHDOG, RC_WATCHDOG),
        )
    }

    fun scheduleBackstop(ctx: Context, atMillis: Long) {
        cancelBackstop(ctx)
        if (atMillis <= System.currentTimeMillis()) return
        Prefs.setBackstopAt(ctx, atMillis)
        setExact(ctx, atMillis, pending(ctx, ACTION_BACKSTOP, RC_BACKSTOP))
    }

    fun cancelBackstop(ctx: Context) {
        runCatching { manager(ctx)?.cancel(pending(ctx, ACTION_BACKSTOP, RC_BACKSTOP)) }
    }

    fun scheduleSnooze(ctx: Context, atMillis: Long) {
        setExact(ctx, atMillis, pending(ctx, ACTION_SNOOZE_FIRE, RC_SNOOZE))
    }

    fun cancelAll(ctx: Context) {
        val am = manager(ctx) ?: return
        runCatching { am.cancel(pending(ctx, ACTION_WATCHDOG, RC_WATCHDOG)) }
        runCatching { am.cancel(pending(ctx, ACTION_BACKSTOP, RC_BACKSTOP)) }
        runCatching { am.cancel(pending(ctx, ACTION_SNOOZE_FIRE, RC_SNOOZE)) }
    }
}
