package com.danemadsen.atlas.search

import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Display-name and dedupe-key composition for OpenAddresses rows.
 *
 * OpenAddresses ships government data that is overwhelmingly ALL-CAPS
 * (Australia's G-NAF: "69 MOTT ST"), so the display name is title-cased
 * through a small street-type dictionary. Search is unaffected either way —
 * FTS4 with the unicode61 tokenizer is case-insensitive — this is purely
 * what the results drawer renders.
 */
private val KEEP_UPPER = setOf(
    // Australian state abbreviations are far more common in address data
    // than any word they could be mistaken for ("Sa", "Wa", "Nt").
    "NSW", "VIC", "QLD", "SA", "WA", "TAS", "NT", "ACT",
)

/**
 * Street-type abbreviations rewritten to their canonical short form
 * ("ST" -> "St", not "St" of an unknown word nor "St" left ALL-CAPS).
 * Full spellings map to themselves title-cased and are included so the
 * map covers both shapes of the same data.
 */
private val STREET_TYPES = mapOf(
    "ST" to "St", "STREET" to "Street",
    "RD" to "Rd", "ROAD" to "Road",
    "AVE" to "Ave", "AV" to "Ave", "AVENUE" to "Avenue",
    "DR" to "Dr", "DRV" to "Drv", "DRIVE" to "Drive",
    "PL" to "Pl", "PLACE" to "Place",
    "CRES" to "Cres", "CRESCENT" to "Crescent",
    "PDE" to "Pde", "PARADE" to "Parade",
    "TCE" to "Tce", "TERRACE" to "Terrace",
    "HWY" to "Hwy", "HIGHWAY" to "Highway",
    "BLVD" to "Blvd", "BOULEVARD" to "Boulevard",
    "LN" to "Ln", "LANE" to "Lane",
    "CL" to "Cl", "CLOSE" to "Close",
    "ESP" to "Esp", "ESPLANADE" to "Esplanade",
    "CIR" to "Cir", "CIRCUIT" to "Circuit",
    "CT" to "Ct", "COURT" to "Court",
    "SQ" to "Sq", "SQUARE" to "Square",
    "GDNS" to "Gdns", "GARDENS" to "Gardens",
    "PKWY" to "Pkwy", "PARKWAY" to "Parkway",
    "WAY" to "Way", "WALK" to "Walk", "VIEW" to "View",
    "HILL" to "Hill", "PARK" to "Park",
)

/**
 * One address word for display: title-case, keeping known state
 * abbreviations all-caps and rewriting street-type abbreviations to their
 * canonical form. Words containing digits ("12A", "3RD") pass through
 * unchanged — they are unit/section markers, not words to case.
 */
fun titleCaseAddressWord(word: String): String {
    val upper = word.uppercase()
    if (upper in KEEP_UPPER) return upper
    STREET_TYPES[upper]?.let { return it }
    if (upper.any { it.isDigit() }) return word
    return word.lowercase().replaceFirstChar { it.uppercase() }
}

/** Title-cases every word of an address-like text (street, city). */
fun titleCaseAddressText(text: String): String =
    text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        .joinToString(" ") { titleCaseAddressWord(it) }

/**
 * The indexed display name: `<unit/number> <title-cased street>` — e.g.
 * "69 Mott Street" or "12/45 Harbour Road". [number] and [street] must be
 * non-blank (the extractor skips features missing either); a blank [unit]
 * is rendered as absent, not "/69".
 */
fun addressName(number: String, unit: String, street: String): String {
    val prefix = if (unit.isBlank()) number.trim() else "${unit.trim()}/${number.trim()}"
    return "$prefix ${titleCaseAddressText(street)}"
}

/**
 * The dedupe key for an address row: identity is the composed text PLUS a
 * quantized location, because identical texts legitimately exist in many
 * towns ("69 Mott Street" is not unique to Singapore) and must not
 * collapse into one row. The quantized cell (~100 m at 3 decimals) keeps
 * the key tight — it is also the unique-index key, and 14M rows pay for
 * every byte of it. Re-indexing the same tile reproduces the same key; a
 * rare same-cell text collision is swallowed by insert-or-ignore.
 */
fun addressDedupeKey(number: String, unit: String, street: String, lat: Double, lon: Double): String {
    val scale = 10.0.pow(SearchIndexer.ADDRESS_QUANTIZE_DECIMALS)
    val lonQ = (lon * scale).roundToLong()
    val latQ = (lat * scale).roundToLong()
    return "${SearchIndexer.KIND_ADDRESS}|$lonQ|$latQ|${unit.trim()}|" +
        "${number.trim()}|${street.trim().uppercase()}"
}