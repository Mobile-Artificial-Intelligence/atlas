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

    companion object {
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