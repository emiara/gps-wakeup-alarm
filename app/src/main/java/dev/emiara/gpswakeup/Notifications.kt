package dev.emiara.gpswakeup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService

object Notifications {

    const val CHANNEL_TRACKING = "tracking"
    const val CHANNEL_ALARM = "alarm"

    const val ID_TRACKING = 1001
    const val ID_ALARM = 1002

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService<NotificationManager>() ?: return

        val tracking = NotificationChannel(
            CHANNEL_TRACKING,
            ctx.getString(R.string.channel_tracking),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = ctx.getString(R.string.channel_tracking_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(tracking)

        // MAX importance so the full-screen intent is honoured. Sound and vibration are
        // handled by the service on the alarm stream, so the channel itself stays silent.
        val alarm = NotificationChannel(
            CHANNEL_ALARM,
            ctx.getString(R.string.channel_alarm),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = ctx.getString(R.string.channel_alarm_desc)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            // Only takes effect once the user grants Do Not Disturb access.
            runCatching { setBypassDnd(true) }
        }
        nm.createNotificationChannel(alarm)
    }
}
