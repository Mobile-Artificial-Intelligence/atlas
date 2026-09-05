package com.danemadsen.atlas.pmtiles

import java.io.File
import java.io.FileInputStream

/** The 127-byte PMTiles v3 header, parsed eagerly when an archive is opened. */
class PmtilesHeader private constructor(
    buffer: ByteArray,
) {
    val rootDirectoryOffset = buffer.readUint64(8)
    val rootDirectoryLength = buffer.readUint64(16)
    val metadataOffset = buffer.readUint64(24)
    val metadataLength = buffer.readUint64(32)
    val leafDirectoriesOffset = buffer.readUint64(40)
    val leafDirectoriesLength = buffer.readUint64(48)
    val tileDataOffset = buffer.readUint64(56)
    val tileDataLength = buffer.readUint64(64)
    val numberOfAddressedTiles = buffer.readUint64(72)
    val numberOfTileEntries = buffer.readUint64(80)
    val numberOfTileContents = buffer.readUint64(88)
    val clustered = buffer[96].toInt() == 1
    val internalCompression = Compression.fromCode(buffer[97].toInt())
    val tileCompression = Compression.fromCode(buffer[98].toInt())
    val tileType = TileType.fromCode(buffer[99].toInt())
    val minZoom = buffer[100].toInt() and 0xFF
    val maxZoom = buffer[101].toInt() and 0xFF
    val minLon = buffer.readInt32(102) / COORDINATE_SCALE
    val minLat = buffer.readInt32(106) / COORDINATE_SCALE
    val maxLon = buffer.readInt32(110) / COORDINATE_SCALE
    val maxLat = buffer.readInt32(114) / COORDINATE_SCALE
    val centerZoom = buffer[118].toInt() and 0xFF
    val centerLon = buffer.readInt32(119) / COORDINATE_SCALE
    val centerLat = buffer.readInt32(123) / COORDINATE_SCALE

    fun bounds(): TileBounds = TileBounds(minLon, minLat, maxLon, maxLat)

    override fun toString(): String =
        "PmtilesHeader(zooms=$minZoom..$maxZoom, bounds=$minLon,$minLat,$maxLon,$maxLat, " +
            "tileType=$tileType, tileCompression=$tileCompression, entries=$numberOfTileEntries)"

    companion object {
        private const val MAGIC = "PMTiles"
        const val HEADER_SIZE = 127
        private const val COORDINATE_SCALE = 10_000_000.0

        fun parse(buffer: ByteArray): PmtilesHeader {
            require(buffer.size >= HEADER_SIZE) {
                "PMTiles header must be $HEADER_SIZE bytes, got ${buffer.size}"
            }
            val magic = String(buffer, 0, 7, Charsets.US_ASCII)
            require(magic == MAGIC) { "not a PMTiles archive: magic='$magic'" }
            val version = buffer[7].toInt()
            require(version == 3) { "unsupported PMTiles version $version (only 3)" }
            return PmtilesHeader(buffer)
        }

        private fun ByteArray.readUint64(offset: Int): Long {
            var value = 0L
            for (i in 7 downTo 0) {
                value = (value shl 8) or (this[offset + i].toLong() and 0xFF)
            }
            return value
        }

        private fun ByteArray.readInt32(offset: Int): Int =
            (this[offset].toInt() and 0xFF) or
                ((this[offset + 1].toInt() and 0xFF) shl 8) or
                ((this[offset + 2].toInt() and 0xFF) shl 16) or
                ((this[offset + 3].toInt() and 0xFF) shl 24)
    }
}

/** WGS84 bounding box, lon/lat order like the PMTiles header and the
 *  MapLibre `fitBounds` convention. */
data class TileBounds(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
)

/**
 * The raw [PmtilesHeader.HEADER_SIZE] header bytes of the archive at
 * [file] — the content identity a fingerprint hashes (an archive's
 * metadata carries the SAF display name it was picked under, which a
 * browser's " (1)" suffix would break, but these bytes only change when
 * the archive itself does).
 */
fun archiveHeaderBytes(file: File): ByteArray {
    require(file.length() >= PmtilesHeader.HEADER_SIZE) {
        "not a PMTiles archive: $file (only ${file.length()} bytes)"
    }
    FileInputStream(file).use { input ->
        val header = ByteArray(PmtilesHeader.HEADER_SIZE)
        // readFully semantics: a single read may short-read, and a short
        // header would silently hash a shifted window.
        var read = 0
        while (read < PmtilesHeader.HEADER_SIZE) {
            val n = input.read(header, read, PmtilesHeader.HEADER_SIZE - read)
            if (n < 0) error("archive shrank mid-read: $file")
            read += n
        }
        return header
    }
}

enum class Compression(val code: Int) {
    UNKNOWN(0),
    NONE(1),
    GZIP(2),
    BROTLI(3),
    ZSTD(4),
    ;

    companion object {
        fun fromCode(code: Int): Compression =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("unknown compression code $code")
    }
}

enum class TileType(val code: Int) {
    UNKNOWN(0),
    MVT(1),
    PNG(2),
    JPEG(3),
    WEBP(4),
    AVIF(5),
    MLVT(6),
    ;

    companion object {
        fun fromCode(code: Int): TileType =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("unknown tile type code $code")
    }
}