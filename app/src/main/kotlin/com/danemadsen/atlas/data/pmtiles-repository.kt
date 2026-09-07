package com.danemadsen.atlas.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.danemadsen.atlas.pmtiles.Compression
import com.danemadsen.atlas.pmtiles.PmtilesReader
import com.danemadsen.atlas.pmtiles.TileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Imports PMTiles map archives as regions ([RegionStore]). The SAF copy
 * goes to a staging file next to the region layout so a failed import
 * never corrupts a working archive; only a validated file is moved into
 * place and recorded.
 */
class PmtilesRepository(
    private val context: Context,
) {

    private val map_dir get() = RegionStore.mapDir(context)

    /** All installed regions, or empty when none (→ the NeedsArchive state). */
    fun loadRegions(): List<RegionInfo> = RegionStore.loadAll(map_dir)

    /**
     * The SAF document's display name, for import progress detail and the
     * pairing toasts — null when the provider does not answer.
     */
    fun displayName(uri: Uri): String? = queryDisplayName(uri)

    /**
     * Copies the SAF document at [uri] into the app and validates it. The
     * region it lands in is resolved from the file itself: matching either
     * content fingerprint means it is a newer build of an installed region
     * (replace in place — siblings untouched); otherwise the id derives
     * from the display name ("atlas-us-georgia.pmtiles" → "us-georgia").
     * [onProgress] receives a 0..1 fraction, or null when the provider does
     * not report a size. Throws with a user-presentable message on failure.
     */
    suspend fun importRegion(
        uri: Uri,
        onProgress: (Float?) -> Unit,
    ): RegionInfo = withContext(Dispatchers.IO) {
        val display_name = queryDisplayName(uri) ?: "map.pmtiles"
        val total_size = querySize(uri)
        map_dir.mkdirs()
        val staging_file = File(map_dir, "incoming.pmtiles.staging")

        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("could not open the selected file")
            input.use { stream ->
                staging_file.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var copied = 0L
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(
                            if (total_size > 0) (copied.toDouble() / total_size).toFloat() else null,
                        )
                    }
                }
            }

            val header = validate(staging_file)
            val routing_fp = RegionStore.routingFingerprintOf(staging_file)
            val search_fp = RegionStore.searchFingerprintOf(staging_file)
            val installed = RegionStore.loadAll(map_dir)
            // A fingerprint match outranks the display name: a browser's
            // " (1)" suffix on a re-downloaded archive must still update
            // the region it belongs to, not mint a sibling.
            val region_id = installed
                .firstOrNull { it.routingFingerprint == routing_fp || it.searchFingerprint == search_fp }
                ?.id
                ?: RegionStore.deriveRegionId(display_name, installed.map { it.id }.toSet())

            val target = RegionStore.archiveFile(map_dir, region_id)
                .apply { parentFile.mkdirs() }
            if (target.exists()) target.delete()
            if (!staging_file.renameTo(target)) {
                throw IOException("could not store the archive in app storage")
            }

            val info = RegionInfo(
                id = region_id,
                displayName = display_name,
                sizeBytes = target.length(),
                minZoom = header.minZoom,
                maxZoom = header.maxZoom,
                west = header.minLon,
                south = header.minLat,
                east = header.maxLon,
                north = header.maxLat,
                centerLon = header.centerLon,
                centerLat = header.centerLat,
                centerZoom = header.centerZoom,
                routingFingerprint = routing_fp,
                searchFingerprint = search_fp,
                importedAtMs = System.currentTimeMillis(),
            )
            RegionStore.add(map_dir, info)
            info
        } catch (e: Exception) {
            staging_file.delete()
            throw e
        }
    }

    /** Parses the header eagerly — anything malformed throws here. */
    private fun validate(file: File): com.danemadsen.atlas.pmtiles.PmtilesHeader {
        RandomAccessFile(file, "r").use { raf ->
            val reader = PmtilesReader(raf)
            val header = reader.header
            if (header.tileType != TileType.MVT) {
                throw IllegalArgumentException(
                    "Atlas needs a vector PMTiles archive (Mapbox Vector Tiles), " +
                        "but this one contains ${header.tileType} tiles.",
                )
            }
            for ((compression, what) in listOf(
                header.internalCompression to "directory",
                header.tileCompression to "tile",
            )) {
                if (compression !in listOf(Compression.NONE, Compression.GZIP)) {
                    throw IllegalArgumentException(
                        "This archive uses ${compression.name.lowercase()}-compressed " +
                            "$what data, which Atlas cannot read. " +
                            "Regenerate it with gzip compression (the Planetiler default).",
                    )
                }
            }
            return header
        }
    }

    private fun queryDisplayName(uri: Uri): String? =
        query(uri, OpenableColumns.DISPLAY_NAME)

    private fun querySize(uri: Uri): Long =
        query(uri, OpenableColumns.SIZE)?.toLongOrNull() ?: 0L

    private fun query(uri: Uri, column: String): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()

    private companion object {
        const val COPY_BUFFER_BYTES = 1 shl 20
    }
}