package dev.emiara.gpswakeup

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A place you can turn into a stop: a transport stop, a station, or an address. */
data class StopSuggestion(
    val id: String,
    val name: String,
    val label: String,
    val locality: String?,
    val county: String?,
    val categories: List<String>,
    val layer: String?,
    val lat: Double,
    val lon: Double,
) {
    /** Short human word for what this is — "Bybanen", "Train", "Bus"… */
    val kind: String
        get() {
            for (category in categories) {
                CATEGORY_LABELS[category.lowercase()]?.let { return it }
            }
            return when (layer) {
                "address" -> "Address"
                "street" -> "Street"
                "locality", "borough", "localadmin" -> "Area"
                "county" -> "County"
                else -> "Stop"
            }
        }

    /** Where it is, for disambiguating the twelve "Sentrum" stops. */
    val where: String
        get() = listOfNotNull(locality, county)
            .distinct()
            .filter { it.isNotBlank() }
            .joinToString(", ")

    private companion object {
        val CATEGORY_LABELS = mapOf(
            "onstreetbus" to "Bus",
            "busstation" to "Bus terminal",
            "coachstation" to "Coach",
            "onstreettram" to "Tram / Bybanen",
            "tramstation" to "Tram / Bybanen",
            "metrostation" to "Metro",
            "railstation" to "Train",
            "vehiclerailinterchange" to "Train",
            "airport" to "Airport",
            "harbourport" to "Ferry",
            "ferryport" to "Ferry",
            "ferrystop" to "Ferry",
            "liftstation" to "Cable car",
            "groupofstopplaces" to "Stop area",
        )
    }
}

/**
 * Norwegian stop and address lookup via Entur's geocoder — the national public transport
 * data service. Covers every bus stop, Bybanen platform, train station, ferry quay and
 * address in Norway, needs no API key, and only wants a client name in the header.
 *
 * Docs: https://developer.entur.org/pages-geocoder-intro
 */
object Entur {

    private const val TAG = "Entur"
    private const val BASE = "https://api.entur.io/geocoder/v1"

    // Entur asks every caller to identify itself as <organisation>-<application>.
    private const val CLIENT_NAME = "emiara-busstopalarm"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /** Stops first, then addresses — this is a stop alarm, so stops are what you usually want. */
    private const val LAYERS = "venue,address,street"

    /**
     * Type-ahead search. [focusLat]/[focusLon] bias results towards where you are, which is
     * what makes searching "Sentrum" in Bergen return the Bergen one.
     */
    suspend fun autocomplete(
        text: String,
        focusLat: Double? = null,
        focusLon: Double? = null,
        size: Int = 12,
    ): Result<List<StopSuggestion>> {
        if (text.isBlank()) return Result.success(emptyList())
        val params = buildString {
            append("text=").append(encode(text))
            append("&size=").append(size)
            append("&lang=no")
            append("&layers=").append(encode(LAYERS))
            append("&boundary.country=NOR")
            if (focusLat != null && focusLon != null) {
                append("&focus.point.lat=").append(focusLat)
                append("&focus.point.lon=").append(focusLon)
            }
        }
        return fetch("$BASE/autocomplete?$params")
    }

    /** Full search, used when you submit rather than type — slightly better at long queries. */
    suspend fun search(
        text: String,
        focusLat: Double? = null,
        focusLon: Double? = null,
        size: Int = 12,
    ): Result<List<StopSuggestion>> {
        if (text.isBlank()) return Result.success(emptyList())
        val params = buildString {
            append("text=").append(encode(text))
            append("&size=").append(size)
            append("&lang=no")
            append("&layers=").append(encode(LAYERS))
            append("&boundary.country=NOR")
            if (focusLat != null && focusLon != null) {
                append("&focus.point.lat=").append(focusLat)
                append("&focus.point.lon=").append(focusLon)
            }
        }
        return fetch("$BASE/search?$params")
    }

    /** Stops near a coordinate — "what is this stop actually called?". */
    suspend fun reverse(lat: Double, lon: Double, size: Int = 10): Result<List<StopSuggestion>> {
        val params = buildString {
            append("point.lat=").append(lat)
            append("&point.lon=").append(lon)
            append("&size=").append(size)
            append("&lang=no")
            append("&layers=venue")
            append("&boundary.circle.radius=1")
        }
        return fetch("$BASE/reverse?$params")
    }

    private suspend fun fetch(url: String): Result<List<StopSuggestion>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("ET-Client-Name", CLIENT_NAME)
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        val detail = connection.errorStream?.bufferedReader()
                            ?.use { it.readText().take(300) }
                            .orEmpty()
                        error("Entur returned HTTP $code $detail".trim())
                    }
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    parse(body)
                } finally {
                    connection.disconnect()
                }
            }.onFailure { Log.w(TAG, "Lookup failed for $url: $it") }
        }

    /** Pelias GeoJSON. Everything is read defensively — a missing field must not lose a result. */
    private fun parse(body: String): List<StopSuggestion> {
        val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
        val results = mutableListOf<StopSuggestion>()

        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val properties = feature.optJSONObject("properties") ?: continue

            // GeoJSON is [longitude, latitude] — the order trips everyone up once.
            val coordinates = feature.optJSONObject("geometry")?.optJSONArray("coordinates")
            if (coordinates == null || coordinates.length() < 2) continue
            val lon = coordinates.optDouble(0, Double.NaN)
            val lat = coordinates.optDouble(1, Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue

            val name = properties.optString("name").takeIf { it.isNotBlank() }
                ?: properties.optString("label").takeIf { it.isNotBlank() }
                ?: continue

            val categories = properties.optJSONArray("category")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
            }.orEmpty()

            results += StopSuggestion(
                id = properties.optString("id").takeIf { it.isNotBlank() } ?: "$lat,$lon",
                name = name,
                label = properties.optString("label").takeIf { it.isNotBlank() } ?: name,
                locality = properties.optString("locality").takeIf { it.isNotBlank() },
                county = properties.optString("county").takeIf { it.isNotBlank() },
                categories = categories,
                layer = properties.optString("layer").takeIf { it.isNotBlank() },
                lat = lat,
                lon = lon,
            )
        }

        // Transport stops before addresses; the API's own ordering is kept within each group.
        return results.sortedBy { if (it.layer == "venue") 0 else 1 }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
