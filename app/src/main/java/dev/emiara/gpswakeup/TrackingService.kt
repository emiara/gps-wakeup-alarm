package dev.emiara.gpswakeup

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Foreground service that watches your position and rings when you get near the stop.
 *
 * Deliberately built on [LocationManager] rather than Google Play Services so the app works
 * on any Android device, including de-Googled ones, with no API key and no extra runtime
 * that could be missing at 4am.
 */
class TrackingService : Service(), LocationListener {

    companion object {
        private const val TAG = "TrackingService"

        const val ACTION_START = "dev.emiara.gpswakeup.START"
        const val ACTION_STOP = "dev.emiara.gpswakeup.STOP"
        const val ACTION_TRIGGER_ALARM = "dev.emiara.gpswakeup.TRIGGER_ALARM"
        const val ACTION_TEST_ALARM = "dev.emiara.gpswakeup.TEST_ALARM"
        const val ACTION_SNOOZE = "dev.emiara.gpswakeup.SNOOZE"
        const val ACTION_DISMISS = "dev.emiara.gpswakeup.DISMISS"

        const val EXTRA_STOP_ID = "stop_id"
        const val EXTRA_REASON = "reason"

        /** True while the service instance is alive; the watchdog uses this. */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun arm(ctx: Context, stopId: String) {
            val backstopMinutes = Prefs.backstopMinutes(ctx)
            val backstopAt =
                if (backstopMinutes > 0) System.currentTimeMillis() + backstopMinutes * 60_000L else 0L
            Prefs.arm(ctx, stopId, backstopAt)
            Alarms.scheduleWatchdog(ctx)
            Alarms.scheduleBackstop(ctx, backstopAt)
            send(ctx, Intent(ctx, TrackingService::class.java).setAction(ACTION_START).putExtra(EXTRA_STOP_ID, stopId))
        }

        /** Arm a saved route, starting at its first leg that still has a stop. */
        fun armRoute(ctx: Context, routeId: String) {
            val route = Prefs.routeById(ctx, routeId) ?: return
            val index = route.legs.indexOfFirst { Prefs.stopById(ctx, it.stopId) != null }
            if (index < 0) return
            val stopId = route.legs[index].stopId
            val backstopMinutes = Prefs.backstopMinutes(ctx)
            val backstopAt =
                if (backstopMinutes > 0) System.currentTimeMillis() + backstopMinutes * 60_000L else 0L
            Prefs.arm(ctx, stopId, backstopAt, routeId = routeId, legIndex = index)
            Alarms.scheduleWatchdog(ctx)
            Alarms.scheduleBackstop(ctx, backstopAt)
            send(ctx, Intent(ctx, TrackingService::class.java).setAction(ACTION_START).putExtra(EXTRA_STOP_ID, stopId))
        }

        fun disarm(ctx: Context) {
            Prefs.disarm(ctx)
            Alarms.cancelAll(ctx)
            send(ctx, Intent(ctx, TrackingService::class.java).setAction(ACTION_STOP))
        }

        fun send(ctx: Context, intent: Intent) {
            runCatching { ContextCompat.startForegroundService(ctx, intent) }
                .onFailure { Log.w(TAG, "Could not start service: $it") }
        }

        fun sendAction(ctx: Context, action: String, reason: String? = null) {
            val i = Intent(ctx, TrackingService::class.java).setAction(action)
            if (reason != null) i.putExtra(EXTRA_REASON, reason)
            send(ctx, i)
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var locationManager: LocationManager
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null

    private var target: Stop? = null
    private var currentIntervalMs: Long = -1L
    private var closestSoFar: Float = Float.MAX_VALUE
    private var lastFixElapsed: Long = 0L
    private var fixCount: Int = 0
    private var alarming = false
    private var testMode = false

    private var mediaPlayer: MediaPlayer? = null
    private val fallbackTone = FallbackTone()
    private var vibrator: Vibrator? = null
    private var audioRoute: AudioRoute = AudioRoute(AlarmOutput.SPEAKER, null)

    /** Headphones coming and going changes both the status text and how we ring. */
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) =
            onAudioRouteChanged()

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) =
            onAudioRouteChanged()
    }

    private val ticker = object : Runnable {
        override fun run() {
            refreshNotification()
            handler.postDelayed(this, 20_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService<VibratorManager>()?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService<Vibrator>()
        }

        val pm = getSystemService<PowerManager>()
        cpuWakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gpswakeup:tracking")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }

        refreshRoute()
        runCatching {
            getSystemService<AudioManager>()?.registerAudioDeviceCallback(audioDeviceCallback, handler)
        }

        // Show something immediately — Android gives us only a few seconds to call
        // startForeground() after startForegroundService().
        goForeground(buildTrackingNotification())
        handler.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_STOP -> {
                stopAlarmSound()
                stopEverything()
                return START_NOT_STICKY
            }

            ACTION_DISMISS -> {
                stopAlarmSound()
                if (advanceRouteOrDisarm()) return START_STICKY
                stopEverything()
                return START_NOT_STICKY
            }

            ACTION_SNOOZE -> {
                snooze()
                return START_STICKY
            }

            ACTION_TEST_ALARM -> {
                testMode = true
                startAlarm(getString(R.string.reason_test))
                return START_STICKY
            }

            ACTION_TRIGGER_ALARM -> {
                val reason = intent?.getStringExtra(EXTRA_REASON)
                    ?: getString(R.string.reason_backstop)
                resumeTargetIfNeeded(intent?.getStringExtra(EXTRA_STOP_ID))
                startAlarm(reason)
                return START_STICKY
            }

            else -> {
                resumeTargetIfNeeded(intent?.getStringExtra(EXTRA_STOP_ID))
            }
        }

        if (target == null) {
            // Nothing armed (or the stop was deleted) — nothing to do.
            stopEverything()
            return START_NOT_STICKY
        }

        if (Prefs.isAlarming(this) && !alarming) {
            // The process was killed while ringing. Pick it back up.
            startAlarm(getString(R.string.reason_resumed))
            return START_STICKY
        }

        startLocationUpdates(force = true)
        Alarms.scheduleWatchdog(this)
        refreshNotification()
        return START_STICKY
    }

    private fun resumeTargetIfNeeded(stopId: String?) {
        val id = stopId ?: Prefs.armedStopId(this)
        val stop = Prefs.stopById(this, id)
        if (stop != null && stop.id != target?.id) {
            target = stop
            closestSoFar = Float.MAX_VALUE
            fixCount = 0
            currentIntervalMs = -1L
        } else if (stop != null) {
            target = stop
        }
        val route = Prefs.armedRoute(this)
        TrackerState.update {
            it.copy(
                serviceRunning = true,
                targetName = target?.name,
                targetRadius = target?.radiusMeters ?: Prefs.DEFAULT_RADIUS,
                backstopAtMillis = Prefs.backstopAt(this),
                routeName = route?.name,
                legIndex = if (route == null) 0 else Prefs.armedLegIndex(this),
                legCount = route?.legs?.size ?: 0,
                nextLegName = Prefs.nextLeg(this)?.name,
            )
        }
    }

    private fun refreshRoute() {
        audioRoute = AlarmAudio.currentRoute(this)
        TrackerState.update {
            it.copy(alarmOutput = audioRoute.output, outputName = audioRoute.deviceName)
        }
    }

    /**
     * If the headset appears or disappears mid-alarm, re-route the sound rather than
     * carrying on playing into a device that is no longer there.
     */
    private fun onAudioRouteChanged() {
        val previous = audioRoute.output
        refreshRoute()
        if (alarming && audioRoute.output != previous) {
            applyAlarmVolume()
            playAlarmSound()
            startVibration()
        }
        refreshNotification()
    }

    // ---- location ----------------------------------------------------------

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun activeProviders(): List<String> {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) wanted += LocationManager.FUSED_PROVIDER
        wanted += LocationManager.GPS_PROVIDER
        wanted += LocationManager.NETWORK_PROVIDER
        val available = runCatching { locationManager.allProviders }.getOrDefault(emptyList())
        return wanted.filter { it in available }
    }

    private fun startLocationUpdates(force: Boolean) {
        if (!hasLocationPermission()) {
            TrackerState.update { it.copy(message = getString(R.string.msg_no_location_permission)) }
            refreshNotification()
            return
        }

        val distance = TrackerState.status.value.distanceMeters
        val radius = target?.radiusMeters ?: Prefs.DEFAULT_RADIUS
        val interval = intervalFor(distance, radius)
        if (!force && interval == currentIntervalMs) return
        currentIntervalMs = interval

        runCatching { locationManager.removeUpdates(this) }

        var registered = 0
        for (provider in activeProviders()) {
            try {
                locationManager.requestLocationUpdates(
                    provider,
                    interval,
                    0f,
                    this,
                    Looper.getMainLooper(),
                )
                registered++
                // Seed the display with whatever the system already knows.
                runCatching { locationManager.getLastKnownLocation(provider) }
                    .getOrNull()
                    ?.let { last ->
                        if (fixCount == 0) updateFromLocation(last, isSeed = true)
                    }
            } catch (e: SecurityException) {
                Log.w(TAG, "No permission for $provider: $e")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Provider $provider unusable: $e")
            }
        }

        TrackerState.update {
            it.copy(
                message = if (registered == 0) getString(R.string.msg_no_providers) else null,
            )
        }
    }

    /** How often to ask for a fix, based on how far out we still are. */
    private fun intervalFor(distanceMeters: Float?, radius: Int): Long {
        val d = distanceMeters ?: return 15_000L
        return when {
            d > 20_000f -> 120_000L
            d > 10_000f -> 60_000L
            d > 5_000f -> 30_000L
            d > 2_000f -> 15_000L
            d > 1_000f -> 8_000L
            d > radius * 3f -> 4_000L
            else -> 1_000L
        }
    }

    override fun onLocationChanged(location: Location) {
        updateFromLocation(location, isSeed = false)
    }

    override fun onProviderEnabled(provider: String) {
        startLocationUpdates(force = true)
    }

    override fun onProviderDisabled(provider: String) {
        refreshNotification()
    }

    @Deprecated("Required by the LocationListener interface on older API levels")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        // no-op
    }

    private fun updateFromLocation(location: Location, isSeed: Boolean) {
        val stop = target ?: return

        // A seed fix can be hours old; use it for display only.
        if (isSeed && location.time > 0 &&
            System.currentTimeMillis() - location.time > 10 * 60_000L
        ) {
            return
        }

        val results = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, stop.lat, stop.lon, results)
        val distance = results[0]
        val accuracy = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE

        if (!isSeed) {
            fixCount++
            lastFixElapsed = SystemClock.elapsedRealtime()
        }

        val previousClosest = closestSoFar
        if (distance < closestSoFar) closestSoFar = distance

        TrackerState.update {
            it.copy(
                serviceRunning = true,
                targetName = stop.name,
                targetRadius = stop.radiusMeters,
                distanceMeters = distance,
                closestSoFarMeters = if (closestSoFar == Float.MAX_VALUE) null else closestSoFar,
                accuracyMeters = if (accuracy == Float.MAX_VALUE) null else accuracy,
                provider = location.provider,
                lastFixAtMillis = if (isSeed) it.lastFixAtMillis else System.currentTimeMillis(),
                fixCount = fixCount,
            )
        }

        if (!isSeed) {
            if (!alarming) checkTrigger(stop, distance, accuracy, previousClosest)
            // Seeds are handled inside startLocationUpdates(); re-entering from there
            // would recurse.
            startLocationUpdates(force = false)
        }
        refreshNotification()
    }

    private fun checkTrigger(stop: Stop, distance: Float, accuracy: Float, previousClosest: Float) {
        val radius = stop.radiusMeters.toFloat()

        // Inside the circle. Guard against wildly imprecise fixes claiming we've arrived,
        // unless the whole error circle is inside the radius anyway.
        val trustworthy = accuracy <= max(radius, 150f) || distance + accuracy <= radius
        if (distance <= radius && trustworthy) {
            startAlarm(getString(R.string.reason_arrived, formatDistance(distance)))
            return
        }

        // Missed-stop guard: we got close and are now clearly moving away again.
        if (Prefs.missedStopGuard(this) &&
            previousClosest <= radius * 2.5f &&
            distance > previousClosest + 200f &&
            accuracy <= 200f
        ) {
            startAlarm(getString(R.string.reason_leaving, formatDistance(previousClosest)))
        }
    }

    // ---- the alarm ---------------------------------------------------------

    private fun startAlarm(reason: String) {
        if (alarming) return
        alarming = true
        Prefs.setAlarming(this, true)
        Alarms.cancelBackstop(this)

        TrackerState.update { it.copy(alarming = true, alarmReason = reason, snoozedUntilMillis = 0L) }

        refreshRoute()
        wakeScreen()
        applyAlarmVolume()
        playAlarmSound()
        startVibration()

        goForeground(buildAlarmNotification(reason))

        // Belt and braces: the full-screen intent above covers a locked/off screen, this
        // covers the case where the app is already in the foreground.
        runCatching {
            startActivity(
                Intent(this, AlarmActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        val pm = getSystemService<PowerManager>() ?: return
        runCatching { screenWakeLock?.takeIf { it.isHeld }?.release() }
        screenWakeLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "gpswakeup:screen",
        ).apply {
            setReferenceCounted(false)
            runCatching { acquire(60_000L) }
        }
    }

    /**
     * Set the alarm stream volume for the current output: full blast on the phone speaker,
     * capped when it is going into your ears.
     */
    private fun applyAlarmVolume() {
        val target = AlarmAudio.targetVolumeIndex(this, audioRoute) ?: return
        val am = getSystemService<AudioManager>() ?: return
        runCatching {
            if (Prefs.previousAlarmVolume(this) < 0) {
                Prefs.setPreviousAlarmVolume(this, am.getStreamVolume(AudioManager.STREAM_ALARM))
            }
            am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
        }
    }

    private fun restoreAlarmVolume() {
        val previous = Prefs.previousAlarmVolume(this)
        if (previous < 0) return
        val am = getSystemService<AudioManager>()
        runCatching { am?.setStreamVolume(AudioManager.STREAM_ALARM, previous, 0) }
        Prefs.setPreviousAlarmVolume(this, -1)
    }

    private fun alarmAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun playAlarmSound() {
        stopAlarmSoundOnly()
        val uri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val preferred = AlarmAudio.deviceById(this, audioRoute.deviceId)

        val fromSystem = uri?.let { u ->
            runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(alarmAudioAttributes())
                    setDataSource(this@TrackingService, u)
                    isLooping = true
                    prepare()
                    // Explicitly aim at the headset; many devices otherwise keep alarms on
                    // the phone speaker even with Bluetooth connected.
                    if (preferred != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        runCatching { setPreferredDevice(preferred) }
                    }
                    start()
                }
            }.getOrNull()
        }

        mediaPlayer = fromSystem
        if (fromSystem != null) return

        // No usable ringtone on this device — synthesise one rather than go silent.
        Log.w(TAG, "No system alarm sound available; falling back to the generated tone")
        if (!fallbackTone.start()) {
            Log.e(TAG, "No alarm sound could be played at all; relying on vibration")
        }
    }

    private fun startVibration() {
        // Forced on when the sound is capped for a headset.
        if (!AlarmAudio.shouldVibrate(this, audioRoute)) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val pattern = longArrayOf(0, 700, 400, 700, 400, 700, 1200)
        runCatching {
            v.vibrate(VibrationEffect.createWaveform(pattern, 0))
        }
    }

    private fun stopAlarmSoundOnly() {
        mediaPlayer?.runCatching {
            if (isPlaying) this.stop()
            reset()
            release()
        }
        mediaPlayer = null
        fallbackTone.stop()
    }

    private fun stopAlarmSound() {
        alarming = false
        Prefs.setAlarming(this, false)
        stopAlarmSoundOnly()
        runCatching { vibrator?.cancel() }
        restoreAlarmVolume()
        runCatching { screenWakeLock?.takeIf { it.isHeld }?.release() }
        screenWakeLock = null
        TrackerState.update { it.copy(alarming = false, alarmReason = null) }
        if (testMode) {
            testMode = false
            if (!Prefs.isArmed(this)) {
                stopEverything()
                return
            }
        }
        goForeground(buildTrackingNotification())
    }

    /**
     * On a route, dismissing one leg's alarm arms the next automatically — the whole point
     * of saving a route. Returns false when the journey is finished.
     */
    private fun advanceRouteOrDisarm(): Boolean {
        if (!Prefs.advanceToNextLeg(this)) {
            Prefs.disarm(this)
            Alarms.cancelAll(this)
            return false
        }

        // Fresh leg: forget how close we got to the previous stop.
        target = null
        closestSoFar = Float.MAX_VALUE
        fixCount = 0
        currentIntervalMs = -1L
        resumeTargetIfNeeded(null)

        // The time backstop is per leg, counted from the moment this leg started.
        val minutes = Prefs.backstopMinutes(this)
        val backstopAt =
            if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
        Prefs.setBackstopAt(this, backstopAt)
        if (backstopAt > 0) Alarms.scheduleBackstop(this, backstopAt) else Alarms.cancelBackstop(this)

        Alarms.scheduleWatchdog(this)
        startLocationUpdates(force = true)
        refreshNotification()
        return true
    }

    private fun snooze() {
        val minutes = Prefs.snoozeMinutes(this)
        val until = System.currentTimeMillis() + minutes * 60_000L
        stopAlarmSound()
        Alarms.scheduleSnooze(this, until)
        TrackerState.update { it.copy(snoozedUntilMillis = until) }
        goForeground(buildTrackingNotification())
    }

    // ---- notifications -----------------------------------------------------

    private fun goForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, Notifications.ID_TRACKING, notification, type)
        }.onFailure { Log.e(TAG, "startForeground failed: $it") }
    }

    private fun pendingActivity(cls: Class<*>, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            this,
            requestCode,
            Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun pendingService(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, TrackingService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun buildTrackingNotification(): Notification {
        val status = TrackerState.status.value
        val stop = target
        val title = if (stop == null) {
            getString(R.string.notif_idle_title)
        } else {
            getString(R.string.notif_tracking_title, stop.name)
        }

        val body = buildString {
            val d = status.distanceMeters
            if (d != null) {
                append(getString(R.string.notif_distance, formatDistance(d)))
            } else {
                append(getString(R.string.notif_waiting_for_fix))
            }
            status.accuracyMeters?.let { append(getString(R.string.notif_accuracy, it.roundToInt())) }
            if (status.snoozedUntilMillis > System.currentTimeMillis()) {
                append(getString(R.string.notif_snoozed))
            }
            val stale = lastFixElapsed > 0 && SystemClock.elapsedRealtime() - lastFixElapsed > 5 * 60_000L
            if (stale) append(getString(R.string.notif_stale_fix))
            status.nextLegName?.let { append(getString(R.string.notif_next_leg, it)) }
            status.message?.let { append(" • ").append(it) }
        }

        // Collapsed: the short distance line. Expanded: the same plain-English sentence
        // the app shows, so the notification alone tells you what is going on.
        val sentence = StatusSentence.build(this, status, Prefs.isArmed(this))

        return NotificationCompat.Builder(this, Notifications.CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_stat_bus)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(sentence))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pendingActivity(MainActivity::class.java, 10))
            .addAction(
                0,
                getString(R.string.action_cancel_alarm),
                pendingService(ACTION_DISMISS, 11),
            )
            .build()
    }

    private fun buildAlarmNotification(reason: String): Notification {
        val fullScreen = PendingIntent.getActivity(
            this,
            20,
            Intent(this, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, Notifications.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_stat_bus)
            .setContentTitle(getString(R.string.alarm_title))
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .addAction(0, getString(R.string.action_snooze), pendingService(ACTION_SNOOZE, 21))
            .addAction(0, getString(R.string.action_im_awake), pendingService(ACTION_DISMISS, 22))
            .build()
    }

    private fun refreshNotification() {
        if (alarming) return
        goForeground(buildTrackingNotification())
    }

    private fun formatDistance(meters: Float): String =
        if (meters >= 1000f) {
            getString(R.string.distance_km, meters / 1000f)
        } else {
            getString(R.string.distance_m, meters.roundToInt())
        }

    // ---- teardown ----------------------------------------------------------

    private fun stopEverything() {
        runCatching { locationManager.removeUpdates(this) }
        handler.removeCallbacks(ticker)
        TrackerState.reset()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        stopAlarmSoundOnly()
        runCatching { vibrator?.cancel() }
        runCatching { locationManager.removeUpdates(this) }
        runCatching {
            getSystemService<AudioManager>()?.unregisterAudioDeviceCallback(audioDeviceCallback)
        }
        handler.removeCallbacks(ticker)
        runCatching { cpuWakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { screenWakeLock?.takeIf { it.isHeld }?.release() }
        TrackerState.update { it.copy(serviceRunning = false) }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away must not cancel the alarm — make sure we come back.
        if (Prefs.isArmed(this)) Alarms.scheduleWatchdog(this, delayMillis = 5_000L)
        super.onTaskRemoved(rootIntent)
    }
}
