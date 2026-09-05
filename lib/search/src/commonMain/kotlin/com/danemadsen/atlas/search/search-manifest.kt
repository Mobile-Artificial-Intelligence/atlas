package com.danemadsen.atlas.search

/**
 * The manifest a CI-built search index carries (see
 * [SearchIndexCli] / [SearchCoordinator.adoptPrebuiltIndex]): a
 * safe-ASCII, hand-rolled-JSON record of the archive fingerprint the index
 * was built from and the row counts it ended up with. The fingerprint is
 * load-bearing — it stops an index minted from a different daily archive
 * from installing a silently-wrong search — and the counts are the adopt
 * step's proof the DB matches what the manifest claims.
 */
const val SEARCH_MANIFEST_FORMAT = 1

/** The manifest's file name inside the artifact dir (see [SearchIndexCli]). */
const val SEARCH_MANIFEST_FILE = "manifest.json"

data class SearchManifest(
    val archiveFingerprint: String,
    val places: Int,
    val addresses: Int,
)

/**
 * Renders the adopt gate's manifest — field order fixed, no spaces, so the
 * gate's regexes (and a human `cat`) read it back deterministically.
 */
fun renderSearchManifest(archiveFingerprint: String, places: Int, addresses: Int): String =
    "{\"format\":$SEARCH_MANIFEST_FORMAT," +
        "\"archiveFingerprint\":\"$archiveFingerprint\"," +
        "\"places\":$places," +
        "\"addresses\":$addresses}"

/**
 * Parses what [renderSearchManifest] renders, refusing malformed manifests
 * with the adopt path's own user-actionable messages (lowercase, ending in
 * the fix).
 */
fun parseSearchManifest(text: String): SearchManifest {
    val format = Regex("\"format\":(-?\\d+)").find(text)?.groupValues?.get(1)?.toInt()
        ?: error("search index has no manifest format — re-download the search index " +
            "and the map archive from the same build, then install both")
    require(format == SEARCH_MANIFEST_FORMAT) {
        "the search index is an unsupported format (version $format) — update the " +
            "app, or re-download the search index from the same build as your map archive"
    }
    val fingerprint = Regex("\"archiveFingerprint\":\"([0-9a-f]+)\"").find(text)
        ?: error("search index has no archive fingerprint — re-download the search index " +
            "and the map archive from the same build, then install both")
    val fp = fingerprint.groupValues[1]
    require(fp.length == 64 && fp.all { it in '0'..'9' || it in 'a'..'f' }) {
        "search index has a malformed archive fingerprint — re-download the search " +
            "index and the map archive from the same build, then install both"
    }
    val places = Regex("\"places\":(-?\\d+)").find(text)?.groupValues?.get(1)?.toInt()
        ?: error("search index has no place count — re-download the search index and " +
            "the map archive from the same build, then install both")
    val addresses = Regex("\"addresses\":(-?\\d+)").find(text)?.groupValues?.get(1)?.toInt()
        ?: error("search index has no address count — re-download the search index and " +
            "the map archive from the same build, then install both")
    return SearchManifest(fp, places, addresses)
}