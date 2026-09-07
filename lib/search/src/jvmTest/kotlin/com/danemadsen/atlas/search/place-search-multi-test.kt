package com.danemadsen.atlas.search

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The multi-DB merge behind two installed regions: each DB runs the same
 * per-DB query, results merge under the single rank-then-distance ordering,
 * and the global winner can come from any DB.
 */
class PlaceSearchMultiTest {

    private fun openDb(dir: File): PlaceDatabase =
        Room.databaseBuilder<PlaceDatabase>(dir.resolve("search.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()

    private fun place(
        name: String,
        kind: String = "city",
        rank: Int = 10,
        lon: Double,
        lat: Double,
    ) = PlaceEntity(
        name = name,
        kind = kind,
        subclass = null,
        rank = rank,
        lon = lon,
        lat = lat,
        zoom = 4,
        dedupeKey = SearchIndexer.dedupeKey(name, kind),
    )

    private fun seed(db: PlaceDatabase, vararg rows: PlaceEntity) = runBlocking {
        db.placeDao().insertBatch(rows.toList())
        db.placeDao().rebuildFts()
    }

    /** Runs the multi search and returns hit names in rank order. */
    private fun search(
        vararg dbs: PlaceDatabase,
        lon: Double,
        lat: Double,
    ): List<String> = runBlocking {
        searchPlacesMulti(
            dbs = dbs.toList(),
            rawQuery = "harbour",
            centerLon = lon,
            centerLat = lat,
        ).map { it.name }
    }

    // ---- tests ----

    @Test
    fun `merges across databases under one ranking`() {
        val dir = createTempDirectory("atlas-multi-").toFile()
        val region_one = openDb(dir.resolve("one"))
        val region_two = openDb(dir.resolve("two"))
        // Region one holds a rank-10 hit; region two holds a rank-5 hit
        // (the global winner) plus a rank-10 tie that must lose to region
        // one's row on distance.
        seed(region_one, place("Harbour", lon = 144.96, lat = -37.81))
        seed(
            region_two,
            place("Harbour Point", rank = 5, lon = 145.10, lat = -37.60),
            place("Harbour Away", lon = 150.0, lat = -38.0),
        )
        val hits = search(region_one, region_two, lon = 144.96, lat = -37.81)
        assertEquals(listOf("Harbour Point", "Harbour", "Harbour Away"), hits)
        region_one.close()
        region_two.close()
        dir.deleteRecursively()
    }

    @Test
    fun `a region whose index is empty contributes nothing and never fails the query`() {
        val dir = createTempDirectory("atlas-multi-").toFile()
        val populated = openDb(dir.resolve("populated"))
        val empty = openDb(dir.resolve("empty"))
        seed(populated, place("Harbour", lon = 144.96, lat = -37.81))
        val hits = search(populated, empty, lon = 144.96, lat = -37.81)
        assertEquals(listOf("Harbour"), hits)
        populated.close()
        empty.close()
        dir.deleteRecursively()
    }

    @Test
    fun `no databases means no hits, not an error`() {
        // Sanity guard for the fresh-install state before any region is
        // imported: the empty DB list short-circuits to emptyList.
        val hits = runBlocking { searchPlacesMulti(emptyList(), "harbour", 0.0, 0.0) }
        assertTrue(hits.isEmpty())
    }
}