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

/** One hop of a journey: get off here. */
data class RouteLeg(
    val stopId: String,
    val note: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("stopId", stopId)
        put("note", note)
    }

    companion object {
        fun fromJson(o: JSONObject) = RouteLeg(
            stopId = o.optString("stopId"),
            note = o.optString("note"),
        )
    }
}

/**
 * A saved journey — the transfer stop, then the one you actually get off at. Arming a route
 * arms its first leg; dismissing that alarm arms the next one automatically, so you never
 * have to re-arm the final destination half asleep at a bus interchange.
 */
data class Route(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val legs: List<RouteLeg>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("legs", JSONArray().also { array -> legs.forEach { array.put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): Route {
            val legsArray = o.optJSONArray("legs")
            val legs = if (legsArray == null) {
                emptyList()
            } else {
                (0 until legsArray.length()).mapNotNull { index ->
                    legsArray.optJSONObject(index)
                        ?.let { RouteLeg.fromJson(it) }
                        ?.takeIf { it.stopId.isNotBlank() }
                }
            }
            return Route(
                id = o.optString("id", UUID.randomUUID().toString()),
                name = o.optString("name", "Route"),
                legs = legs,
            )
        }
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
    private const val K_ROUTES = "routes"
    private const val K_ARMED_ROUTE = "armed_route_id"
    private const val K_ARMED_LEG = "armed_leg_index"
    private const val K_ARMED_REVERSED = "armed_reversed"
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
    private const val K_LIMIT_HEADSET = "limit_headset_volume"
    private const val K_HEADSET_PERCENT = "headset_volume_percent"
    private const val K_DYSLEXIA_FONT = "dyslexia_font"

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
        // A route pointing at a stop that no longer exists would silently skip a leg.
        saveRoutes(
            ctx,
            routes(ctx).map { route -> route.copy(legs = route.legs.filterNot { it.stopId == id }) },
        )
        if (armedStopId(ctx) == id) disarm(ctx)
    }

    fun stopById(ctx: Context, id: String?): Stop? =
        if (id == null) null else stops(ctx).firstOrNull { it.id == id }

    // ---- armed state -------------------------------------------------------

    fun armedStopId(ctx: Context): String? = sp(ctx).getString(K_ARMED_STOP, null)

    fun isArmed(ctx: Context): Boolean = armedStopId(ctx) != null

    fun arm(
        ctx: Context,
        stopId: String,
        backstopAtMillis: Long,
        routeId: String? = null,
        legIndex: Int = 0,
        reversed: Boolean = false,
    ) {
        sp(ctx).edit()
            .putString(K_ARMED_STOP, stopId)
            .putString(K_ARMED_ROUTE, routeId)
            .putInt(K_ARMED_LEG, legIndex)
            .putBoolean(K_ARMED_REVERSED, reversed)
            .putLong(K_ARMED_AT, System.currentTimeMillis())
            .putLong(K_BACKSTOP_AT, backstopAtMillis)
            .putBoolean(K_ALARMING, false)
            .apply()
    }

    fun disarm(ctx: Context) {
        sp(ctx).edit()
            .remove(K_ARMED_STOP)
            .remove(K_ARMED_ROUTE)
            .remove(K_ARMED_LEG)
            .remove(K_ARMED_REVERSED)
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

    // ---- saved routes ------------------------------------------------------

    fun routes(ctx: Context): List<Route> {
        val raw = sp(ctx).getString(K_ROUTES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(Route::fromJson) }
        }.getOrDefault(emptyList())
    }

    fun saveRoutes(ctx: Context, routes: List<Route>) {
        val arr = JSONArray()
        routes.forEach { arr.put(it.toJson()) }
        sp(ctx).edit().putString(K_ROUTES, arr.toString()).apply()
    }

    fun upsertRoute(ctx: Context, route: Route) {
        val current = routes(ctx).toMutableList()
        val idx = current.indexOfFirst { it.id == route.id }
        if (idx >= 0) current[idx] = route else current.add(route)
        saveRoutes(ctx, current)
    }

    fun deleteRoute(ctx: Context, id: String) {
        saveRoutes(ctx, routes(ctx).filterNot { it.id == id })
        if (armedRouteId(ctx) == id) disarm(ctx)
    }

    fun routeById(ctx: Context, id: String?): Route? =
        if (id == null) null else routes(ctx).firstOrNull { it.id == id }

    fun armedRouteId(ctx: Context): String? = sp(ctx).getString(K_ARMED_ROUTE, null)

    fun armedRoute(ctx: Context): Route? = routeById(ctx, armedRouteId(ctx))

    fun armedLegIndex(ctx: Context): Int = sp(ctx).getInt(K_ARMED_LEG, 0)

    /** True when the armed route is being ridden backwards. */
    fun armedReversed(ctx: Context): Boolean = sp(ctx).getBoolean(K_ARMED_REVERSED, false)

    /**
     * The legs of a route in travel order. Reversing a route is a property of this journey,
     * not an edit to the saved route — the same route works there and back.
     */
    fun legsInOrder(route: Route, reversed: Boolean): List<RouteLeg> =
        if (reversed) route.legs.reversed() else route.legs

    private fun armedLegs(ctx: Context): List<RouteLeg> {
        val route = armedRoute(ctx) ?: return emptyList()
        return legsInOrder(route, armedReversed(ctx))
    }

    /** The leg after the current one, skipping any whose stop has since been deleted. */
    fun nextLeg(ctx: Context): Stop? {
        val legs = armedLegs(ctx)
        for (index in (armedLegIndex(ctx) + 1) until legs.size) {
            stopById(ctx, legs[index].stopId)?.let { return it }
        }
        return null
    }

    /**
     * Move to the next leg of the armed route. Returns false when there is no next leg,
     * which means the journey is over and the caller should disarm.
     */
    fun advanceToNextLeg(ctx: Context): Boolean {
        val legs = armedLegs(ctx)
        for (index in (armedLegIndex(ctx) + 1) until legs.size) {
            val stop = stopById(ctx, legs[index].stopId) ?: continue
            sp(ctx).edit()
                .putString(K_ARMED_STOP, stop.id)
                .putInt(K_ARMED_LEG, index)
                .putBoolean(K_ALARMING, false)
                .apply()
            return true
        }
        return false
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

    /** Cap the alarm volume when it is going into your ears rather than a speaker. */
    fun limitHeadsetVolume(ctx: Context): Boolean = sp(ctx).getBoolean(K_LIMIT_HEADSET, true)

    fun setLimitHeadsetVolume(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(K_LIMIT_HEADSET, value).apply()
    }

    fun headsetVolumePercent(ctx: Context): Int = sp(ctx).getInt(K_HEADSET_PERCENT, 60)

    fun setHeadsetVolumePercent(ctx: Context, value: Int) {
        sp(ctx).edit().putInt(K_HEADSET_PERCENT, value.coerceIn(10, 100)).apply()
    }

    fun dyslexiaFont(ctx: Context): Boolean = sp(ctx).getBoolean(K_DYSLEXIA_FONT, true)

    fun setDyslexiaFont(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(K_DYSLEXIA_FONT, value).apply()
    }

    fun previousAlarmVolume(ctx: Context): Int = sp(ctx).getInt(K_PREVIOUS_VOLUME, -1)

    fun setPreviousAlarmVolume(ctx: Context, value: Int) {
        sp(ctx).edit().putInt(K_PREVIOUS_VOLUME, value).apply()
    }
}
