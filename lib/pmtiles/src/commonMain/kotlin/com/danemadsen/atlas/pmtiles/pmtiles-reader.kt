package com.danemadsen.atlas.pmtiles

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream

/**
 * Random-access reader for a PMTiles v3 archive. The root directory is kept
 * in memory (it is <= ~16 KiB compressed by spec); leaf directories are
 * cached LRU since the graph builder walks tiles in curve order, which
 * clusters into the same leaves.
 */
class PmtilesReader(
    private val file: RandomAccessFile,
) : Closeable {

    val header: PmtilesHeader

    private val rootDirectory: List<PmtilesEntry>
    private val leafCache = LinkedHashMap<Long, List<PmtilesEntry>>()

    constructor(path: String) : this(RandomAccessFile(path, "r"))

    init {
        val headerBytes = ByteArray(PmtilesHeader.HEADER_SIZE)
        file.seek(0)
        file.readFully(headerBytes)
        header = PmtilesHeader.parse(headerBytes)
        rootDirectory = decodeDirectory(
            header.rootDirectoryOffset,
            header.rootDirectoryLength.toInt(),
            "root directory",
        )
    }

    /** The archive's JSON metadata, decompressed. */
    fun metadata(): String {
        if (header.metadataLength == 0L) return "{}"
        val bytes = readSection(header.metadataOffset, header.metadataLength.toInt())
        return String(decompress(bytes, header.internalCompression), Charsets.UTF_8)
    }

    /**
     * The decompressed tile bytes at (z, x, y), or null when the archive does
     * not contain that tile.
     */
    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        val entry = findTileEntry(z, x, y) ?: return null
        val raw = readSection(header.tileDataOffset + entry.offset, entry.length)
        return decompress(raw, header.tileCompression)
    }

    /** Offset of the tile in the tile-data section, or null when absent. */
    fun tileOffset(z: Int, x: Int, y: Int): Long? = findTileEntry(z, x, y)?.offset

    fun tileLength(z: Int, x: Int, y: Int): Int? = findTileEntry(z, x, y)?.length
    /**
     * Iterate every tile in [bounds] at [zoom], in curve order, passing the
     * decompressed tile bytes. Skips missing tiles.
     *
     * [onCellsProbed], when given, receives (cells visited so far, total
     * cells in the bounds' raster grid) every [PROBE_PROGRESS_EVERY] cells:
     * a bounds walk is minutes of work at detail zooms and the misses are
     * silent, so the caller can report an honest fraction. The callback must
     * not suspend or throw.
     */
    fun forEachTileInBounds(
        zoom: Int,
        bounds: TileBounds,
        onCellsProbed: ((probed: Long, total: Long) -> Unit)? = null,
        visitor: (z: Int, x: Int, y: Int, bytes: ByteArray) -> Unit,
    ) {
        val (minX, minY, maxX, maxY) = tileRange(zoom, bounds)
        val total = (maxX - minX + 1L) * (maxY - minY + 1L)
        var probed = 0L
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                probed++
                if (onCellsProbed != null && probed % PROBE_PROGRESS_EVERY == 0L) {
                    onCellsProbed(probed, total)
                }
                val bytes = tile(zoom, x, y) ?: continue
                visitor(zoom, x, y, bytes)
            }
        }
    }

    /** Web-mercator tile coordinates covering [bounds] at [zoom]. Mercator
     *  tile y grows southwards, so the north edge is the smaller y. */
    fun tileRange(zoom: Int, bounds: TileBounds): TileRange {
        val maxTileIndex = (1 shl zoom) - 1
        return TileRange(
            minX = lonToTileX(bounds.west, zoom).coerceIn(0, maxTileIndex),
            maxX = lonToTileX(bounds.east, zoom).coerceIn(0, maxTileIndex),
            minY = latToTileY(bounds.north, zoom).coerceIn(0, maxTileIndex),
            maxY = latToTileY(bounds.south, zoom).coerceIn(0, maxTileIndex),
        )
    }

    /**
     * [forEachTileInBounds], chunked and ordered by distance from [anchorLonLat]
     * (as `lon to lat`): the tile raster splits into [chunkTiles]²-tile chunks,
     * each walked with the same inner loop, chunks visited nearest-first.
     * Addresses near the anchor become searchable long before a continental
     * sweep reaches their side of the map.
     *
     * [anchorLonLat] null keeps the plain column-major order. Every tile is
     * visited exactly once either way, and [onCellsProbed] reports cumulative
     * cells against the FULL grid total, so progress stays monotonic and honest
     * regardless of visit order. The callback must not suspend or throw.
     */
    fun forEachTileChunkInBounds(
        zoom: Int,
        bounds: TileBounds,
        anchorLonLat: Pair<Double, Double>?,
        chunkTiles: Int = CHUNK_TILES,
        onCellsProbed: ((probed: Long, total: Long) -> Unit)? = null,
        visitor: (z: Int, x: Int, y: Int, bytes: ByteArray) -> Unit,
    ) {
        val (minX, minY, maxX, maxY) = tileRange(zoom, bounds)
        val cols = maxX - minX + 1
        val rows = maxY - minY + 1
        val total = cols.toLong() * rows
        val chunk_w = chunkTiles.coerceAtLeast(1)
        val chunks = buildList {
            var cy = 0
            while (cy * chunk_w < rows) {
                var cx = 0
                while (cx * chunk_w < cols) {
                    val x0 = minX + cx * chunk_w
                    val y0 = minY + cy * chunk_w
                    add(
                        Chunk(
                            zoom = zoom,
                            x0 = x0,
                            x1 = minOf(x0 + chunk_w - 1, maxX),
                            y0 = y0,
                            y1 = minOf(y0 + chunk_w - 1, maxY),
                        ),
                    )
                    cx++
                }
                cy++
            }
        }
        // No anchor: keep the plain sweep's (x-major, then y) order so the
        // chunked walk degenerates to forEachTileInBounds' visit order.
        val ordered = if (anchorLonLat == null) {
            chunks.sortedWith(compareBy({ it.x0 }, { it.y0 }))
        } else {
            val cos_lat = Math.cos(Math.toRadians(anchorLonLat.second))
            chunks.sortedBy { chunk ->
                val (clon, clat) = chunkCenter(chunk)
                val dlon = shortestLonDelta(anchorLonLat.first, clon) * cos_lat
                val dlat = anchorLonLat.second - clat
                dlon * dlon + dlat * dlat
            }
        }
        var probed = 0L
        for (chunk in ordered) {
            for (x in chunk.x0..chunk.x1) {
                for (y in chunk.y0..chunk.y1) {
                    probed++
                    if (onCellsProbed != null && probed % PROBE_PROGRESS_EVERY == 0L) {
                        onCellsProbed(probed, total)
                    }
                    val bytes = tile(zoom, x, y) ?: continue
                    visitor(zoom, x, y, bytes)
                }
            }
        }
    }

    /** Chunk-center WGS84 position, from the chunk's own tile coordinates. */
    private fun chunkCenter(chunk: Chunk): Pair<Double, Double> {
        val n = 1 shl chunk.zoom
        val center_x = (chunk.x0 + chunk.x1 + 1) / 2.0
        val center_y = (chunk.y0 + chunk.y1 + 1) / 2.0
        val lon = center_x / n * 360.0 - 180.0
        val lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * center_y / n))))
        return lon to lat
    }

    /** A rectangular sub-raster of the bounds' tile grid at one zoom. */
    private class Chunk(
        val zoom: Int,
        val x0: Int,
        val x1: Int,
        val y0: Int,
        val y1: Int,
    )

    private fun findTileEntry(z: Int, x: Int, y: Int): PmtilesEntry? {
        val tileId = HilbertTileId.tileId(z, x, y)
        var directory = rootDirectory
        var entry = PmtilesDirectory.findEntry(directory, tileId) ?: return null
        // Follow at most one level of leaf directories (the spec asks writers
        // not to nest deeper).
        if (entry.isLeafPointer) {
            directory = leafDirectory(entry)
            entry = PmtilesDirectory.findEntry(directory, tileId) ?: return null
        }
        return entry
    }

    private fun leafDirectory(pointer: PmtilesEntry): List<PmtilesEntry> =
        leafCache.getOrPut(pointer.offset) {
            val decoded = decodeDirectory(
                header.leafDirectoriesOffset + pointer.offset,
                pointer.length,
                "leaf directory",
            )
            if (leafCache.size >= LEAF_CACHE_LIMIT) {
                leafCache.remove(leafCache.keys.first())
            }
            decoded
        }

    private fun decodeDirectory(offset: Long, length: Int, what: String): List<PmtilesEntry> {
        val raw = readSection(offset, length)
        return PmtilesDirectory.decode(decompress(raw, header.internalCompression))
    }

    private fun readSection(offset: Long, length: Int): ByteArray {
        val bytes = ByteArray(length)
        file.seek(offset)
        file.readFully(bytes)
        return bytes
    }

    override fun close() = file.close()

    /** Longitudinal delta from [a] to [b] folded into (-180, 180], so
     *  distance comparisons work across the antimeridian. */
    private fun shortestLonDelta(a: Double, b: Double): Double {
        val delta = (b - a) % 360.0
        return when {
            delta > 180.0 -> delta - 360.0
            delta <= -180.0 -> delta + 360.0
            else -> delta
        }
    }

    companion object {
        /** Side length (in tiles) of the chunk raster used by
         * [forEachTileChunkInBounds] — 64² tiles is small enough that the
         * nearest chunk starts streaming addresses within seconds, large
         * enough that chunk sorting overhead is negligible. */
        const val CHUNK_TILES = 64

        private const val LEAF_CACHE_LIMIT = 64
        private const val PROBE_PROGRESS_EVERY = 4096L

        fun open(path: String): PmtilesReader = PmtilesReader(path)

        fun decompress(bytes: ByteArray, compression: Compression): ByteArray =
            when (compression) {
                Compression.NONE -> bytes
                Compression.GZIP ->
                    GZIPInputStream(ByteArrayInputStream(bytes)).readBytes()
                Compression.BROTLI, Compression.ZSTD ->
                    throw UnsupportedOperationException(
                        "Atlas only handles none/gzip-compressed PMTiles " +
                            "(found $compression); regenerate the archive with gzip.",
                    )
                Compression.UNKNOWN ->
                    throw IllegalArgumentException("unknown compression in archive")
            }

        fun lonToTileX(lon: Double, zoom: Int): Int =
            Math.floor((lon + 180.0) / 360.0 * (1 shl zoom)).toInt()

        fun latToTileY(lat: Double, zoom: Int): Int {
            val clamped = lat.coerceIn(MERCATOR_LAT_MIN, MERCATOR_LAT_MAX)
            val radians = Math.toRadians(clamped)
            val normalized =
                (1.0 - Math.log(Math.tan(radians) + 1.0 / Math.cos(radians)) / Math.PI) / 2.0
            return Math.floor(normalized * (1 shl zoom)).toInt()
        }

        private const val MERCATOR_LAT_MIN = -85.05112878
        private const val MERCATOR_LAT_MAX = 85.05112878
    }
}

data class TileRange(
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int,
)

/**
 * Convert an integer tile-local position (as decoded from an MVT, extent e.g.
 * 4096) to WGS84 lon/lat for tile (z, x, y).
 */
fun tilePointToLonLat(
    zoom: Int,
    x: Int,
    y: Int,
    localX: Int,
    localY: Int,
    extent: Int,
): Pair<Double, Double> {
    val n = 1 shl zoom
    val xn = (x + localX.toDouble() / extent) / n
    val yn = (y + localY.toDouble() / extent) / n
    val lon = xn * 360.0 - 180.0
    val lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * yn))))
    return lon to lat
}