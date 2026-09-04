package com.danemadsen.atlas.search

import android.content.Context
import androidx.room.Room
import com.danemadsen.atlas.pmtiles.PmtilesReader
import com.danemadsen.atlas.pmtiles.TileBounds
import com.danemadsen.atlas.pmtiles.mvt.MvtGeomType
import com.danemadsen.atlas.pmtiles.mvt.MvtTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Builds the search index database for one archive.
 *
 * One DB per archive fingerprint ([databaseFile]): a new import never reads
 * a stale index, and replacing the archive wipes the old DB with the
 * archive-scoped directory.
 *
 * The import-time pass indexes the `place` layer at zooms
 * [MIN_INDEX_ZOOM]..[MAX_CHEAP_ZOOM] — countries down to neighbourhoods —
 * which is tens of seconds over a continental archive and makes search
 * useful immediately. [candidatesFromTile] is also called by the graph
 * build's scan hook (deep pass: `poi` + high-zoom `place`), which already
 * holds the decompressed tiles in memory and must not re-read the archive.
 */
class SearchIndexer(
    private val context: Context,
    private val databaseFile: File,
) {

    /** Rows a pass offered vs. rows it genuinely added. */
    data class PassResult(val placesSeen: Int, val placesInserted: Int)

    /** A fresh DB handle; the caller owns closing it. */
    fun open(): PlaceDatabase {
        databaseFile.parentFile?.mkdirs()
        return Room.databaseBuilder(
            context.applicationContext,
            PlaceDatabase::class.java,
            databaseFile.absolutePath,
        ).build()
    }

    /**
     * The import-time pass: every `place` and `poi` feature in zooms
     * [MIN_INDEX_ZOOM]..min([MAX_CHEAP_ZOOM], archive maxZoom).
     */
    suspend fun indexCheapPass(
        reader: PmtilesReader,
        bounds: TileBounds,
    ): PassResult = withContext(Dispatchers.IO) {
        val db = open()
        try {
            val existing = existingZooms(db)
            val offered = HashMap<String, Int>(existing.size)
            var seen = 0
            var inserted = 0
            val batch = ArrayList<PlaceEntity>(BATCH_ROWS)
            val max_zoom = minOf(reader.header.maxZoom, MAX_CHEAP_ZOOM)
            for (zoom in MIN_INDEX_ZOOM..max_zoom) {
                reader.forEachTileInBounds(zoom, bounds) { _, x, y, bytes ->
                    for (candidate in candidatesFromTile(zoom, x, y, bytes)) {
                        seen++
                        if (beatsExisting(candidate, existing, offered)) {
                            offered[candidate.dedupeKey] = candidate.zoom
                            batch.add(candidate)
                        }
                    }
                }
                // Cancellation checks bracket each zoom level: the reader's
                // visitor cannot suspend, and one zoom is the bounded window
                // a cancelled import waits out.
                currentCoroutineContext().ensureActive()
                inserted += flush(db, batch)
            }
            db.placeDao().rebuildFts()
            PassResult(seen, inserted)
        } finally {
            db.close()
        }
    }

    /**
     * The deep pass over already-read tiles (the graph build's scan hook):
     * `place` + `poi` features in the tiles' own zooms.
     */
    suspend fun indexDeepPass(
        db: PlaceDatabase,
        tiles: Sequence<DeepPassTile>,
    ): PassResult = withContext(Dispatchers.IO) {
        val existing = existingZooms(db)
        val offered = HashMap<String, Int>(existing.size)
        var seen = 0
        var inserted = 0
        val batch = ArrayList<PlaceEntity>(BATCH_ROWS)
        for (tile in tiles) {
            for (candidate in candidatesFromTile(tile.zoom, tile.x, tile.y, tile.bytes)) {
                seen++
                if (beatsExisting(candidate, existing, offered)) {
                    offered[candidate.dedupeKey] = candidate.zoom
                    batch.add(candidate)
                }
            }
            currentCoroutineContext().ensureActive()
            if (batch.size >= BATCH_ROWS) inserted += flush(db, batch)
        }
        inserted += flush(db, batch)
        db.placeDao().rebuildFts()
        PassResult(seen, inserted)
    }

    /** One already-decompressed tile handed to [indexDeepPass]. */
    class DeepPassTile(val zoom: Int, val x: Int, val y: Int, val bytes: ByteArray)

    /**
     * True when [candidate] is the best zoom seen for its key so far —
     * lower zoom wins (the low-zoom representative point is the place's
     * conceptual center, and one row per real place is all search needs).
     */
    private fun beatsExisting(
        candidate: PlaceEntity,
        existing: Map<String, Int>,
        offered: Map<String, Int>,
    ): Boolean {
        val best_zoom = minOf(
            offered[candidate.dedupeKey] ?: Int.MAX_VALUE,
            existing[candidate.dedupeKey] ?: Int.MAX_VALUE,
        )
        return candidate.zoom < best_zoom
    }

    /**
     * Extracts indexable places/pois from one decompressed MVT tile. Point
     * features only: area places are point-represented by OpenMapTiles, and
     * the picker wants a flyable coordinate, not an outline.
     */
    fun candidatesFromTile(zoom: Int, x: Int, y: Int, bytes: ByteArray): List<PlaceEntity> {
        val tile = MvtTile.decode(bytes)
        val result = ArrayList<PlaceEntity>(16)
        for (layer_name in listOf(LAYER_PLACE, LAYER_POI)) {
            val layer = tile.layer(layer_name) ?: continue
            for (feature in layer.features) {
                if (feature.geomType != MvtGeomType.POINT) continue
                val props = layer.properties(feature)
                val name = (props[PROP_NAME] as? String) ?: (props[PROP_NAME_EN] as? String)
                if (name.isNullOrBlank()) continue
                val kind = props[PROP_CLASS] as? String ?: continue
                val point = layer.pathsLocal(feature).firstOrNull()?.firstOrNull() ?: continue
                val (lon, lat) = com.danemadsen.atlas.pmtiles.tilePointToLonLat(
                    zoom, x, y, point.x, point.y, layer.extent,
                )
                val trimmed_name = name.trim()
                result.add(
                    PlaceEntity(
                        name = trimmed_name,
                        kind = kind,
                        subclass = props[PROP_SUBCLASS] as? String,
                        rank = (props[PROP_RANK] as? Long)?.toInt() ?: MAX_RANK,
                        lon = lon,
                        lat = lat,
                        zoom = zoom,
                        dedupeKey = dedupeKey(trimmed_name, kind),
                    ),
                )
            }
        }
        return result
    }

    /** The unique dedupeKey -> zoom map already in the DB. */
    private suspend fun existingZooms(db: PlaceDatabase): HashMap<String, Int> {
        val existing = HashMap<String, Int>()
        db.placeDao().existingZooms().forEach { row ->
            existing[dedupeKey(row.name, row.kind)] = row.zoom
        }
        return existing
    }

    private suspend fun flush(db: PlaceDatabase, batch: MutableList<PlaceEntity>): Int {
        if (batch.isEmpty()) return 0
        val rows = batch.toList()
        batch.clear()
        db.placeDao().insertBatch(rows)
        return rows.size
    }

    companion object {
        /** The rank stored when a feature carries no rank. */
        const val MAX_RANK = 20
        const val MIN_INDEX_ZOOM = 0
        const val MAX_CHEAP_ZOOM = 9

        const val LAYER_PLACE = "place"
        const val LAYER_POI = "poi"
        const val PROP_NAME = "name"
        const val PROP_NAME_EN = "name:en"
        const val PROP_CLASS = "class"
        const val PROP_SUBCLASS = "subclass"
        const val PROP_RANK = "rank"

        const val BATCH_ROWS = 500

        /** `"<name>|<kind>"` — the unique key rows dedupe on. */
        fun dedupeKey(name: String, kind: String): String = "$name|$kind"

        /**
         * The archive fingerprint keying the index DB's file name: stable
         * across launches of the same archive, different for any different
         * archive (name, size or bbox change).
         */
        fun archiveFingerprint(
            fileName: String,
            sizeBytes: Long,
            west: Double,
            south: Double,
            east: Double,
            north: Double,
            minZoom: Int,
            maxZoom: Int,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val canonical = listOf(
                fileName, sizeBytes.toString(),
                west.toString(), south.toString(), east.toString(), north.toString(),
                minZoom.toString(), maxZoom.toString(),
            ).joinToString(" ")
            return digest.digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        fun databaseFile(searchDir: File, fingerprint: String): File =
            File(searchDir, "search-$fingerprint.db")

        /** Deletes every index DB under [searchDir] (a replaced archive). */
        fun deleteAll(searchDir: File) {
            searchDir.listFiles()?.forEach { it.delete() }
            searchDir.delete()
        }
    }
}