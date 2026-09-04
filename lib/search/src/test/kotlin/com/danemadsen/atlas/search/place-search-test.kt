package com.danemadsen.atlas.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaceSearchTest {

    @Test
    fun `every token becomes a quoted prefix`() {
        assertEquals("\"melb\"*", ftsPattern("melb"))
        assertEquals("\"port\"* \"macqu\"*", ftsPattern("  port   macqu "))
    }

    @Test
    fun `match syntax cannot be injected`() {
        // Every token is wrapped in quotes before the star, so a bare OR is
        // a term (never FTS's OR operator) and user quotes are stripped
        // first so they cannot break out of the wrapping.
        assertEquals("\"port\"* \"OR\"*", ftsPattern("\"port\" OR"))
        assertEquals("\"near\"*", ftsPattern("near\"*"))
    }

    @Test
    fun `dash-prefixed and hyphenated tokens stay literal terms`() {
        // A leading dash would otherwise read as FTS's NOT operator (or
        // crash the parser); a mid-token hyphen splits into an adjacent
        // phrase, matching the same shape the unicode61 tokenizer stored
        // for the hyphenated name.
        assertEquals("\"-north\"* \"st\"*", ftsPattern("-north st"))
        assertEquals("\"north-west\"* \"rd\"*", ftsPattern("north-west rd"))
    }

    @Test
    fun `address-shaped queries hit the address table, others do not`() {
        assertTrue(isAddressQuery("69 mott street"), "number + street")
        assertTrue(isAddressQuery("12/45 harbour"), "unit-slash-number + street")
        assertTrue(isAddressQuery("69 mott"), "partial number + street")
        // No word token: a bare number stays on the place-only path (it
        // would be a huge address prefix for no useful result).
        assertFalse(isAddressQuery("69"), "bare number")
        // No digit token: a street or place name never enumerates addresses.
        assertFalse(isAddressQuery("melbourne"), "single token")
        assertFalse(isAddressQuery("queen st"), "street name only")
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
        // INDEX_FORMAT is folded into the fingerprint's canonical string:
        // bumping it rebuilds every index exactly once (new DB file name,
        // new completion marker). The exact value is asserted on purpose —
        // a bump must be a conscious change that fails this line, not a
        // silent edit that strands old DBs as unexplained stale disk.
        assertEquals(3, SearchIndexer.INDEX_FORMAT, "index format — 3: address table split")
    }

    @Test
    fun `street address queries tokenize into the address row`() {
        // ANDed quoted prefixes over the composed name "69 Mott Street":
        // the number is a first-class FTS token, not punctuation to strip.
        assertEquals("\"69\"* \"mott\"* \"street\"*", ftsPattern("69 mott street"))
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
    fun `the unit participates in the address dedupe key`() {
        // Unit 12 and street-level 45 of the same building are two rows —
        // the unit is part of the key, not folded into the number.
        val with_unit = addressDedupeKey("45", "12", "HARBOUR RD", -37.8142, 144.9631)
        val without_unit = addressDedupeKey("45", "", "HARBOUR RD", -37.8142, 144.9631)
        assertTrue(with_unit != without_unit, "unit vs no-unit must be different rows")
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
    fun `database and completion-marker names embed the fingerprint`() {
        val dir = java.io.File("/tmp/search")
        assertEquals(java.io.File("/tmp/search/search-abc123.db"), SearchIndexer.databaseFile(dir, "abc123"))
        // The marker is written only by a pass that ran to the end, and
        // lives beside the DB it completes.
        assertEquals(java.io.File("/tmp/search/search-abc123.done"), SearchIndexer.completionFile(dir, "abc123"))
    }
}