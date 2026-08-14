package dev.emiara.gpswakeup

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * What we managed to pull out of a shared map link. Either a coordinate, a place name, or
 * both — a place name alone is still useful because it can be looked up in [Entur].
 */
data class MapsTarget(
    val lat: Double? = null,
    val lon: Double? = null,
    val placeName: String? = null,
    val expandedUrl: String? = null,
) {
    val hasCoordinates: Boolean get() = lat != null && lon != null
    val isEmpty: Boolean get() = !hasCoordinates && placeName.isNullOrBlank()
}

/**
 * Turns a pasted or shared Google Maps link into somewhere we can set an alarm for.
 *
 * Short links (`maps.app.goo.gl/…`) are resolved by following the redirect, then the
 * expanded URL is mined for the destination. Google's URL format is undocumented and
 * changes, so every pattern here is best-effort and the result is always shown to you for
 * confirmation rather than saved silently.
 */
object MapsLink {

    private const val TAG = "MapsLink"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_REDIRECTS = 6
    private const val MAX_BODY_CHARS = 200_000

    // A browser UA — Google serves bare redirects to browsers and interstitials to others.
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    private val URL_PATTERN = Regex("""https?://\S+""")
    private val SHORTENER_HOSTS = setOf(
        "maps.app.goo.gl",
        "goo.gl",
        "g.co",
        "maps.google.com",
        "www.google.com",
        "google.com",
    )

    // Google encodes a place's real coordinates as !3d<lat>!4d<lon> inside the data blob.
    private val DATA_COORDS = Regex("""!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)""")

    // The @lat,lon,zoom in the path is the map viewport, not the destination — fallback only.
    private val VIEWPORT_COORDS = Regex("""[/@](-?\d+\.\d+),(-?\d+\.\d+)(?:,[\d.]+z)?""")

