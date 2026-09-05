package com.danemadsen.atlas.search

import com.danemadsen.atlas.pmtiles.PmtilesReader
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end extraction against a REAL merged country archive (a CI
 * artifact: base tiles + the CI-merged OpenAddresses layer). Skipped
 * unless ATLAS_MERGED_ARCHIVE points at one — CI has no multi-GB
 * fixture, so there the test is a no-op; locally:
 *
 *   ATLAS_MERGED_ARCHIVE=/tmp/atlas-australia.pmtiles \
 *     gradlew :lib:search:jvmTest --tests MergedArchiveAddressTest
 */
class MergedArchiveAddressTest {

    @Test
    fun `the merged archive yields address rows through the real reader`() {
        val path = System.getenv("ATLAS_MERGED_ARCHIVE") ?: return

        PmtilesReader(path).use { reader ->
            // Melbourne CBD — the run's verify step probes this same point.
            val (lon, lat) = 144.9631 to -37.8142
            val zoom = SearchIndexer.ADDRESS_INDEX_ZOOM
            val n = 1 shl zoom
            val x = ((lon + 180.0) / 360.0 * n).toInt()
            val y = ((1.0 - asinh(tan(Math.toRadians(lat))) / PI) / 2.0 * n).toInt()
            val bytes = reader.tile(zoom, x, y)
                ?: error("no z$zoom tile at $x/$y in ${path.takeLastAfterSlash()}")
            val rows = SearchIndexer.candidatesFromTile(zoom, x, y, bytes)
            println(
                "merged-archive tile $zoom/$x/$y: ${rows.addresses.size} addresses, " +
                    "${rows.places.size} places; sample: ${rows.addresses.take(3).map { it.name to it.city }}",
            )

            // A dense CBD tile carries hundreds of G-NAF points; a handful
            // means the merge clipped wrong, zero means the reader and the
            // python prover disagree about where the layer lives.
            assertTrue(
                rows.addresses.size > 100,
                "expected a dense Melbourne address tile, got ${rows.addresses.size}",
            )
            assertTrue(
                rows.addresses.all { it.name.isNotBlank() && it.lon != 0.0 && it.lat != 0.0 },
                "an address row came out blank",
            )
            assertTrue(
                rows.addresses.none { it.name.contains(Regex("\\d\\.0 ")) },
                "a house number leaked a decimal tail: ${rows.addresses.firstOrNull { Regex("\\d\\.0 ").containsMatchIn(it.name) }?.name}",
            )
            // The base layers must survive the merge in the same tile.
            assertTrue(rows.places.isNotEmpty(), "the merge lost the place layer")
        }
    }

    private fun String.takeLastAfterSlash(): String = substringAfterLast('/')
}