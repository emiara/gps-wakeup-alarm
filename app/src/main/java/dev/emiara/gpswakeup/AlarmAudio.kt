package dev.emiara.gpswakeup

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.getSystemService
import kotlin.math.ceil

/** Where the alarm sound will actually come out. */
enum class AlarmOutput { SPEAKER, BLUETOOTH, WIRED }

data class AudioRoute(
    val output: AlarmOutput,
    /** Best-effort device name; null when Android won't tell us without extra permissions. */
    val deviceName: String?,
    val deviceId: Int = 0,
) {
    val isHeadset: Boolean get() = output != AlarmOutput.SPEAKER
}

/**
 * Alarm output routing and volume.
 *
 * When headphones are connected the alarm is sent to them and the volume is capped, because
 * a maxed-out alarm stream straight into your ears is genuinely harmful. Vibration is forced
 * on in that case so a capped alarm still wakes you.
 */
object AlarmAudio {

    private val BLUETOOTH_TYPES: Set<Int> by lazy {
        buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(AudioDeviceInfo.TYPE_HEARING_AID)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
        }
    }

    private val WIRED_TYPES = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
    )

    /** What the alarm would play through right now. */
    fun currentRoute(ctx: Context): AudioRoute {
        val am = ctx.getSystemService<AudioManager>()
            ?: return AudioRoute(AlarmOutput.SPEAKER, null)

        val outputs = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            .getOrNull()
            .orEmpty()

        // Bluetooth wins over wired: if both are attached you are almost certainly wearing
        // the wireless ones on a night bus.
        outputs.firstOrNull { it.type in BLUETOOTH_TYPES }?.let {
            return AudioRoute(AlarmOutput.BLUETOOTH, nameOf(it), it.id)
        }
        outputs.firstOrNull { it.type in WIRED_TYPES }?.let {
            return AudioRoute(AlarmOutput.WIRED, nameOf(it), it.id)
        }
        return AudioRoute(AlarmOutput.SPEAKER, null)
    }

    fun deviceById(ctx: Context, id: Int): AudioDeviceInfo? {
        if (id == 0) return null
        val am = ctx.getSystemService<AudioManager>() ?: return null
        return runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            .getOrNull()
            ?.firstOrNull { it.id == id }
    }

    /**
     * Reading a Bluetooth device's real name needs BLUETOOTH_CONNECT on Android 12+, which
     * this app deliberately does not ask for. A blank or generic name is fine — the status
     * text falls back to "your headphones".
     */
    private fun nameOf(device: AudioDeviceInfo): String? =
        runCatching { device.productName?.toString()?.trim() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && !it.equals(Build.MODEL, ignoreCase = true) }

    /**
     * The alarm volume index to use.
     *
     * Android does not expose the OS-level "safe media volume" figure to apps, so on a
     * headset this caps at a percentage of the stream maximum instead — conservative by
     * default, and adjustable. On the phone speaker the existing force-to-max behaviour
     * applies, since there is no hearing risk there.
     */
    fun targetVolumeIndex(ctx: Context, route: AudioRoute): Int? {
        val am = ctx.getSystemService<AudioManager>() ?: return null
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        if (max <= 0) return null

        val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { am.getStreamMinVolume(AudioManager.STREAM_ALARM) }.getOrDefault(0)
        } else {
            0
        }

        if (route.isHeadset && Prefs.limitHeadsetVolume(ctx)) {
            val fraction = Prefs.headsetVolumePercent(ctx).coerceIn(10, 100) / 100f
            // Always at least one step above silent, however low the percentage is set.
            return ceil(max * fraction).toInt().coerceIn(min + 1, max)
        }

        if (Prefs.forceMaxVolume(ctx)) return max
        return null // leave whatever the user already has
    }

    /** Vibration is not optional on a headset — the sound is deliberately quieter there. */
    fun shouldVibrate(ctx: Context, route: AudioRoute): Boolean =
        route.isHeadset || Prefs.vibrate(ctx)
}
