package dev.emiara.gpswakeup

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService

enum class Severity { REQUIRED, RECOMMENDED }

data class ReadinessItem(
    val id: String,
    val title: String,
    val detail: String,
    val ok: Boolean,
    val severity: Severity,
    val actionLabel: String?,
    /** Manual steps can't be verified by the app; the user ticks them off. */
    val manual: Boolean = false,
)

/**
 * Everything that has to be true for the alarm to actually go off, checked one by one so
 * the user can see and fix each item before relying on the app.
 */
object Readiness {

    const val ID_NOTIFICATIONS = "notifications"
    const val ID_LOCATION_FINE = "location_fine"
    const val ID_LOCATION_BACKGROUND = "location_background"
    const val ID_LOCATION_SERVICES = "location_services"
    const val ID_BATTERY = "battery"
    const val ID_EXACT_ALARM = "exact_alarm"
    const val ID_FULL_SCREEN = "full_screen"
    const val ID_ALARM_CHANNEL = "alarm_channel"
    const val ID_VOLUME = "volume"
    const val ID_DND = "dnd"
    const val ID_OEM = "oem"

    fun evaluate(ctx: Context): List<ReadinessItem> {
        val items = mutableListOf<ReadinessItem>()

        items += ReadinessItem(
            id = ID_NOTIFICATIONS,
            title = ctx.getString(R.string.chk_notifications),
            detail = ctx.getString(R.string.chk_notifications_detail),
            ok = NotificationManagerCompat.from(ctx).areNotificationsEnabled(),
            severity = Severity.REQUIRED,
            actionLabel = ctx.getString(R.string.action_grant),
        )

        val fine = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        items += ReadinessItem(
            id = ID_LOCATION_FINE,
            title = ctx.getString(R.string.chk_location_precise),
            detail = ctx.getString(R.string.chk_location_precise_detail),
            ok = fine,
            severity = Severity.REQUIRED,
            actionLabel = ctx.getString(R.string.action_grant),
        )

        val background = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            true
        }
        items += ReadinessItem(
            id = ID_LOCATION_BACKGROUND,
            title = ctx.getString(R.string.chk_location_background),
            detail = ctx.getString(R.string.chk_location_background_detail),
            ok = background,
            severity = Severity.REQUIRED,
            actionLabel = ctx.getString(R.string.action_grant),
        )

        items += ReadinessItem(
            id = ID_LOCATION_SERVICES,
            title = ctx.getString(R.string.chk_location_services),
            detail = ctx.getString(R.string.chk_location_services_detail),
            ok = locationEnabled(ctx),
            severity = Severity.REQUIRED,
            actionLabel = ctx.getString(R.string.action_open),
        )

        items += ReadinessItem(
            id = ID_BATTERY,
            title = ctx.getString(R.string.chk_battery),
            detail = ctx.getString(R.string.chk_battery_detail),
            ok = ignoringBatteryOptimizations(ctx),
            severity = Severity.REQUIRED,
            actionLabel = ctx.getString(R.string.action_allow),
        )

        items += ReadinessItem(
            id = ID_EXACT_ALARM,
            title = ctx.getString(R.string.chk_exact_alarm),
            detail = ctx.getString(R.string.chk_exact_alarm_detail),
            ok = Alarms.canScheduleExact(ctx),
            severity = Severity.REQUIRED,
            actionLabel = ctx.getString(R.string.action_allow),
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = ctx.getSystemService<NotificationManager>()
            items += ReadinessItem(
                id = ID_FULL_SCREEN,
                title = ctx.getString(R.string.chk_full_screen),
                detail = ctx.getString(R.string.chk_full_screen_detail),
                ok = nm?.canUseFullScreenIntent() ?: false,
                severity = Severity.REQUIRED,
                actionLabel = ctx.getString(R.string.action_allow),
            )
        }

        items += ReadinessItem(
            id = ID_ALARM_CHANNEL,
            title = ctx.getString(R.string.chk_alarm_channel),
            detail = ctx.getString(R.string.chk_alarm_channel_detail),
            ok = alarmChannelUsable(ctx),
            severity = Severity.REQUIRED,
            actionLabel = ctx.getString(R.string.action_open),
        )

        items += ReadinessItem(
            id = ID_VOLUME,
            title = ctx.getString(R.string.chk_volume),
            detail = ctx.getString(R.string.chk_volume_detail),
            ok = alarmVolume(ctx) > 0 || Prefs.forceMaxVolume(ctx),
            severity = Severity.REQUIRED,
            actionLabel = ctx.getString(R.string.action_fix),
        )

        items += ReadinessItem(
            id = ID_DND,
            title = ctx.getString(R.string.chk_dnd),
            detail = ctx.getString(R.string.chk_dnd_detail),
            ok = dndAccessGranted(ctx),
            severity = Severity.RECOMMENDED,
            actionLabel = ctx.getString(R.string.action_allow),
        )

