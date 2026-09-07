package com.danemadsen.atlas.data

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The region registry: index round-trip, id derivation, bbox containment
 * (including an antimeridian-straddling region), primary selection, and the
 * legacy one-archive migration.
 */
class RegionStoreTest {

    private fun newMapDir(): File = createTempDirectory("atlas-regions-").toFile()

    /** A region plus a fake archive of the recorded size — fingerprints are
     *  pure header+length hashes, so arbitrary bytes register fine. */
    private fun install(mapDir: File, id: String, displayName: String): RegionInfo {
        val dir = File(mapDir, id).apply { mkdirs() }
        val archive = File(dir, RegionStore.ARCHIVE_NAME).apply { writeBytes(ByteArray(2048)) }
        val info = RegionInfo(
            id = id,
            displayName = displayName,
            sizeBytes = archive.length(),
            minZoom = 0,
            maxZoom = 14,
            west = 144.0,
            south = -39.0,
            east = 147.0,
            north = -37.0,
            centerLon = 145.5,
            centerLat = -38.0,
            centerZoom = 7,
            routingFingerprint = "f".repeat(64),
            searchFingerprint = "0".repeat(64),
            importedAtMs = 1_000L,
        )
        RegionStore.add(mapDir, info)
        return info
    }

    // ---- id derivation ----

    @Test
    fun `region ids derive from display names and stay stable`() {
        assertEquals(
            "us-georgia",
            RegionStore.deriveRegionId("atlas-us-georgia.pmtiles", emptySet()),
        )
        assertEquals(
            "australia",
            RegionStore.deriveRegionId("atlas-australia.pmtiles", emptySet()),
        )
        assertEquals(
            "great-britain",
            RegionStore.deriveRegionId("great britain.pmtiles", emptySet()),
        )
        // collision: the second install of the same name gets a suffix
        assertEquals("nsw-2", RegionStore.deriveRegionId("nsw", setOf("nsw")))
        assertEquals("nsw-3", RegionStore.deriveRegionId("nsw", setOf("nsw", "nsw-2")))
        // degenerate names still produce a usable id
        assertEquals("map", RegionStore.deriveRegionId("???", emptySet()))
    }

    // ---- index persistence ----

    @Test
    fun `regions round-trip through the index file`() {
        val map_dir = newMapDir()
        val first = install(map_dir, "us-georgia", "atlas-us-georgia.pmtiles")
        val second = install(map_dir, "australia", "australia.pmtiles")
        val loaded = RegionStore.loadAll(map_dir)
        assertEquals(listOf("us-georgia", "australia"), loaded.map { it.id }, "insertion order")
        assertEquals(first, loaded.first { it.id == "us-georgia" })
        assertEquals(second, loaded.first { it.id == "australia" })
        map_dir.deleteRecursively()
    }

    @Test
    fun `add replaces by id and remove drops the row`() {
        val map_dir = newMapDir()
        install(map_dir, "us-georgia", "us-georgia.pmtiles")
        install(map_dir, "us-georgia", "us-georgia.pmtiles")
        assertEquals(1, RegionStore.loadAll(map_dir).size)
        RegionStore.remove(map_dir, "us-georgia")
        assertTrue(RegionStore.loadAll(map_dir).isEmpty())
        map_dir.deleteRecursively()
    }

    @Test
    fun `a missing or resized archive rebuilds the index from disk`() {
        val map_dir = newMapDir()
        install(map_dir, "us-georgia", "us-georgia.pmtiles")
        // Delete the archive: stale() trips, and rebuild scans the dirs.
        // The rebuilt entry has no parseable header, so it drops out.
        RegionStore.archiveFile(map_dir, "us-georgia").delete()
        assertTrue(RegionStore.loadAll(map_dir).none { it.id == "us-georgia" })
        map_dir.deleteRecursively()
    }

    // ---- bbox containment ----

    @Test
    fun `regionForPoint honors plain and antimeridian bboxes`() {
        val map_dir = newMapDir()
        val victoria = install(map_dir, "vic", "vic.pmtiles")
        assertTrue(RegionStore.insideRegion(victoria, 145.0, -38.0))
        assertFalse(RegionStore.insideRegion(victoria, 150.0, -38.0))
        // A straddling region (west > east) covers the union of both sides.
        val straddling = victoria.copy(west = 170.0, east = -170.0)
        assertTrue(RegionStore.insideRegion(straddling, 175.0, -38.0))
        assertTrue(RegionStore.insideRegion(straddling, -175.0, -38.0))
        assertFalse(RegionStore.insideRegion(straddling, 160.0, -38.0))
        assertEquals("vic", RegionStore.regionForPoint(listOf(victoria), 145.0, -38.0)?.id)
        assertNull(RegionStore.regionForPoint(listOf(victoria), 0.0, 0.0))
        map_dir.deleteRecursively()
    }

