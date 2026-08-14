package dev.emiara.gpswakeup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService

/** One-shot "where am I right now", used when saving a stop you're standing at. */
object Locate {

    private const val TIMEOUT_MS = 20_000L

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun once(ctx: Context, onResult: (Location?) -> Unit) {
        if (!hasPermission(ctx)) {
            onResult(null)
            return
        }
        val lm = ctx.getSystemService<LocationManager>()
        if (lm == null) {
            onResult(null)
            return
        }

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { it in runCatching { lm.allProviders }.getOrDefault(emptyList()) }
        if (providers.isEmpty()) {
            onResult(null)
            return
        }

        val handler = Handler(Looper.getMainLooper())
        var best: Location? = null
        var finished = false

        fun finish() {
            if (finished) return
            finished = true
            onResult(best ?: bestLastKnown(lm, providers))
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (best == null || (location.hasAccuracy() && location.accuracy < (best?.accuracy ?: Float.MAX_VALUE))) {
                    best = location
                }
                // Good enough for a bus stop — stop burning the GPS.
                if (location.hasAccuracy() && location.accuracy <= 25f) {
                    runCatching { lm.removeUpdates(this) }
                    handler.removeCallbacksAndMessages(null)
                    finish()
                }
            }

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit

            @Deprecated("Required by LocationListener on older API levels")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
        }

        for (provider in providers) {
            try {
                lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        }

        handler.postDelayed({
            runCatching { lm.removeUpdates(listener) }
            finish()
        }, TIMEOUT_MS)
    }

    private fun bestLastKnown(lm: LocationManager, providers: List<String>): Location? {
        var best: Location? = null
        for (p in providers) {
            val candidate = try {
                lm.getLastKnownLocation(p)
            } catch (_: SecurityException) {
                null
            } ?: continue
            val current = best
            if (current == null || candidate.time > current.time) best = candidate
        }
        // Anything older than half an hour is not where you are.
        val cutoff = System.currentTimeMillis() - 30 * 60_000L
        return best?.takeIf { it.time <= 0 || it.time >= cutoff }
    }
}