        if (oemNeedsAutostart()) {
            items += ReadinessItem(
                id = ID_OEM,
                title = ctx.getString(R.string.chk_oem, Build.MANUFACTURER),
                detail = ctx.getString(R.string.chk_oem_detail),
                ok = Prefs.manualStepDone(ctx, ID_OEM),
                severity = Severity.RECOMMENDED,
                actionLabel = ctx.getString(R.string.action_open),
                manual = true,
            )
        }

        return items
    }

    fun blockingCount(items: List<ReadinessItem>): Int =
        items.count { !it.ok && it.severity == Severity.REQUIRED }

    // ---- individual checks -------------------------------------------------

    private fun granted(ctx: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    fun locationEnabled(ctx: Context): Boolean {
        val lm = ctx.getSystemService<LocationManager>() ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ||
                runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }
                    .getOrDefault(false)
        }
    }

    fun ignoringBatteryOptimizations(ctx: Context): Boolean {
        val pm = ctx.getSystemService<PowerManager>() ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(ctx.packageName) }.getOrDefault(false)
    }

    fun alarmVolume(ctx: Context): Int =
        ctx.getSystemService<AudioManager>()?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 0

    fun maxAlarmVolume(ctx: Context): Int =
        ctx.getSystemService<AudioManager>()?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 0

    fun setAlarmVolumeToMax(ctx: Context) {
        val am = ctx.getSystemService<AudioManager>() ?: return
        runCatching {
            am.setStreamVolume(
                AudioManager.STREAM_ALARM,
                am.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0,
            )
        }
    }

    private fun dndAccessGranted(ctx: Context): Boolean =
        ctx.getSystemService<NotificationManager>()?.isNotificationPolicyAccessGranted ?: false

    private fun alarmChannelUsable(ctx: Context): Boolean {
        val nm = ctx.getSystemService<NotificationManager>() ?: return false
        val channel = nm.getNotificationChannel(Notifications.CHANNEL_ALARM) ?: return false
        return channel.importance >= NotificationManager.IMPORTANCE_DEFAULT
    }

    private fun oemNeedsAutostart(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return AUTOSTART_INTENTS.keys.any { m.contains(it) }
    }

    // ---- fixing ------------------------------------------------------------

    /** Returns the settings screen for a check, or null if it needs a runtime permission. */
    fun fixIntent(ctx: Context, id: String): Intent? = when (id) {
        ID_NOTIFICATIONS ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)

        ID_LOCATION_FINE, ID_LOCATION_BACKGROUND -> null // runtime permission

        ID_LOCATION_SERVICES -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)

        ID_BATTERY ->
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.fromParts("package", ctx.packageName, null))

        ID_EXACT_ALARM ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.fromParts("package", ctx.packageName, null))
            } else {
                null
            }

        ID_FULL_SCREEN ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    .setData(Uri.fromParts("package", ctx.packageName, null))
            } else {
                null
            }

        ID_ALARM_CHANNEL ->
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, Notifications.CHANNEL_ALARM)

        ID_VOLUME -> null // fixed in-app

        ID_DND -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

        ID_OEM -> oemCandidates(ctx).first()

        else -> null
    }

    /** Intents to try in order for a check, falling back to the app info screen. */
    fun fixIntents(ctx: Context, id: String): List<Intent> =
        if (id == ID_OEM) oemCandidates(ctx) else listOfNotNull(fixIntent(ctx, id), appDetailsIntent(ctx))

    fun appDetailsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", ctx.packageName, null))

    /** Best-effort deep links into the OEM "autostart" / "protected apps" screens. */
    private val AUTOSTART_INTENTS: Map<String, List<ComponentName>> = mapOf(
        "xiaomi" to listOf(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
        ),
        "redmi" to listOf(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
        ),
        "poco" to listOf(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
        ),
        "huawei" to listOf(
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity",
            ),
        ),
        "honor" to listOf(
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity",
            ),
        ),
        "oppo" to listOf(
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity",
            ),
            ComponentName(
                "com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity",
            ),
        ),
        "realme" to listOf(
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity",
            ),
        ),
        "oneplus" to listOf(
            ComponentName(
                "com.oneplus.security",
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            ),
        ),
        "vivo" to listOf(
            ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            ),
            ComponentName(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            ),
        ),
        "samsung" to listOf(
            ComponentName(
                "com.samsung.android.lool",
                "com.samsung.android.sm.battery.ui.BatteryActivity",
            ),
            ComponentName(
                "com.samsung.android.lool",
                "com.samsung.android.sm.ui.battery.BatteryActivity",
            ),
        ),
        "asus" to listOf(
            ComponentName(
                "com.asus.mobilemanager",
                "com.asus.mobilemanager.autostart.AutoStartActivity",
            ),
        ),
    )

    /**
     * Every candidate for this manufacturer, most specific first, with the generic app-info
     * screen last. Package visibility rules make [Intent.resolveActivity] unreliable for other
     * apps, so the caller just walks the list until one of them starts.
     */
    fun oemCandidates(ctx: Context): List<Intent> {
        val m = Build.MANUFACTURER.lowercase()
        val components = AUTOSTART_INTENTS.entries
            .firstOrNull { m.contains(it.key) }
            ?.value
            .orEmpty()
        return components.map { Intent().setComponent(it) } + appDetailsIntent(ctx)
    }
}
