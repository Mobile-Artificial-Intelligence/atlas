package com.danemadsen.atlas.search

import com.danemadsen.atlas.pmtiles.PmtilesReader
import com.danemadsen.atlas.pmtiles.archiveHeaderBytes
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
 * archive-scoped directory. A [completion marker][completionFile] is written
 * only after a pass runs to the end — a cancelled or killed pass leaves a
 * partial DB with no marker, which the resume check treats as "not indexed
 * yet" (the pass re-runs; inserts are idempotent on the unique keys).
 *
 * The DB handle comes from [openDatabase], injected by the caller: the app
 * passes its Android Room builder, CI's JVM CLI passes a
 * BundledSQLiteDriver-backed builder — both mint/open the SAME schema (Room's
 * identity hash is target-independent), so a CI-minted index adopts on device
 * as if the device had built it itself.
 *
 * The import-time pass runs in two stages: first the `place` layer at zooms
 * [MIN_INDEX_ZOOM]..[MAX_CHEAP_ZOOM] — countries down to neighbourhoods —
 * which is tens of seconds over a continental archive and makes search
 * useful immediately; then the `address` layer (OpenAddresses points merged
 * into the archive by the tile CI) at [ADDRESS_INDEX_ZOOM] — a full
 * continental z14 sweep over millions of rows, committing in batches so a
 * cancelled import loses minutes, not the whole pass. Address rows go to
 * their own table ([AddressEntity]), never the place table.
 *
 * [candidatesFromTile] is also called by the graph build's scan hook (deep
 * pass: `place` + `poi` at the tile's own zoom plus `address` rows), which
 * already holds the decompressed tiles in memory and must not re-read the
 * archive.
 */
