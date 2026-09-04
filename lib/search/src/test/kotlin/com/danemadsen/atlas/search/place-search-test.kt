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
    fun `database file name embeds the fingerprint`() {
        val file = SearchIndexer.databaseFile(java.io.File("/tmp/search"), "abc123")
        assertEquals(java.io.File("/tmp/search/search-abc123.db"), file)
    }
}