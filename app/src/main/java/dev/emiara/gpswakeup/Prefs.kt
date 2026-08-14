package dev.emiara.gpswakeup

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A saved destination — normally the bus stop you get off at. */
data class Stop(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val lat: Double,
    val lon: Double,
    val radiusMeters: Int = Prefs.DEFAULT_RADIUS,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("lat", lat)
        put("lon", lon)
        put("radius", radiusMeters)
    }

    companion object {
        fun fromJson(o: JSONObject) = Stop(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name", "Stop"),
            lat = o.optDouble("lat", 0.0),
            lon = o.optDouble("lon", 0.0),
            radiusMeters = o.optInt("radius", Prefs.DEFAULT_RADIUS),
        )
    }
}

/**
 * Everything the app remembers, in one SharedPreferences file.
 *
 * The armed state lives here rather than in memory on purpose: the boot receiver and the
 * watchdog alarm both need to know whether tracking should be running after the process
 * has been killed.
 */
object Prefs {

    const val DEFAULT_RADIUS = 500
    const val MIN_RADIUS = 100
    const val MAX_RADIUS = 3000

    private const val FILE = "gps_wakeup"

    private const val K_STOPS = "stops"
    private const val K_ARMED_STOP = "armed_stop_id"
    private const val K_ARMED_AT = "armed_at"
    private const val K_BACKSTOP_AT = "backstop_at"
    private const val K_ALARMING = "alarming"
    private const val K_MAX_VOLUME = "force_max_volume"
    private const val K_VIBRATE = "vibrate"
    private const val K_MISSED_STOP = "missed_stop_guard"
    private const val K_BACKSTOP_MINUTES = "backstop_minutes"
    private const val K_SNOOZE_MINUTES = "snooze_minutes"
    private const val K_PREVIOUS_VOLUME = "previous_alarm_volume"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- saved stops -------------------------------------------------------

    fun stops(ctx: Context): List<Stop> {
        val raw = sp(ctx).getString(K_STOPS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Stop.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun saveStops(ctx: Context, stops: List<Stop>) {
        val arr = JSONArray()
        stops.forEach { arr.put(it.toJson()) }
        sp(ctx).edit().putString(K_STOPS, arr.toString()).apply()
    }

    fun upsertStop(ctx: Context, stop: Stop) {
        val current = stops(ctx).toMutableList()
        val idx = current.indexOfFirst { it.id == stop.id }
        if (idx >= 0) current[idx] = stop else current.add(stop)
        saveStops(ctx, current)
    }

    fun deleteStop(ctx: Context, id: String) {
        saveStops(ctx, stops(ctx).filterNot { it.id == id })
        if (armedStopId(ctx) == id) disarm(ctx)
    }

    fun stopById(ctx: Context, id: String?): Stop? =
        if (id == null) null else stops(ctx).firstOrNull { it.id == id }

    // ---- armed state -------------------------------------------------------

    fun armedStopId(ctx: Context): String? = sp(ctx).getString(K_ARMED_STOP, null)

    fun isArmed(ctx: Context): Boolean = armedStopId(ctx) != null

    fun arm(ctx: Context, stopId: String, backstopAtMillis: Long) {
        sp(ctx).edit()
            .putString(K_ARMED_STOP, stopId)
            .putLong(K_ARMED_AT, System.currentTimeMillis())
            .putLong(K_BACKSTOP_AT, backstopAtMillis)
            .putBoolean(K_ALARMING, false)
            .apply()
    }

    fun disarm(ctx: Context) {
        sp(ctx).edit()
            .remove(K_ARMED_STOP)
            .remove(K_ARMED_AT)
            .remove(K_BACKSTOP_AT)
            .putBoolean(K_ALARMING, false)
            .apply()
    }

    fun backstopAt(ctx: Context): Long = sp(ctx).getLong(K_BACKSTOP_AT, 0L)

    fun setBackstopAt(ctx: Context, at: Long) {
        sp(ctx).edit().putLong(K_BACKSTOP_AT, at).apply()
    }

    fun isAlarming(ctx: Context): Boolean = sp(ctx).getBoolean(K_ALARMING, false)

    fun setAlarming(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(K_ALARMING, value).apply()
    }

    // ---- settings ----------------------------------------------------------

    fun forceMaxVolume(ctx: Context): Boolean = sp(ctx).getBoolean(K_MAX_VOLUME, true)

    fun setForceMaxVolume(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(K_MAX_VOLUME, value).apply()
    }

    fun vibrate(ctx: Context): Boolean = sp(ctx).getBoolean(K_VIBRATE, true)

    fun setVibrate(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(K_VIBRATE, value).apply()
    }

    fun missedStopGuard(ctx: Context): Boolean = sp(ctx).getBoolean(K_MISSED_STOP, true)

    fun setMissedStopGuard(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(K_MISSED_STOP, value).apply()
    }

    /** Wake me no later than N minutes after arming, even with no GPS at all. 0 = off. */
    fun backstopMinutes(ctx: Context): Int = sp(ctx).getInt(K_BACKSTOP_MINUTES, 0)

    fun setBackstopMinutes(ctx: Context, value: Int) {
        sp(ctx).edit().putInt(K_BACKSTOP_MINUTES, value.coerceIn(0, 240)).apply()
    }

    fun snoozeMinutes(ctx: Context): Int = sp(ctx).getInt(K_SNOOZE_MINUTES, 2)

    fun setSnoozeMinutes(ctx: Context, value: Int) {
        sp(ctx).edit().putInt(K_SNOOZE_MINUTES, value.coerceIn(1, 15)).apply()
    }

    /** Manual, unverifiable setup steps (e.g. OEM autostart) the user has ticked off. */
    fun manualStepDone(ctx: Context, key: String): Boolean =
        sp(ctx).getBoolean("manual_$key", false)

    fun setManualStepDone(ctx: Context, key: String, done: Boolean) {
        sp(ctx).edit().putBoolean("manual_$key", done).apply()
    }

    fun previousAlarmVolume(ctx: Context): Int = sp(ctx).getInt(K_PREVIOUS_VOLUME, -1)

    fun setPreviousAlarmVolume(ctx: Context, value: Int) {
        sp(ctx).edit().putInt(K_PREVIOUS_VOLUME, value).apply()
    }
}