    private val PARAM_COORDS = Regex(
        """[?&](?:destination|daddr|q|ll|center|sll)=(-?\d+\.\d+)(?:,|%2C)(-?\d+\.\d+)""",
        RegexOption.IGNORE_CASE,
    )
    private val PARAM_NAME = Regex(
        """[?&](?:destination|daddr|q)=([^&]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val PLACE_PATH = Regex("""/maps/place/([^/@?]+)""")
    private val DIR_PATH = Regex("""/maps/dir/([^@?]*)""")
    private val GEO_URI = Regex("""geo:(-?\d+\.\d+),(-?\d+\.\d+)""")
    private val GEO_QUERY = Regex("""geo:[^?]*\?q=(-?\d+\.\d+),(-?\d+\.\d+)""")

    fun looksLikeLink(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.startsWith("geo:", ignoreCase = true) || URL_PATTERN.containsMatchIn(trimmed)
    }

    /** Share sheets hand over "Check this out <url>" — pull the URL back out. */
    fun extractUrl(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.startsWith("geo:", ignoreCase = true)) return trimmed.substringBefore(' ')
        return URL_PATTERN.find(trimmed)?.value?.trimEnd('.', ',', ')', '"', '\'')
    }

    /**
     * Resolve shared text into a target. Network is only touched for links that need
     * expanding; a full URL with coordinates already in it is parsed offline.
     */
    suspend fun resolve(sharedText: String): Result<MapsTarget> = withContext(Dispatchers.IO) {
        runCatching {
            val raw = extractUrl(sharedText)
                ?: return@runCatching parse(sharedText, sharedText)

            if (raw.startsWith("geo:", ignoreCase = true)) {
                return@runCatching parse(raw, raw)
            }

            // Try it as-is first; many pasted links already carry the coordinates.
            val direct = parse(raw, raw)
            if (direct.hasCoordinates) return@runCatching direct

            val expanded = expand(raw)
            val fromExpanded = parse(expanded.url, expanded.url)
            if (!fromExpanded.isEmpty) return@runCatching fromExpanded

            // Interstitial or consent page — the real link is usually inside the HTML.
            val fromBody = expanded.body
                ?.let { body -> DATA_COORDS.findAll(body).lastOrNull() }
                ?.let { match ->
                    MapsTarget(
                        lat = match.groupValues[1].toDoubleOrNull(),
                        lon = match.groupValues[2].toDoubleOrNull(),
                        expandedUrl = expanded.url,
                    )
                }

            fromBody ?: direct.copy(expandedUrl = expanded.url)
        }.onFailure { Log.w(TAG, "Could not resolve '$sharedText': $it") }
    }

    private class Expanded(val url: String, val body: String?)

    /** Follow redirects manually so we can see the final URL even when the body is junk. */
    private fun expand(startUrl: String): Expanded {
        var current = startUrl
        var body: String? = null

        for (hop in 0 until MAX_REDIRECTS) {
            val host = runCatching { URL(current).host }.getOrNull().orEmpty()
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept-Language", "en,no;q=0.9")
            }
            try {
                val code = connection.responseCode
                val location = connection.getHeaderField("Location")
                if (code in 300..399 && !location.isNullOrBlank()) {
                    current = if (location.startsWith("http")) {
                        location
                    } else {
                        URL(URL(current), location).toString()
                    }
                    continue
                }

                // Not a redirect. Read the body only if we still have no coordinates and the
                // host is one we expect to be a Google page.
                if (host in SHORTENER_HOSTS || host.endsWith(".google.com")) {
                    body = connection.inputStream.bufferedReader().use { reader ->
                        val text = StringBuilder()
                        val buffer = CharArray(8192)
                        while (text.length < MAX_BODY_CHARS) {
                            val read = reader.read(buffer)
                            if (read < 0) break
                            text.appendRange(buffer, 0, read)
                        }
                        text.toString()
                    }
                }
                return Expanded(current, body)
            } finally {
                connection.disconnect()
            }
        }
        return Expanded(current, body)
    }

    /** Pull whatever we can out of a URL. Never throws. */
    fun parse(url: String, expandedUrl: String?): MapsTarget {
        GEO_QUERY.find(url)?.let { match ->
            return MapsTarget(
                lat = match.groupValues[1].toDoubleOrNull(),
                lon = match.groupValues[2].toDoubleOrNull(),
                expandedUrl = expandedUrl,
            )
        }
        GEO_URI.find(url)?.let { match ->
            val lat = match.groupValues[1].toDoubleOrNull()
            val lon = match.groupValues[2].toDoubleOrNull()
            // geo:0,0?q=Name is the "search for this" form.
            if (lat != 0.0 || lon != 0.0) {
                return MapsTarget(lat = lat, lon = lon, expandedUrl = expandedUrl)
            }
        }

        val name = placeNameFrom(url)

        // The last !3d/!4d pair is the destination of a directions link.
        DATA_COORDS.findAll(url).lastOrNull()?.let { match ->
            return MapsTarget(
                lat = match.groupValues[1].toDoubleOrNull(),
                lon = match.groupValues[2].toDoubleOrNull(),
                placeName = name,
                expandedUrl = expandedUrl,
            )
        }

        PARAM_COORDS.find(url)?.let { match ->
            return MapsTarget(
                lat = match.groupValues[1].toDoubleOrNull(),
                lon = match.groupValues[2].toDoubleOrNull(),
                placeName = name,
                expandedUrl = expandedUrl,
            )
        }

        VIEWPORT_COORDS.find(url)?.let { match ->
            return MapsTarget(
                lat = match.groupValues[1].toDoubleOrNull(),
                lon = match.groupValues[2].toDoubleOrNull(),
                placeName = name,
                expandedUrl = expandedUrl,
            )
        }

        return MapsTarget(placeName = name, expandedUrl = expandedUrl)
    }

    /** The human-readable destination, which can be handed to Entur if we have no coordinates. */
    private fun placeNameFrom(url: String): String? {
        PLACE_PATH.find(url)?.groupValues?.get(1)?.let { return prettify(it) }

        DIR_PATH.find(url)?.groupValues?.get(1)?.let { segment ->
            // /maps/dir/<origin>/<destination>/ — the destination is what we want.
            val parts = segment.split('/')
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("data=") && !it.startsWith("@") }
            parts.lastOrNull()?.let { candidate ->
                val pretty = prettify(candidate)
                if (!pretty.isNullOrBlank() && !LOOKS_LIKE_COORDS.matches(pretty)) return pretty
            }
        }

        PARAM_NAME.find(url)?.groupValues?.get(1)?.let { candidate ->
            val pretty = prettify(candidate)
            if (!pretty.isNullOrBlank() && !LOOKS_LIKE_COORDS.matches(pretty)) return pretty
        }
        return null
    }

    private val LOOKS_LIKE_COORDS = Regex("""^\s*-?\d+(\.\d+)?\s*,\s*-?\d+(\.\d+)?\s*$""")

    private fun prettify(raw: String): String? = runCatching {
        URLDecoder.decode(raw.replace('+', ' '), "UTF-8").trim().takeIf { it.isNotBlank() }
    }.getOrNull()
}
