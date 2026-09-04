package com.danemadsen.atlas.search

import android.content.Context
import androidx.room.Room
import com.danemadsen.atlas.pmtiles.PmtilesReader
import com.danemadsen.atlas.pmtiles.TileBounds
import com.danemadsen.atlas.pmtiles.mvt.MvtGeomType
import com.danemadsen.atlas.pmtiles.mvt.MvtTile
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
 * The import-time pass runs in two stages: first the `place` layer at zooms
 * [MIN_INDEX_ZOOM]..[MAX_CHEAP_ZOOM] — countries down to neighbourhoods —
 * which is tens of seconds over a continental archive and makes search
 * useful immediately; then the `address` layer (OpenAddresses points merged
 * into the archive by the tile CI) at [ADDRESS_INDEX_ZOOM] — a full
 * continental z14 sweep over millions of rows, committing in batches so a
 * cancelled import loses minutes, not the whole pass. [candidatesFromTile]
 * is also called by the graph build's scan hook (deep pass: `poi` + z14
 * `place` + `address`), which already holds the decompressed tiles in
 * memory and must not re-read the archive.
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
     * The import-time pass: stage 1 indexes every `place`/`poi` feature in
     * zooms [MIN_INDEX_ZOOM]..min([MAX_CHEAP_ZOOM], archive maxZoom); stage
     * 2 sweeps the `address` layer at [ADDRESS_INDEX_ZOOM].
     */
    suspend fun indexCheapPass(
        reader: PmtilesReader,
        bounds: TileBounds,
    ): PassResult = withContext(Dispatchers.IO) {
        val db = open()
        try {
            // Address rows bypass this map entirely (14M entries would OOM;
            // their dedupe is the unique dedupeKey index + insert-or-ignore),
            // so it only ever holds place/poi keys.
            val existing = existingZooms(db, exceptKind = KIND_ADDRESS)
            val offered = HashMap<String, Int>(existing.size)
            var seen = 0
            var inserted = 0
            val batch = ArrayList<PlaceEntity>(BATCH_ROWS)
            val max_zoom = minOf(reader.header.maxZoom, MAX_CHEAP_ZOOM)
            for (zoom in MIN_INDEX_ZOOM..max_zoom) {
                reader.forEachTileInBounds(zoom, bounds) { _, x, y, bytes ->
                    for (candidate in placeCandidates(MvtTile.decode(bytes), zoom, x, y)) {
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
            // Stage 1 commits (and its FTS rows) BEFORE the address sweep:
            // Room creates the DB file at open(), so search is already
            // serving against these rows while millions of addresses index.
            db.placeDao().rebuildFts()

            // Stage 2: the address sweep at one zoom — the tile visitor
            // cannot suspend, so batching and cancellation both happen
            // off-visitor: candidates stream through an unbounded channel
            // (trySend never blocks or fails into it) drained by a flusher,
            // and the Job handle is checked non-suspendingly every
            // [CANCEL_CHECK_TILES] tiles.
            val job = currentCoroutineContext()[Job]
            coroutineScope {
                val channel = Channel<PlaceEntity>(Channel.UNLIMITED)
                val drain = launch(start = CoroutineStart.LAZY) {
                    val address_batch = ArrayList<PlaceEntity>(ADDRESS_BATCH_ROWS)
                    for (entity in channel) {
                        address_batch.add(entity)
                        if (address_batch.size >= ADDRESS_BATCH_ROWS) {
                            inserted += flush(db, address_batch)
                        }
                    }
                    inserted += flush(db, address_batch)
                }
                drain.start()
                var since_check = 0
                reader.forEachTileInBounds(ADDRESS_INDEX_ZOOM, bounds) { zoom, x, y, bytes ->
                    for (candidate in addressCandidates(MvtTile.decode(bytes), zoom, x, y)) {
                        seen++
                        channel.trySend(candidate)
                    }
                    if (++since_check >= CANCEL_CHECK_TILES) {
                        since_check = 0
                        job?.ensureActive()
                    }
                }
                channel.close()
                drain.join()
            }
            db.placeDao().rebuildFts()
            PassResult(seen, inserted)
        } finally {
            db.close()
        }
    }

    /**
     * The deep pass over already-read tiles (the graph build's scan hook):
     * `place` + `poi` features in the tiles' own zooms, plus `address`
     * features at [ADDRESS_INDEX_ZOOM]-and-above tiles.
     */
    suspend fun indexDeepPass(
        db: PlaceDatabase,
        tiles: Sequence<DeepPassTile>,
    ): PassResult = withContext(Dispatchers.IO) {
        val existing = existingZooms(db, exceptKind = KIND_ADDRESS)
        val offered = HashMap<String, Int>(existing.size)
        var seen = 0
        var inserted = 0
        val batch = ArrayList<PlaceEntity>(BATCH_ROWS)
        for (tile in tiles) {
            for (candidate in candidatesFromTile(tile.zoom, tile.x, tile.y, tile.bytes)) {
                seen++
                // Addresses bypass the in-memory dedupe (see [indexCheapPass]);
                // their unique dedupeKey index + insert-or-ignore absorbs the
                // overlap with the cheap pass and re-scanned buckets.
                if (candidate.kind == KIND_ADDRESS || beatsExisting(candidate, existing, offered)) {
                    if (candidate.kind != KIND_ADDRESS) {
                        offered[candidate.dedupeKey] = candidate.zoom
                    }
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

    /** The unique dedupeKey -> zoom map already in the DB, minus [exceptKind]. */
    private suspend fun existingZooms(
        db: PlaceDatabase,
        exceptKind: String,
    ): HashMap<String, Int> {
        val existing = HashMap<String, Int>()
        db.placeDao().existingZoomsExcept(exceptKind).forEach { row ->
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
        const val LAYER_ADDRESS = "address"
        const val PROP_NAME = "name"
        const val PROP_NAME_EN = "name:en"
        const val PROP_CLASS = "class"
        const val PROP_SUBCLASS = "subclass"
        const val PROP_RANK = "rank"
        const val PROP_NUMBER = "number"
        const val PROP_STREET = "street"
        const val PROP_UNIT = "unit"
        const val PROP_CITY = "city"

        /** The `kind` stored for OpenAddresses rows; no OMT class collides with it. */
        const val KIND_ADDRESS = "address"

        /** The only zoom the `address` layer exists at (CI merge clips to it). */
        const val ADDRESS_INDEX_ZOOM = 14

        /**
         * Rank for address rows: above OMT's unranked places/POIs (MAX_RANK
         * = 20) but below every ranked place (OMT places rank 1..11), so a
         * broad query like "melbourne" still surfaces the city ahead of
         * thousands of "69 Melbourne St" rows.
         */
        const val ADDRESS_RANK = 12

        /** Address dedupe-key location quantization (decimals; 3 ≈ 100 m). */
        const val ADDRESS_QUANTIZE_DECIMALS = 3

        const val BATCH_ROWS = 500
        const val ADDRESS_BATCH_ROWS = 2000

        /**
         * How often the address sweep checks its Job — the tile visitor
         * cannot suspend, so a cancelled import waits out at most this many
         * tiles between checks.
         */
        const val CANCEL_CHECK_TILES = 256

        /**
         * Bumped whenever the extraction rules change in a way an existing
         * index cannot absorb — folded into [archiveFingerprint] so every
         * archive re-indexes exactly once per format change.
         */
        const val INDEX_FORMAT = 2

        /** `"<name>|<kind>"` — the unique key place rows dedupe on. */
        fun dedupeKey(name: String, kind: String): String = "$name|$kind"

        /**
         * Every indexable place, POI and (at [ADDRESS_INDEX_ZOOM] and up)
         * address from one decompressed MVT tile. Point features only: area
         * places are point-represented by OpenMapTiles, and the picker
         * wants a flyable coordinate, not an outline.
         */
        fun candidatesFromTile(zoom: Int, x: Int, y: Int, bytes: ByteArray) : List<PlaceEntity> {
            val tile = MvtTile.decode(bytes)
            val result = ArrayList(placeCandidates(tile, zoom, x, y))
            if (zoom >= ADDRESS_INDEX_ZOOM) {
                result.addAll(addressCandidates(tile, zoom, x, y))
            }
            return result
        }

        /** Named points of the `place` and `poi` layers — today's place rows. */
        private fun placeCandidates(tile: MvtTile, zoom: Int, x: Int, y: Int): List<PlaceEntity> {
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

        /**
         * OpenAddresses rows from the `address` layer. Only features with
         * both a number and a street are indexed — a lone "69" would flood
         * FTS with unsearchable tokens. Rows keep the city (title-cased) as
         * their subclass so the results drawer shows a useful subtitle.
         */
        internal fun addressCandidates(tile: MvtTile, zoom: Int, x: Int, y: Int): List<PlaceEntity> {
            val layer = tile.layer(LAYER_ADDRESS) ?: return emptyList()
            val result = ArrayList<PlaceEntity>(8)
            for (feature in layer.features) {
                if (feature.geomType != MvtGeomType.POINT) continue
                val props = layer.properties(feature)
                val number = addressText(props, PROP_NUMBER)
                val street = addressText(props, PROP_STREET)
                if (number.isEmpty() || street.isEmpty()) continue
                val point = layer.pathsLocal(feature).firstOrNull()?.firstOrNull() ?: continue
                val (lon, lat) = com.danemadsen.atlas.pmtiles.tilePointToLonLat(
                    zoom, x, y, point.x, point.y, layer.extent,
                )
                val unit = addressText(props, PROP_UNIT)
                val city = addressText(props, PROP_CITY)
                result.add(
                    PlaceEntity(
                        name = addressName(number, unit, street),
                        kind = KIND_ADDRESS,
                        subclass = city.takeIf { it.isNotEmpty() }?.let { titleCaseAddressText(it) },
                        rank = ADDRESS_RANK,
                        lon = lon,
                        lat = lat,
                        zoom = zoom,
                        dedupeKey = addressDedupeKey(number, unit, street, lat, lon),
                    ),
                )
            }
            return result
        }

        /**
         * A tile property as display text, tolerating the value encodings a
         * merge pipeline can hand through: strings as-is, whole numbers
         * without a decimal tail (a house number of 69 must not read "69.0").
         */
        private fun addressText(props: Map<String, Any?>, key: String): String = when (val v = props[key]) {
            null -> ""
            is String -> v.trim()
            is Number -> if (v.toDouble() % 1.0 == 0.0) v.toLong().toString() else v.toString()
            else -> v.toString().trim()
        }

        /**
         * The archive fingerprint keying the index DB's file name: stable
         * across launches of the same archive, different for any different
         * archive (name, size or bbox change) or index format ([INDEX_FORMAT]).
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
                INDEX_FORMAT.toString(),
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