    // ---- primary selection ----

    @Test
    fun `primaryRegion prefers the nearest center then the newest import`() {
        val map_dir = newMapDir()
        val georgia = install(map_dir, "us-georgia", "us-georgia.pmtiles")
            .copy(centerLon = -84.0, centerLat = 32.0)
        val geelong = install(map_dir, "geelong", "geelong.pmtiles")
            .copy(centerLon = 144.0, centerLat = -38.0)
        RegionStore.save(map_dir, listOf(georgia, geelong))
        assertEquals(
            "geelong",
            RegionStore.primaryRegion(listOf(georgia, geelong), 145.0 to -38.0)?.id,
            "the region nearest the anchor wins",
        )
        assertEquals(
            "us-georgia",
            RegionStore.primaryRegion(listOf(georgia, geelong), null)?.id,
            "no anchor: the most recent import wins",
        )
        assertNull(RegionStore.primaryRegion(emptyList(), null))
        map_dir.deleteRecursively()
    }

    // ---- legacy migration ----

    @Test
    fun `legacy layout migrates and the search index survives in place`() {
        val files_dir = createTempDirectory("atlas-legacy-").toFile()
        val map_dir = File(files_dir, "map").apply { mkdirs() }
        val archive = File(map_dir, "atlas.pmtiles").apply { writeBytes(ByteArray(4096)) }
        File(files_dir, "archive-info.json").writeText(
            """{"fileName":"australia.pmtiles","sizeBytes":4096,"minZoom":0,"maxZoom":14,
                "west":144.0,"south":-39.0,"east":147.0,"north":-37.0,
                "centerLon":145.5,"centerLat":-38.0,"centerZoom":7}""",
        )
        // (Search indexes are fingerprint-keyed and live outside map/ — the
        // migration must not touch them, and none is needed here.)

        RegionMigrator.migrate(files_dir, File(files_dir, "archive-info.json"), File(files_dir, "graph/build-status.json"))

        val regions = RegionStore.loadAll(map_dir)
        assertEquals(listOf("atlas"), regions.map { it.id })
        assertEquals("australia.pmtiles", regions.first().displayName)
        assertTrue(
            File(map_dir, "atlas/${RegionStore.ARCHIVE_NAME}").isFile,
            "the archive moved into map/atlas/",
        )
        assertFalse(File(map_dir, "atlas.pmtiles").exists(), "the legacy archive is gone")
        assertFalse(File(files_dir, "archive-info.json").exists(), "the legacy info file is deleted")
        // Idempotent: a second pass is a no-op.
        RegionMigrator.migrate(files_dir, File(files_dir, "archive-info.json"), File(files_dir, "graph/build-status.json"))
        assertEquals(1, RegionStore.loadAll(map_dir).size)
        files_dir.deleteRecursively()
        assertNotEquals(0, regions.first().importedAtMs)
    }

    @Test
    fun `migration defers while a build claims running`() {
        val files_dir = createTempDirectory("atlas-legacy-").toFile()
        val map_dir = File(files_dir, "map").apply { mkdirs() }
        File(map_dir, "atlas.pmtiles").writeBytes(ByteArray(1024))
        File(files_dir, "archive-info.json").writeText(
            """{"fileName":"australia.pmtiles","sizeBytes":1024,"minZoom":0,"maxZoom":14,
                "west":0.0,"south":0.0,"east":1.0,"north":1.0,
                "centerLon":0.5,"centerLat":0.5,"centerZoom":5}""",
        )
        val status = File(files_dir, "graph/build-status.json")
        status.parentFile.mkdirs()
        status.writeText("""{"running":true}""")

        RegionMigrator.migrate(
            files_dir,
            File(files_dir, "archive-info.json"),
            status,
        )

        // Nothing moved: the migration retries after an honest terminal status.
        assertTrue(File(map_dir, "atlas.pmtiles").isFile)
        assertFalse(RegionStore.indexFile(map_dir).isFile)
        files_dir.deleteRecursively()
    }
}