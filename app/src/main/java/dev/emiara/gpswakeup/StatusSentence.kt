package dev.emiara.gpswakeup

import android.content.Context
import kotlin.math.roundToInt

/**
 * The whole state of the app, written out as sentences a half-asleep person can read.
 *
 * Everything else in the UI is numbers and toggles. This is the one place that just says,
 * in plain words, what is happening and what will happen next — so nobody has to work it
 * out from a distance readout at four in the morning.
 */
object StatusSentence {

    fun build(ctx: Context, status: TrackingStatus, armed: Boolean): String {
        val parts = mutableListOf<String>()

        if (status.alarming) {
            parts += ctx.getString(R.string.sentence_ringing, status.targetName ?: destination(ctx))
            status.nextLegName?.let { parts += ctx.getString(R.string.sentence_ringing_next, it) }
            return parts.joinToString(" ")
        }

        if (!armed) {
            parts += ctx.getString(R.string.sentence_off)
            parts += ctx.getString(R.string.sentence_off_hint)
            return parts.joinToString(" ")
        }

        val name = status.targetName ?: destination(ctx)

        // Sentence 1: is it on, and how far away are you?
        val distance = status.distanceMeters
        parts += when {
            distance == null ->
                ctx.getString(R.string.sentence_on_no_fix, name)

            distance >= 1000f ->
                ctx.getString(R.string.sentence_on_far, prettyKm(distance), name)

            else ->
                ctx.getString(R.string.sentence_on_near, distance.roundToInt(), name)
        }

        // Sentence 2: what will happen when you get there.
        val radius = status.targetRadius
        val output = when (status.alarmOutput) {
            AlarmOutput.BLUETOOTH ->
                status.outputName?.let { ctx.getString(R.string.sentence_out_named, it) }
                    ?: ctx.getString(R.string.sentence_out_bluetooth)

            AlarmOutput.WIRED -> ctx.getString(R.string.sentence_out_wired)
            AlarmOutput.SPEAKER -> ctx.getString(R.string.sentence_out_speaker)
        }
        val vibrates = AlarmAudio.shouldVibrate(ctx, AudioRoute(status.alarmOutput, status.outputName))
        parts += if (vibrates) {
            ctx.getString(R.string.sentence_will_ring_and_buzz, output, radius)
        } else {
            ctx.getString(R.string.sentence_will_ring, output, radius)
        }

        if (status.alarmOutput != AlarmOutput.SPEAKER && Prefs.limitHeadsetVolume(ctx)) {
            parts += ctx.getString(R.string.sentence_volume_capped, Prefs.headsetVolumePercent(ctx))
        }

        // Sentence 3: the rest of the journey.
        status.nextLegName?.let { parts += ctx.getString(R.string.sentence_then, it) }

        // Sentence 4: anything the user should worry about.
        if (!status.serviceRunning) {
            parts += ctx.getString(R.string.sentence_service_restarting)
        }
        if (status.snoozedUntilMillis > System.currentTimeMillis()) {
            parts += ctx.getString(R.string.sentence_snoozed, clockTime(status.snoozedUntilMillis))
        }
        status.message?.let { parts += it }

        return parts.joinToString(" ")
    }

    private fun destination(ctx: Context) = ctx.getString(R.string.sentence_your_stop)

    private fun prettyKm(meters: Float): String {
        val km = meters / 1000f
        // "3.2 km" reads better than "3.20 km" when you are half asleep.
        return if (km >= 10f) "${km.roundToInt()}" else String.format(java.util.Locale.getDefault(), "%.1f", km)
    }
}
