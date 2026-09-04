package com.danemadsen.atlas.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A ranked result row for the results drawer.
 */
data class PlaceHit(
    val name: String,
    val kind: String,
    val subclass: String?,
    val rank: Int,
    val lon: Double,
    val lat: Double,
)

/**
 * The search read side over one built index DB: tokenize + prefix the raw
 * query into an FTS MATCH pattern, fetch a generous candidate window, then
 * score by place rank and distance from the map center — SQL cannot express
 * that ranking, and pulling [HARD_LIMIT] rows to score in Kotlin is a
 * millisecond-order operation at this table's scale.
 */
suspend fun searchPlaces(
    dao: PlaceDao,
    rawQuery: String,
    centerLon: Double,
    centerLat: Double,
    limit: Int = RESULT_LIMIT,
): List<PlaceHit> = withContext(Dispatchers.IO) {
    val pattern = ftsPattern(rawQuery) ?: return@withContext emptyList()
    dao.match(pattern, centerLon, centerLat, HARD_LIMIT)
        .sortedWith(
            compareBy({ it.rank }, { distanceMeters(it.lon, it.lat, centerLon, centerLat) }),
        )
        .take(limit)
        .map { PlaceHit(it.name, it.kind, it.subclass, it.rank, it.lon, it.lat) }
}

const val RESULT_LIMIT = 20
const val HARD_LIMIT = 500

/**
 * The FTS MATCH pattern for a user query, or null when it holds no usable
 * token. Tokens are ANDed (implicit FTS semantics), every token gets a
 * prefix star so "melb" finds "Melbourne", and quotes are stripped so user
 * input can never inject MATCH syntax — a raw "port*" or a stray OR must
 * not blow the query up. A user-typed star is not doubled ("port*" stays
 * "port*", not "port**").
 */
fun ftsPattern(rawQuery: String): String? {
    val tokens = rawQuery.split(Regex("\\s+"))
        .map { token ->
            token.replace("\"", "").trim().trimEnd('*').trim()
        }
        .filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return null
    return tokens.joinToString(" ") { "$it*" }
}

/** Haversine distance in meters — the ranking tiebreaker. */
fun distanceMeters(lon1: Double, lat1: Double, lon2: Double, lat2: Double): Double {
    val dlon = Math.toRadians(lon2 - lon1)
    val dlat = Math.toRadians(lat2 - lat1)
    val a = Math.sin(dlat / 2) * Math.sin(dlat / 2) +
        Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
        Math.sin(dlon / 2) * Math.sin(dlon / 2)
    return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(a))
}

private const val EARTH_RADIUS_M = 6_371_000.0