class SearchIndexer(
    private val databaseFile: File,
    private val openDatabase: (File) -> PlaceDatabase,
) {

    /** Rows a pass offered vs. rows it genuinely added, per table. */
    data class PassResult(
        val placesSeen: Int,
        val placesInserted: Int,
        val addressesSeen: Int = 0,
        val addressesInserted: Int = 0,
    )

    /** A fresh DB handle; the caller owns closing it. */
    fun open(): PlaceDatabase {
        databaseFile.parentFile?.mkdirs()
        return openDatabase(databaseFile)
    }

    /**
     * The import-time pass: stage 1 indexes every `place`/`poi` feature in
     * zooms [MIN_INDEX_ZOOM]..min([MAX_CHEAP_ZOOM], archive maxZoom); stage
     * 2 sweeps the `address` layer at [ADDRESS_INDEX_ZOOM] when the archive
     * carries one (GB/US builds merge no addresses; their sweep would spend
     * minutes walking z14 tiles to find no `address` layer). The sweep
     * bounds come from the reader's own header bbox — the same box the
     * graph build walks — so the pass needs nothing but the archive.
     */
    suspend fun indexCheapPass(reader: PmtilesReader): PassResult = withContext(Dispatchers.IO) {
        val bounds = reader.header.bounds()
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
                inserted += flushPlaces(db, batch)
            }
            // Stage 1 commits (and its FTS rows) BEFORE the address sweep:
            // Room creates the DB file at open(), so search is already
            // serving against these rows while millions of addresses index.
            db.placeDao().rebuildFts()

            var addresses_seen = 0
            var addresses_inserted = 0
            if (archiveHasAddressLayer(reader)) {
                // Stage 2: the address sweep at one zoom — the tile visitor
                // cannot suspend, so batching and cancellation both happen
                // off-visitor: candidates stream through an unbounded channel
                // (trySend never blocks or fails into it) drained by a
                // flusher, and the Job handle is checked non-suspendingly
                // every [CANCEL_CHECK_TILES] tiles.
                val job = currentCoroutineContext()[Job]
                coroutineScope {
                    val channel = Channel<AddressEntity>(Channel.UNLIMITED)
                    val drain = launch(start = CoroutineStart.LAZY) {
                        val address_batch = ArrayList<AddressEntity>(ADDRESS_BATCH_ROWS)
                        for (entity in channel) {
                            address_batch.add(entity)
                            if (address_batch.size >= ADDRESS_BATCH_ROWS) {
                                addresses_inserted += flushAddresses(db, address_batch)
                            }
                        }
                        addresses_inserted += flushAddresses(db, address_batch)
                    }
                    drain.start()
                    var since_check = 0
                    reader.forEachTileInBounds(ADDRESS_INDEX_ZOOM, bounds) { zoom, x, y, bytes ->
                        for (candidate in addressCandidates(MvtTile.decode(bytes), zoom, x, y)) {
                            addresses_seen++
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
                // No address_fts rebuild: Room's content-entity triggers
                // already kept the FTS shadow in sync per insert, and a
                // rebuild would re-index all millions of rows from scratch —
                // a full extra pass that buys nothing.
            }
            // Only a pass that ran to the end marks the index complete — a
            // cancellation unwinds before this line, so the partial DB stays
            // unmarked and the next launch resumes it.
            completionMarkerFile.writeText("")
            PassResult(seen, inserted, addresses_seen, addresses_inserted)
        } finally {
            db.close()
        }
    }

    /**
     * True when the archive's metadata advertises the merged `address`
     * layer. The needle is the compact vector_layers id tile-join writes
     * (`"id":"address"`); tilestats carries sample street/number values but
     * never that quoted form.
     */
    private fun archiveHasAddressLayer(reader: PmtilesReader): Boolean =
        reader.header.maxZoom >= ADDRESS_INDEX_ZOOM &&
            reader.metadata().contains(ADDRESS_LAYER_NEEDLE)

    /**
     * The completion marker beside the DB — written only by a pass that
     * finished, so [indexExists] semantics stay "fully indexed or absent".
     */
    private val completionMarkerFile: File
        get() = File(databaseFile.parentFile, databaseFile.name.removeSuffix(".db") + ".done")

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

    /** The unique dedupeKey -> zoom map already in the place table. */
    private suspend fun existingZooms(db: PlaceDatabase): HashMap<String, Int> {
        val existing = HashMap<String, Int>()
        db.placeDao().existingZooms().forEach { row ->
            existing[dedupeKey(row.name, row.kind)] = row.zoom
        }
        return existing
    }

    private suspend fun flushPlaces(db: PlaceDatabase, batch: MutableList<PlaceEntity>): Int {
        if (batch.isEmpty()) return 0
        val rows = batch.toList()
        batch.clear()
        db.placeDao().insertBatch(rows)
        return rows.size
    }

    private suspend fun flushAddresses(db: PlaceDatabase, batch: MutableList<AddressEntity>): Int {
        if (batch.isEmpty()) return 0
        val rows = batch.toList()
        batch.clear()
        db.addressDao().insertAll(rows)
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
         * Rank for address hits: above OMT's unranked places/POIs (MAX_RANK
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
         * The metadata needle proving an archive carries the merged
         * `address` layer — see [archiveHasAddressLayer].
         */
        const val ADDRESS_LAYER_NEEDLE = "\"id\":\"address\""

        /**
         * Bumped whenever the extraction or schema rules change in a way an
         * existing index cannot absorb — folded into [contentFingerprint]
         * so every archive re-indexes exactly once per format change. 3:
         * addresses moved to their own table + FTS (split from the place
         * schema). The fingerprint SCHEME itself (content hash vs. the old
         * metadata hash) also re-keys every existing index exactly once,
         * without a bump.
         */
        const val INDEX_FORMAT = 3

        /** `"<name>|<kind>"` — the unique key place rows dedupe on. */
        fun dedupeKey(name: String, kind: String): String = "$name|$kind"

        /**
         * Every indexable place, POI and (at [ADDRESS_INDEX_ZOOM] and up)
         * address from one decompressed MVT tile — decoded once, split by
         * table. Point features only: area places are point-represented by
         * OpenMapTiles, and the picker wants a flyable coordinate, not an
         * outline.
         */
        fun candidatesFromTile(zoom: Int, x: Int, y: Int, bytes: ByteArray): TileCandidates {
            val tile = MvtTile.decode(bytes)
            val places = placeCandidates(tile, zoom, x, y)
            val addresses = if (zoom >= ADDRESS_INDEX_ZOOM) {
                addressCandidates(tile, zoom, x, y)
            } else {
                emptyList()
            }
            return TileCandidates(places, addresses)
        }

        /** The per-tile extraction result, split by destination table. */
        class TileCandidates(
            val places: List<PlaceEntity>,
            val addresses: List<AddressEntity>,
        )

        /** Named points of the `place` and `poi` layers — the place table's rows. */
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
         * FTS with unsearchable tokens. Rows keep the city (title-cased) for
         * the results drawer's subtitle.
         */
        internal fun addressCandidates(tile: MvtTile, zoom: Int, x: Int, y: Int): List<AddressEntity> {
            val layer = tile.layer(LAYER_ADDRESS) ?: return emptyList()
            val result = ArrayList<AddressEntity>(8)
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
                    AddressEntity(
                        name = addressName(number, unit, street),
                        city = city.takeIf { it.isNotEmpty() }?.let { titleCaseAddressText(it) },
                        lon = lon,
                        lat = lat,
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
         * The archive fingerprint keying the index DB's file name: SHA-256
         * over the archive's raw 127-byte PMTiles header, its file length
         * and [INDEX_FORMAT]. Content, not metadata — a fingerprint keyed on
         * the archive's display name would break the CI pairing (a browser
         * appending " (1)" to a download would mint an unadoptable index),
         * while these bytes only change when the archive itself does.
         */
        fun contentFingerprint(archiveFile: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("atlas-search-index/$INDEX_FORMAT/".toByteArray(Charsets.UTF_8))
            digest.update(archiveHeaderBytes(archiveFile))
            digest.update(archiveFile.length().toString().toByteArray(Charsets.UTF_8))
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun databaseFile(searchDir: File, fingerprint: String): File =
            File(searchDir, "search-$fingerprint.db")

        /**
         * The completion marker for [databaseFile]'s fingerprint — written
         * only by a pass that ran to the end. [indexExists] requires BOTH
         * files; the marker alone (DB deleted by hand) or the DB alone
         * (cancelled or killed pass) must count as "needs indexing".
         */
        fun completionFile(searchDir: File, fingerprint: String): File =
            File(searchDir, "search-$fingerprint.done")

        /** Deletes every index DB and marker under [searchDir] (a replaced archive). */
        fun deleteAll(searchDir: File) {
            searchDir.listFiles()?.forEach { it.delete() }
            searchDir.delete()
        }
    }
}