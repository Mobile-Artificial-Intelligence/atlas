package com.danemadsen.atlas.pmtiles

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PmtilesReaderTest {

    // ---- test-only PMTiles serializer (mirrors the v3 spec) ----

    private data class TestEntry(
        val tileId: Long,
        val runLength: Int,
        val length: Int,
        val offset: Long,
    )

    private fun serializeDirectory(entries: List<TestEntry>): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarint(out, entries.size.toLong())
        // tile IDs: first absolute, rest delta
        writeVarint(out, entries[0].tileId)
        for (i in 1 until entries.size) {
            writeVarint(out, entries[i].tileId - entries[i - 1].tileId)
        }
        // run lengths + lengths: as-is
        entries.forEach { writeVarint(out, it.runLength.toLong()) }
        entries.forEach { writeVarint(out, it.length.toLong()) }
        // offsets: offset+1, or 0 when contiguous with the previous entry
        writeVarint(out, entries[0].offset + 1)
        for (i in 1 until entries.size) {
            val prevEnd = entries[i - 1].offset + entries[i - 1].length
            if (entries[i].offset == prevEnd) {
                writeVarint(out, 0)
            } else {
                writeVarint(out, entries[i].offset + 1)
            }
        }
        return gzip(out.toByteArray())
    }

    private fun writeVarint(out: ByteArrayOutputStream, value_in: Long) {
        var value = value_in
        while (true) {
            val byte = (value and 0x7F).toInt()
            value = value ushr 7
            if (value == 0L) {
                out.write(byte)
                return
            }
            out.write(byte or 0x80)
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    /** Builds a v3 archive: [tileBlobs] by tile id, plus metadata; returns
     *  the serialized 127-byte header + sections in one byte array. */
    private fun buildArchive(
        tilesByTileId: Map<Long, ByteArray>,
        metadata: String = """{"name":"atlas-test"}""",
        forceLeafDirectory: Boolean = false,
    ): ByteArray {
        // Layout: header | root dir | metadata | leaf dirs (optional) | tile data
        val sortedTiles = tilesByTileId.toSortedMap()
        val tileData = sortedTiles.values.reduce { a, b -> a + b }
        var offset = 0L
        val entries = sortedTiles.map { (id, bytes) ->
            val entry = TestEntry(id, runLength = 1, length = bytes.size, offset = offset)
            offset += bytes.size
            entry
        }

        val headerSize = 127
        val metadataBytes = gzip(metadata.toByteArray(Charsets.UTF_8))

        val rootBytes: ByteArray
        val leafSectionBytes: ByteArray
        if (forceLeafDirectory && entries.isNotEmpty()) {
            leafSectionBytes = serializeDirectory(entries)
            rootBytes = serializeDirectory(
                listOf(
                    TestEntry(
                        tileId = entries.first().tileId,
                        runLength = 0,
                        length = leafSectionBytes.size,
                        offset = 0,
                    ),
                ),
            )
        } else {
            rootBytes = serializeDirectory(entries)
            leafSectionBytes = ByteArray(0)
        }

        val rootOffset = headerSize.toLong()
        val metadataOffset = rootOffset + rootBytes.size
        val leafOffset = metadataOffset + metadataBytes.size
        val tileDataOffset = leafOffset + leafSectionBytes.size

        val header = ByteArray(headerSize)
        "PMTiles".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        header[7] = 3
        writeUint64(header, 8, rootOffset)
        writeUint64(header, 16, rootBytes.size.toLong())
        writeUint64(header, 24, metadataOffset)
        writeUint64(header, 32, metadataBytes.size.toLong())
        writeUint64(header, 40, leafOffset)
        writeUint64(header, 48, leafSectionBytes.size.toLong())
        writeUint64(header, 56, tileDataOffset)
        writeUint64(header, 64, tileData.size.toLong())
        writeUint64(header, 72, sortedTiles.size.toLong())
        writeUint64(header, 80, entries.size.toLong())
        writeUint64(header, 88, sortedTiles.size.toLong())
        header[96] = 0 // not clustered
        header[97] = 2 // gzip internal
        header[98] = 1 // tile bytes stored raw
        header[99] = 1 // MVT
        header[100] = 0 // min zoom
        header[101] = 14 // max zoom
        writeInt32(header, 102, (144.0 * 10_000_000).toInt())
        writeInt32(header, 106, (-38.0 * 10_000_000).toInt())
        writeInt32(header, 110, (146.0 * 10_000_000).toInt())
        writeInt32(header, 114, (-36.0 * 10_000_000).toInt())
        header[118] = 8
        writeInt32(header, 119, (145.0 * 10_000_000).toInt())
        writeInt32(header, 123, (-37.0 * 10_000_000).toInt())

        return header + rootBytes + metadataBytes + leafSectionBytes + tileData
    }

    private fun writeUint64(buffer: ByteArray, offset: Int, value: Long) {
        var v = value
        for (i in 0 until 8) {
            buffer[offset + i] = (v and 0xFF).toByte()
            v = v shr 8
        }
    }

    private fun writeInt32(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun openReader(bytes: ByteArray): PmtilesReader {
        val temp_file = File.createTempFile("atlas-pmtiles-test", ".pmtiles")
        temp_file.deleteOnExit()
        FileOutputStream(temp_file).use { it.write(bytes) }
        return PmtilesReader(RandomAccessFile(temp_file, "r"))
    }

    // ---- tests ----

    @Test
    fun headerFieldsParse() {
        val reader = openReader(
            buildArchive(
                mapOf(
                    HilbertTileId.tileId(1, 0, 0) to "tile-A".toByteArray(),
                ),
            ),
        )
        reader.use {
            assertEquals(TileType.MVT, it.header.tileType)
            assertEquals(Compression.GZIP, it.header.internalCompression)
            assertEquals(Compression.NONE, it.header.tileCompression)
            assertEquals(0, it.header.minZoom)
            assertEquals(14, it.header.maxZoom)
            assertEquals(144.0, it.header.minLon)
            assertEquals(-38.0, it.header.minLat)
            assertEquals(146.0, it.header.maxLon)
            assertEquals(-36.0, it.header.maxLat)
            assertEquals(145.0, it.header.centerLon)
            assertEquals(-37.0, it.header.centerLat)
            assertEquals(8, it.header.centerZoom)
        }
    }

    @Test
    fun contiguousOffsetsAndRunLengthsResolve() {
        // ids 1,2 (z1 0,0 and 0,1) contiguous; id 3 (z1 1,1) after a gap in
        // the addressed ids but still contiguous in bytes.
        val reader = openReader(
            buildArchive(
                mapOf(
                    HilbertTileId.tileId(1, 0, 0) to "tile-A".toByteArray(),
                    HilbertTileId.tileId(1, 0, 1) to "tile-B".toByteArray(),
                    HilbertTileId.tileId(1, 1, 1) to "tile-C".toByteArray(),
                ),
            ),
        )
        reader.use {
            assertEquals("tile-A", String(it.tile(1, 0, 0)!!))
            assertEquals("tile-B", String(it.tile(1, 0, 1)!!))
            assertEquals("tile-C", String(it.tile(1, 1, 1)!!))
            // z1 (1,0) is id 4 — not addressed.
            assertNull(it.tile(1, 1, 0))
            assertNull(it.tile(3, 1, 1))
        }
    }

    @Test
    fun leafDirectoryLookupResolves() {
        val reader = openReader(
            buildArchive(
                mapOf(
                    HilbertTileId.tileId(2, 1, 1) to "leaf-tile".toByteArray(),
                ),
                forceLeafDirectory = true,
            ),
        )
        reader.use {
            assertEquals("leaf-tile", String(it.tile(2, 1, 1)!!))
            assertNull(it.tile(2, 0, 0))
        }
    }

    @Test
    fun metadataDecompresses() {
        val reader = openReader(
            buildArchive(mapOf(HilbertTileId.tileId(0, 0, 0) to "t".toByteArray())),
        )
        reader.use {
            assertEquals("""{"name":"atlas-test"}""", it.metadata())
        }
    }

    @Test
    fun rejectsNonPmtilesAndWrongVersion() {
        assertFailsWith<IllegalArgumentException> {
            PmtilesHeader.parse(ByteArray(127))
        }
        val badVersion = ByteArray(127)
        "PMTiles".toByteArray().copyInto(badVersion)
        badVersion[7] = 2
        assertFailsWith<IllegalArgumentException> {
            PmtilesHeader.parse(badVersion)
        }
    }

    @Test
    fun tileRangeCoversMelbourne() {
        val range = readerTileRange(14, TileBounds(144.90, -37.87, 145.05, -37.75))
        // z14: 360/16384 = 0.02197° per tile; 0.15° of longitude spans 8 tiles.
        assertEquals(7, range.maxX - range.minX)
        // Mercator y grows southwards: the north edge must give the smaller y.
        assertTrue(range.minY < range.maxY, "inverted lat range: $range")
        // 0.12° of latitude at 37.8°N covers ~7 mercator tiles.
        assertTrue(range.maxY - range.minY in 6..10, "lat span ${range.maxY - range.minY}")
    }

    private fun readerTileRange(zoom: Int, bounds: TileBounds): TileRange =
        TileRange(
            minX = PmtilesReader.lonToTileX(bounds.west, zoom),
            maxX = PmtilesReader.lonToTileX(bounds.east, zoom),
            minY = PmtilesReader.latToTileY(bounds.north, zoom),
            maxY = PmtilesReader.latToTileY(bounds.south, zoom),
        )

    // ---- integration: the dev corpus (skipped when absent) ----

    @Test
    fun readsRealAustraliaArchive() {
        val archive = File(System.getProperty("user.home"), "atlas-prototype/tmp/australia.pmtiles")
        if (!archive.exists()) {
            println("skipping: no $archive on this machine")
            return
        }
        PmtilesReader.open(archive.absolutePath).use { reader ->
            val header = reader.header
            println("australia.pmtiles: $header")
            assertTrue(header.maxZoom >= 14)
            assertEquals(TileType.MVT, header.tileType)
            assertTrue(header.numberOfTileEntries > 1_000_000L)

            // Melbourne CBD z14 tiles through the real root/leaf directories.
            val melbourne = TileBounds(144.94, -37.83, 144.97, -37.80)
            var sawTransportationFeature = false
            reader.forEachTileInBounds(14, melbourne) { z, x, y, bytes ->
                val tile = com.danemadsen.atlas.pmtiles.mvt.MvtTile.decode(bytes)
                val layer = tile.layer("transportation") ?: return@forEachTileInBounds
                for (feature in layer.features) {
                    if (feature.geomType != com.danemadsen.atlas.pmtiles.mvt.MvtGeomType.LINESTRING) continue
                    val props = layer.properties(feature)
                    val roadClass = props["class"] ?: continue
                    val paths = layer.pathsLonLat(feature, z, x, y)
                    assertTrue(paths.all { it.size >= 2 }, "linestring with < 2 points")
                    val lon = paths.first().first().lon
                    val lat = paths.first().first().lat
                    // MVT features are clipped to the tile extent, so the
                    // converted point must fall back onto this tile (±1 for
                    // the clip border at shared edges).
                    val rx = PmtilesReader.lonToTileX(lon, z)
                    val ry = PmtilesReader.latToTileY(lat, z)
                    assertTrue(kotlin.math.abs(rx - x) <= 1, "lon $lon -> x=$rx, tile x=$x")
                    assertTrue(kotlin.math.abs(ry - y) <= 1, "lat $lat -> y=$ry, tile y=$y")
                    println("transportation/${roadClass}: ${paths.first().size} points")
                    sawTransportationFeature = true
                    break
                }
            }
            assertTrue(sawTransportationFeature, "no transportation features in Melbourne z14")
        }
    }
}