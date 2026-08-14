package dev.emiara.gpswakeup

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Last-resort alarm sound, synthesised on the fly.
 *
 * Used when the device has no usable default alarm ringtone, or when MediaPlayer refuses to
 * play it. Generating the tone rather than shipping an audio file means there is no asset
 * that can be missing, unreadable or silently transcoded — if the speaker works, this works.
 */
class FallbackTone {

    private companion object {
        const val TAG = "FallbackTone"
        const val SAMPLE_RATE = 16_000
    }

    private var track: AudioTrack? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    fun start(): Boolean {
        stop()

        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioTrack reports no usable buffer size")
            return false
        }

        val created = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(max(minBuffer, SAMPLE_RATE))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrElse {
            Log.e(TAG, "Could not create AudioTrack: $it")
            return false
        }

        track = created
        running = true
        runCatching { created.play() }.getOrElse {
            Log.e(TAG, "Could not start AudioTrack: $it")
            stop()
            return false
        }

        val pattern = buildPattern()
        thread = Thread {
            while (running) {
                var offset = 0
                while (running && offset < pattern.size) {
                    val written = runCatching {
                        created.write(pattern, offset, pattern.size - offset)
                    }.getOrDefault(-1)
                    if (written <= 0) return@Thread
                    offset += written
                }
            }
        }.also {
            it.isDaemon = true
            it.name = "gpswakeup-tone"
            it.start()
        }
        return true
    }

    fun stop() {
        running = false
        thread?.runCatching { join(500) }
        thread = null
        track?.runCatching {
            if (state == AudioTrack.STATE_INITIALIZED) {
                if (playState != AudioTrack.PLAYSTATE_STOPPED) this.stop()
                flush()
            }
            release()
        }
        track = null
    }

    /** A repeating urgent two-tone pattern that starts and ends on silence, so it loops cleanly. */
    private fun buildPattern(): ShortArray {
        val totalSamples = SAMPLE_RATE * 3
        val samples = ShortArray(totalSamples)

        fun beep(startSeconds: Double, lengthSeconds: Double, frequency: Double) {
            val start = (startSeconds * SAMPLE_RATE).toInt()
            val length = (lengthSeconds * SAMPLE_RATE).toInt()
            val fade = (0.008 * SAMPLE_RATE).toInt().coerceAtLeast(1)
            for (i in 0 until length) {
                val index = start + i
                if (index >= totalSamples) break
                val envelope = when {
                    i < fade -> i.toDouble() / fade
                    i > length - fade -> max(0.0, (length - i).toDouble() / fade)
                    else -> 1.0
                }
                // A little second harmonic cuts through bus noise better than a pure sine.
                val phase = 2.0 * PI * frequency * i / SAMPLE_RATE
                val value = 0.8 * sin(phase) + 0.2 * sin(2 * phase)
                val scaled = 0.85 * envelope * value
                samples[index] = (min(1.0, max(-1.0, scaled)) * Short.MAX_VALUE).toInt().toShort()
            }
        }

        var t = 0.05
        while (t < 2.65) {
            beep(t, 0.22, 880.0)
            t += 0.30
            if (t >= 2.65) break
            beep(t, 0.22, 1174.0)
            t += 0.45
        }
        return samples
    }
}
