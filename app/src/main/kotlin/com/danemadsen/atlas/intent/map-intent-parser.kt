package com.danemadsen.atlas.intent

import android.net.Uri

/**
 * What an external geo: URI asked the map to do. Two shapes cover the
 * scheme's real-world uses: a point to look at (optionally labeled), and
 * a free-text lookup Atlas resolves through its OFFLINE search index.
 */
sealed interface MapIntentRequest {
    /**
     * A concrete location: the URI path's coordinates, an optional `z=`
     * zoom and an optional label (from `q=lat,lon(Label)`).
     */
    data class Coordinate(
        val latitude: Double,
        val longitude: Double,
        val zoom: Double?,
        val label: String?,
    ) : MapIntentRequest

    /**
     * A place to look up offline. [latitude]/[longitude] carry the URI
     * path's coordinates when it supplied a real position — a ranking
     * anchor around which the offline search should lean.
     */
    data class Search(
        val query: String,
        val latitude: Double?,
        val longitude: Double?,
    ) : MapIntentRequest
}

/**
 * The geo: URI parser (RFC 5870 plus the de-facto Google query extension).
 * Pure string parsing so the unit tests run on the plain JVM — the
 * [android.net.Uri] overload is a thin adapter over [parse].
 *
 * Accepted shapes (the ones other mapping apps emit):
 *
 *   geo:-27.4698,153.0251                  → Coordinate
 *   geo:-27.4698,153.0251?z=16             → Coordinate with zoom
 *   geo:0,0?q=Brisbane                     → Search
 *   geo:-27.4698,153.0251?q=coffee         → Search anchored on the path
 *   geo:0,0?q=-27.4698,153.0251(Brisbane)  → Coordinate with a label
 *
 * Anything malformed — bad numbers, out-of-range coordinates, an empty
 * query on a null island, a non-geo URI — parses to null and the caller
 * simply ignores the intent. Never throws.
 */
object MapIntentParser {

    /** MapLibre's usable zoom ceiling; `z=` beyond it is nonsense, not a camera. */
    private const val MAX_ZOOM = 25.5

    /** `lat,lon` with an optional `(label)` tail — the `q=` coordinate form. */
    private val QUERY_COORDINATE_RE =
        Regex("""^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*(?:\((.*)\))?\s*$""")

    fun parse(uri: Uri?): MapIntentRequest? = uri?.toString()?.let { parse(it) }

    fun parse(raw: String?): MapIntentRequest? {
        if (raw == null) return null
        val colon = raw.indexOf(':')
        if (colon <= 0) return null
        if (!raw.substring(0, colon).equals("geo", ignoreCase = true)) return null
        val rest = raw.substring(colon + 1)

        // Path and query split on the FIRST '?' — a query value may hold
        // more of them once percent-decoded.
        val question = rest.indexOf('?')
        val path = if (question < 0) rest else rest.substring(0, question)
        val query = if (question < 0) "" else rest.substring(question + 1)

        // RFC 5870 also allows ";crs=..." style path parameters after the
        // coordinates; they never change which point is meant.
        val coordinates = path.substringBefore(';')
        val parts = coordinates.split(',')
        if (parts.size < 2) return null
        val latitude = parts[0].trim().toDoubleOrNull() ?: return null
        val longitude = parts[1].trim().toDoubleOrNull() ?: return null
        if (!isValid(latitude, longitude)) return null

        var zoom: Double? = null
        var search_query: String? = null
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val name = percentDecode(if (eq < 0) pair else pair.substring(0, eq)).trim()
            val value = if (eq < 0) "" else percentDecode(pair.substring(eq + 1))
            when (name.lowercase()) {
                "z" -> zoom = value.toDoubleOrNull()?.takeIf { it in 0.0..MAX_ZOOM }
                "q" -> if (search_query == null) search_query = value
            }
        }

        val q = search_query?.takeIf { it.isNotBlank() }
        if (q == null) {
            // A bare coordinate (possibly with z=) — but `geo:0,0` with no
            // query means "look at nothing in particular", which is not an
            // action: only a real position is worth flying to.
            if (latitude == 0.0 && longitude == 0.0) return null
            return MapIntentRequest.Coordinate(latitude, longitude, zoom, null)
        }

        // The de-facto coordinate-in-query form: `q=lat,lon(Label)` is a
        // POINT, not something to run through the place index.
        val coordinate_match = QUERY_COORDINATE_RE.matchEntire(q)
        if (coordinate_match != null) {
            val q_lat = coordinate_match.groupValues[1].toDoubleOrNull()
            val q_lon = coordinate_match.groupValues[2].toDoubleOrNull()
            if (q_lat != null && q_lon != null && isValid(q_lat, q_lon)) {
                val label = coordinate_match.groupValues[3].trim().takeIf { it.isNotEmpty() }
                return MapIntentRequest.Coordinate(q_lat, q_lon, zoom, label)
            }
            // A q= that LOOKS like coordinates but is out of range falls
            // through to the offline search — the text is still a lookup.
        }

        val anchored = if (latitude == 0.0 && longitude == 0.0) null else latitude to longitude
        return MapIntentRequest.Search(
            query = q,
            latitude = anchored?.first,
            longitude = anchored?.second,
        )
    }

    private fun isValid(latitude: Double, longitude: Double): Boolean =
        !latitude.isNaN() && !longitude.isNaN() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0

    /**
     * Percent-decoding without [java.net.URLDecoder]: that one also turns
     * '+' into a space, which is form-encoding, not URI encoding — a query
     * like `q=coffee+beans` must search for "coffee+beans", and a literal
     * '+' survives here untouched. Multi-byte UTF-8 sequences (%XX %XX…)
     * are reassembled as bytes before the charset decode, not char by char.
     * A dangling or non-hex `%` is left as a literal.
     */
    private fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val bytes = ByteArray(value.length)
        var byte_count = 0
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val byte = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (byte != null) {
                    bytes[byte_count++] = byte.toByte()
                    i += 3
                    continue
                }
            }
            // Non-ASCII input characters (an already-decoded literal) ride
            // through as their UTF-8 bytes; ASCII maps to itself.
            for (b in c.toString().toByteArray(Charsets.UTF_8)) bytes[byte_count++] = b
            i += 1
        }
        return String(bytes, 0, byte_count, Charsets.UTF_8)
    }
}