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
 * query into an FTS MATCH pattern, fetch a generous candidate window from the
 * place table (always — places are thousands of rows), and additionally from
 * the address table when the query looks like an address ("69 mott street",
 * never a bare city name) — then score everything by place rank and distance
 * from the map center. SQL cannot express that ranking, and pulling
 * [HARD_LIMIT] rows to score in Kotlin is a millisecond-order operation at
 * the place table's scale.
 *
 * The two tables are queried separately on purpose: addresses are millions of
 * rows per country, and a shared table would make every keystroke of a place
 * search enumerate address doclists. The address query only ever runs once a
 * token pattern looks like house-number-plus-street typing.
 */
suspend fun searchPlaces(
    db: PlaceDatabase,
    rawQuery: String,
    centerLon: Double,
    centerLat: Double,
    limit: Int = RESULT_LIMIT,
): List<PlaceHit> = withContext(Dispatchers.IO) {
    val pattern = ftsPattern(rawQuery) ?: return@withContext emptyList()
    val candidates = db.placeDao().match(pattern, centerLon, centerLat, HARD_LIMIT).map {
        PlaceHit(it.name, it.kind, it.subclass, it.rank, it.lon, it.lat)
    } + if (isAddressQuery(rawQuery)) {
        db.addressDao().match(pattern, centerLon, centerLat, ADDRESS_HARD_LIMIT).map {
            PlaceHit(it.name, SearchIndexer.KIND_ADDRESS, it.city, SearchIndexer.ADDRESS_RANK, it.lon, it.lat)
        }
    } else {
        emptyList()
    }
    candidates
        .sortedWith(
            compareBy({ it.rank }, { distanceMeters(it.lon, it.lat, centerLon, centerLat) }),
        )
        .take(limit)
}

const val RESULT_LIMIT = 20
const val HARD_LIMIT = 500
const val ADDRESS_HARD_LIMIT = 500

/**
 * True when the query should also hit the address table: at least two tokens,
 * one digit-leading (the house number) and one that isn't (the street). The
 * digit-plus-word shape keeps bare numbers ("69" — a huge FTS prefix over
 * millions of rows) and pure street/city names on the cheap place-only path,
 * while "69 mott" and "12/45 harbour rd" go to the addresses.
 */
fun isAddressQuery(rawQuery: String): Boolean {
    val tokens = queryTokens(rawQuery)
    if (tokens.size < 2) return false
    val has_number = tokens.any { it.first().isDigit() }
    val has_word = tokens.any { !it.first().isDigit() }
    return has_number && has_word
}

/**
 * The FTS MATCH pattern for a user query, or null when it holds no usable
 * token. Tokens are ANDed (implicit FTS semantics) and each is wrapped as a
 * quoted prefix — `"tok"*` — so no token text can ever act as MATCH syntax:
 * a leading dash ("north-west" typed with a hyphen-minus) would otherwise be
 * parsed as a NOT operator or crash the parser, and an unquoted OR/NEAR
 * would rewrite the semantics. Quotes are stripped from the input first, so
 * user quotes cannot break out of the wrapping. A user-typed star is not
 * doubled ("port*" stays `port*`, not `port**`).
 */
fun ftsPattern(rawQuery: String): String? {
    val tokens = queryTokens(rawQuery)
    if (tokens.isEmpty()) return null
    return tokens.joinToString(" ") { "\"$it\"*" }
}

/** The query's whitespace-split tokens, MATCH-syntax-stripped and trimmed. */
private fun queryTokens(rawQuery: String): List<String> = rawQuery.split(Regex("\\s+"))
    .map { token ->
        token.replace("\"", "").trim().trimEnd('*').trim()
    }
    .filter { it.isNotEmpty() }

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