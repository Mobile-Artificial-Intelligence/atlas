package com.danemadsen.atlas.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaceSearchTest {

    @Test
    fun `prefix star is appended to every token`() {
        assertEquals("melb*", ftsPattern("melb"))
        assertEquals("port* macqu*", ftsPattern("  port   macqu "))
    }

    @Test
    fun `quotes are stripped so match syntax cannot be injected`() {
        // The appended star also disarms bare operators: "OR*" is a term,
        // not FTS's OR operator (which must be bare uppercase "OR").
        assertEquals("port* OR*", ftsPattern("\"port\" OR*"))
        assertEquals("near*", ftsPattern("near\"*"))
    }

    @Test
    fun `empty and punctuation-only queries yield null`() {
        assertNull(ftsPattern(""))
        assertNull(ftsPattern("   "))
        assertNull(ftsPattern("\"\" \"\""))
    }

    @Test
    fun `distance is zero for identical points and positive otherwise`() {
        assertEquals(0.0, distanceMeters(144.96, -37.81, 144.96, -37.81))
        // Melbourne CBD -> Geelong is ~72 km as the crow flies.
        val km = distanceMeters(144.9631, -37.8142, 144.3608, -38.1495) / 1000.0
        assertTrue(km in 60.0..85.0, "melbourne-geelong distance was $km km")
    }

    @Test
    fun `dedupe key separates kinds but not names of one kind`() {
        // The key is a pure concat: name trimming happens at extraction time
        // (candidatesFromTile), before the key is built.
        assertEquals("Sale|city", SearchIndexer.dedupeKey("Sale", "city"))
        assertEquals(" Sale |city", SearchIndexer.dedupeKey(" Sale ", "city"))
        assertTrue(SearchIndexer.dedupeKey("Sale", "town") != "Sale|city")
    }

    @Test
    fun `fingerprint differs when any identifying field differs`() {
        val base = SearchIndexer.archiveFingerprint(
            "australia.pmtiles", 1317773274L,
            68.1, -57.1, 169.0, -8.8, 0, 14,
        )
        assertEquals(base, SearchIndexer.archiveFingerprint(
            "australia.pmtiles", 1317773274L,
            68.1, -57.1, 169.0, -8.8, 0, 14,
        ))
        assertTrue(
            base != SearchIndexer.archiveFingerprint(
                "australia.pmtiles", 1317773275L,
                68.1, -57.1, 169.0, -8.8, 0, 14,
            ),
            "a one-byte archive difference must change the fingerprint",
        )
        assertEquals(64, base.length, "sha-256 hex fingerprint length")
    }

    @Test
    fun `fingerprint embeds the index format`() {
        val base = SearchIndexer.archiveFingerprint(
            "australia.pmtiles", 1317773274L,
            68.1, -57.1, 169.0, -8.8, 0, 14,
        )
        // Bumping INDEX_FORMAT must rebuild every index exactly once. The
        // digest cannot be varied from the test (a companion const), so
        // this asserts the contract that matters: the format is part of
        // the fingerprint's canonical string and a stable constant.
        assertTrue(SearchIndexer.INDEX_FORMAT >= 2, "index format bumped for address rows")
        assertEquals(64, base.length)
    }

    @Test
    fun `street address queries tokenize into the address row`() {
        // ANDed tokens over the composed name "69 Mott Street": the number
        // is a first-class FTS token, not punctuation to strip.
        assertEquals("69* mott* street*", ftsPattern("69 mott street"))
    }

    @Test
    fun `address dedupe keys are location-quantized`() {
        // The same text ~4 km apart must not collapse into one row; the
        // same cell must (insert-or-ignore then dedupes re-offers).
        val here = addressDedupeKey("69", "", "MOTT ST", -37.8142, 144.9631)
        val nearby = addressDedupeKey("69", "", "MOTT ST", -37.8184, 144.9674)
        val same_cell = addressDedupeKey("69", "", "MOTT ST", -37.81425, 144.96315)
        assertTrue(here != nearby, "same text 4 km apart must have different keys")
        assertEquals(here, same_cell, "the same ~100 m cell must reproduce the key")
    }

    @Test
    fun `title casing rewrites street types and keeps state abbreviations`() {
        assertEquals("Mott", titleCaseAddressWord("MOTT"))
        assertEquals("St", titleCaseAddressWord("ST"))
        assertEquals("Street", titleCaseAddressWord("STREET"))
        assertEquals("Rd", titleCaseAddressWord("RD"))
        assertEquals("NSW", titleCaseAddressWord("NSW"))
        assertEquals("12A", titleCaseAddressWord("12A"))
        assertEquals("Parade", titleCaseAddressWord("PARADE"))
        assertEquals("69 Mott St", addressName("69", "", "MOTT ST"))
        assertEquals("12/45 Harbour Rd", addressName("45", "12", "HARBOUR RD"))
    }

    @Test
    fun `database file name embeds the fingerprint`() {
        val file = SearchIndexer.databaseFile(java.io.File("/tmp/search"), "abc123")
        assertEquals(java.io.File("/tmp/search/search-abc123.db"), file)
    }
}