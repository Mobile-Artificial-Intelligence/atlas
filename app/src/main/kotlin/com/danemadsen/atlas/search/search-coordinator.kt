package com.danemadsen.atlas.search

import android.content.Context
import androidx.room.Room
import com.danemadsen.atlas.data.RegionInfo
import com.danemadsen.atlas.data.RegionStore
import com.danemadsen.atlas.graph.copyBounded
import com.danemadsen.atlas.graph.stageZip
import com.danemadsen.atlas.graph.usableAbove
import com.danemadsen.atlas.pmtiles.PmtilesReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * The search side's counterpart of GraphBuildCoordinator: owns the index
 * databases for ALL installed regions, runs the import-time cheap pass,
 * and serves queries through cached DB handles.
 *
 * Each index DB lives under `filesDir/search/` keyed by its region's
 * CONTENT fingerprint ([SearchIndexer.contentFingerprint] — the same
 * 127-header-bytes identity the routing manifest keys on); every region's
 * index is independent, so a query fans out over the regions and merges.
 */
object SearchCoordinator {

    /**
     * Single-flight for the cheap pass: the VM's import hook and its lazy
     * resume check can both fire it, and two concurrent writers on one DB
     * file is a corruption risk, not a speedup.
     */
    private val indexing = AtomicBoolean(false)

    /**
     * The open query DBs, keyed by the search fingerprint they were opened
     * for — one per region whose index is being served. A region's entry
     * is closed and dropped when its file is replaced (an adopt-swap) or
     * deleted (stale cleanup, full wipe); a NEW fingerprint is simply a
     * new entry, so adding a region never disturbs the other regions'
     * open handles.
     */
    private val cached_dbs = HashMap<String, PlaceDatabase>()

    private const val ADOPT_SCRATCH_DIR = "adopt-scratch"
    private const val STAGED_ZIP_FILE = "staged.zip"
    private const val TMP_SUFFIX = ".tmp"
    private const val MAX_MANIFEST_BYTES = 1L shl 20 // 1 MB
    private const val MAX_MARKER_BYTES = 1L shl 10 // 1 KB; the real marker is empty
    private const val MAX_INDEX_DB_BYTES = 8L shl 30 // 8 GB; the largest country index is a few GB
    private const val MIN_FREE_DISK_BYTES = 256L shl 20 // leave 256 MB free
    private val INDEX_DB_ENTRY_RE = Regex("search-([0-9a-f]{64})\\.db")
    private val INDEX_DONE_ENTRY_RE = Regex("search-([0-9a-f]{64})\\.done")

    fun searchDir(context: Context): File = File(context.filesDir, "search")

    /** The index DB file for [region]'s current content fingerprint. */
    fun databaseFileFor(context: Context, region: RegionInfo): File =
        SearchIndexer.databaseFile(searchDir(context), region.searchFingerprint)

    private fun completionFileFor(context: Context, region: RegionInfo): File =
        SearchIndexer.completionFile(searchDir(context), region.searchFingerprint)

    /**
     * The app's own Room builder — the one handle-creation path, and the
     * identity check an ADOPTED index must pass: a CI-minted DB opens (and
     * its schema reads back) through the same builder the device's own
     * index uses, or the adoption refuses it.
     */
    fun openDatabase(context: Context, file: File): PlaceDatabase =
        Room.databaseBuilder(
            context.applicationContext,
            PlaceDatabase::class.java,
            file.absolutePath,
        ).build()

    /**
     * True only for a COMPLETE index for [region]: DB plus its completion
     * marker. A cancelled or killed pass leaves a partial DB with no
     * marker, which must count as "needs indexing" — the next launch
     * re-runs the pass over it (inserts are idempotent on the unique
     * keys).
     */
    fun indexExists(context: Context, region: RegionInfo): Boolean =
        databaseFileFor(context, region).isFile && completionFileFor(context, region).isFile

    /**
     * Deletes every index DB and marker (a Rebuild search must never read
     * a stale one) and closes + drops every cached open handle — they all
     * point at deleted files.
     */
    fun deleteIndexes(context: Context) {
        closeCachedDbs()
        SearchIndexer.deleteAll(searchDir(context))
    }

    /**
     * Deletes index DBs, markers and any other file other than the
     * installed regions' CURRENT index files (an index-format bump or a
     * re-imported region orphans the previous fingerprint's DB — GBs of
     * dead disk). The keep-set is computed from [RegionStore], so every
     * installed region's DB + marker survive regardless of whether its
     * index is complete yet (a partial DB is worth resuming, not
     * deleting); anything else goes. Only called while no pass is running,
     * so no pass can hold an about-to-be-deleted file open.
     */
    fun deleteStaleIndexes(context: Context) {
        val keep = RegionStore.loadAll(context)
            .flatMap { listOf(databaseFileFor(context, it).name, completionFileFor(context, it).name) }
            .toSet()
        dropCachedDbsExcept(keep)
        searchDir(context).listFiles()?.forEach { file ->
            if (file.name !in keep) file.delete()
        }
    }

    private fun indexerFor(context: Context, region: RegionInfo): SearchIndexer =
        SearchIndexer(databaseFileFor(context, region)) { file ->
            openDatabase(context, file)
        }

    /**
     * The full index pass over ONE region — places (zooms 0-9, tens of
     * seconds) and, when the archive carries the merged address layer, the
     * z14 address sweep. Runs in the `:graph` service per region; null
     * when another pass is already running (the coordinator's
     * single-flight lock).
     *
     * [onProgress] and [isCancelled] ride [SearchIndexer.indexCheapPass] —
     * the service's status file and its flag-based cancel both hang off
     * them. [anchorLonLat] (`lon to lat`) orders the sweep nearest-first
     * around the anchor so nearby addresses become searchable first; null
     * keeps plain order.
     */
    suspend fun buildCheapIndex(
        context: Context,
        region: RegionInfo,
        anchorLonLat: Pair<Double, Double>? = null,
        onProgress: (label: String, fraction: Float?) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): SearchIndexer.PassResult? {
        if (!indexing.compareAndSet(false, true)) return null
        try {
            // An incomplete current index (partial DB, no marker) resumes;
            // an index-format bump or a re-imported region orphans the
            // previous fingerprint's DB — GBs of dead disk — cleaned up
            // while no pass holds it open (the keep-set spans all regions,
            // so sibling regions' indexes are never touched).
            if (!indexExists(context, region)) deleteStaleIndexes(context)
            val indexer = indexerFor(context, region)
            PmtilesReader(
                RegionStore.archiveFile(RegionStore.mapDir(context), region.id).absolutePath,
            ).use { reader ->
                return indexer.indexCheapPass(reader, anchorLonLat, onProgress, isCancelled)
            }
        } finally {
            indexing.set(false)
        }
    }

    /**
     * Top hits for [query] around the center, across EVERY installed
     * region. One PlaceDatabase handle per region whose DB file exists is
     * opened (cached per fingerprint; see [cached_dbs]) and queried via
     * [searchPlacesMulti], which applies the single rank-then-distance
     * ordering across all regions.
     *
     * Serves from a PARTIAL index too: stage 1's place rows commit before
     * the address sweep starts (its own doc contract), so search works
     * during the minutes-to-hours the address stage runs — gating on the
     * completion marker instead would dead-search the whole import. The
     * DB file's presence is the gate, NOT the marker.
     */
    suspend fun search(
        context: Context,
        rawQuery: String,
        centerLon: Double,
        centerLat: Double,
    ): List<PlaceHit> {
        val regions = withContext(Dispatchers.IO) { RegionStore.loadAll(context) }
        // Per-region gate: the DB FILE exists, not the completion marker —
        // a mid-build index serves its committed stage-1 rows (see above).
        val dbs = regions.filter { databaseFileFor(context, it).isFile }
            .map { queryDb(context, it) }
        return searchPlacesMulti(dbs, rawQuery, centerLon, centerLat)
    }

    /** The cached (or freshly opened) query handle for [region]'s fingerprint. */
    private fun queryDb(context: Context, region: RegionInfo): PlaceDatabase {
        val fingerprint = region.searchFingerprint
        synchronized(cached_dbs) {
            cached_dbs[fingerprint]?.let { return it }
        }
        val db = indexerFor(context, region).open()
        synchronized(cached_dbs) {
            // A racing close may have dropped the entry while this handle
            // was being opened; either way exactly one handle is cached —
            // a second open of the same file would leak one.
            cached_dbs[fingerprint]?.let { existing ->
                db.close()
                return existing
            }
            cached_dbs[fingerprint] = db
            return db
        }
    }

    /** Closes and drops every cached query handle. */
    private fun closeCachedDbs() {
        synchronized(cached_dbs) {
            val handles = cached_dbs.values.toList()
            cached_dbs.clear()
            for (db in handles) runCatching { db.close() }
        }
    }

    /**
     * Closes and drops every cached query handle EXCEPT [keep]'s — used
     * when files are about to be replaced or deleted; a kept handle still
     * points at its (surviving) file.
     */
    private fun dropCachedDbsExcept(keep: Set<String>) {
        synchronized(cached_dbs) {
            val dropped = cached_dbs.entries.filter { it.key !in keep }
            for (entry in dropped) {
                runCatching { entry.value.close() }
                cached_dbs.remove(entry.key)
            }
        }
    }

    // ---- prebuilt index adoption (the search counterpart of
    // GraphBuildManager.adoptPrebuiltSegments) ----

    /** What a prebuilt search index installed, for progress/UI reporting. */
    data class IndexAdoption(
        val places: Int,
        val addresses: Int,
    )

    /**
     * Installs a CI-minted search index (the `atlas-search-<country>`
     * artifact from the same build as [region]'s archive) for [region]:
     * search then works immediately instead of after the minutes-to-hours
     * on-device build. Throws with a user-presentable message when the
     * file is not a usable Atlas search index for THIS region.
     *
     * Same hardening as the routing adoption, adapted to two files:
     * - The manifest gate: the fingerprint must match [region]'s
     *   [content fingerprint][RegionInfo.searchFingerprint] — a
     *   daily-rebuilt archive and yesterday's index must not mix, and the
     *   check turns the mismatch into an actionable refusal instead of a
     *   silently-wrong search.
     * - Bounded extraction: the manifest and marker read under small byte
     *   caps, the DB streams to a scratch dir under a gigabytes-scale
     *   cap, with a free-space floor before the stream starts — a crafted
     *   ZIP cannot fill the partition or bomb the heap.
     * - Integrity check BEFORE anything live is touched: the extracted DB
     *   must open through the app's own Room builder (Room's identity
     *   check) and answer the manifest's row counts. A DB that fails that
     *   is not an index this app could have built.
     * - Live-state safety: the DB lands via temp + atomic rename and the
     *   completion marker LAST — a torn adopt leaves a DB with no marker,
     *   which the resume check already treats as "needs indexing", so the
     *   next launch self-heals instead of serving a torn index.
     */
    suspend fun adoptPrebuiltIndex(
        context: Context,
        region: RegionInfo,
        zip: InputStream,
    ): IndexAdoption = withContext(Dispatchers.IO) {
        // The same write lock as the cheap pass: an adopt must not race a
        // running pass on the same DB file. The caller (the VM's install
        // entry point) stops a running pass first, so a held lock here
        // means a pass the UI could not stop — refuse, don't interleave.
        if (!indexing.compareAndSet(false, true)) {
            error("the search index is being built — wait for the build to finish, then install again")
        }
        try {
            val search_dir = searchDir(context)
            val scratch = File(search_dir, ADOPT_SCRATCH_DIR)
            scratch.deleteRecursively()
            scratch.mkdirs()
            try {
                var manifest: SearchManifest? = null
                var db_name: String? = null
                var marker_name: String? = null
                // GitHub's artifact ZIPs (upload-artifact, compression-level
                // 0) are STORED entries flagged with a trailing data
                // descriptor — ZipInputStream refuses them (OpenJDK throws,
                // older Android stops iterating), which is exactly the "no
                // manifest" failure CI-generated search indexes hit. Staging
                // to a file and reading the central directory instead reads
                // both layouts. stageZip keeps the floor guards; the DB's
                // declared entry size is the fit check below.
                val staged = File(scratch, STAGED_ZIP_FILE)
                stageZip(zip, staged, MAX_INDEX_DB_BYTES, MIN_FREE_DISK_BYTES, "the search index file")
                val zip_file = runCatching { ZipFile(staged) }
                    .getOrElse {
                        error("the search index file is not a readable ZIP archive — wrong file?")
                    }
                try {
                    for (entry in zip_file.entries()) {
                        if (entry.isDirectory) continue
                        val name = entry.name.substringAfterLast('/')
                        when {
                            name == SEARCH_MANIFEST_FILE -> {
                                // Not readBytes(): a crafted ZIP entry can
                                // decompress GBs onto the UI-process heap;
                                // the real manifest is one short line.
                                val out = ByteArrayOutputStream()
                                copyBounded(zip_file.getInputStream(entry), out, MAX_MANIFEST_BYTES, "the search index manifest")
                                manifest = parseSearchManifest(out.toString(Charsets.UTF_8))
                            }
                            INDEX_DB_ENTRY_RE.matchEntire(name) != null -> {
                                require(db_name == null) {
                                    "the search index file holds more than one index — wrong file?"
                                }
                                // The free-space floor as a gate before the
                                // stream starts: an extraction that would
                                // fill the partition fails with a message,
                                // not an opaque ENOSPC. The declared entry
                                // size turns the floor into a real fit
                                // check — the DB is the multi-GB entry, a
                                // single floor probe against it would still
                                // let the copy run the partition dry and
                                // die with the system message mid-stream.
                                require(usableAbove(scratch, MIN_FREE_DISK_BYTES)) {
                                    "not enough free storage to install the " +
                                        "search index — free up space and try again"
                                }
                                if (entry.size > 0) {
                                    val usable = scratch.usableSpace
                                    require(usable <= 0 || usable - MIN_FREE_DISK_BYTES >= entry.size) {
                                        "not enough free storage to install the " +
                                            "search index — free up space and try again"
                                    }
                                }
                                val out = File(scratch, name)
                                out.outputStream().use { output ->
                                    copyBounded(zip_file.getInputStream(entry), output, MAX_INDEX_DB_BYTES, "the search index database")
                                }
                                db_name = name
                            }
                            INDEX_DONE_ENTRY_RE.matchEntire(name) != null -> {
                                // Zero bytes in a real artifact; bounded for
                                // the same bomb reason as the manifest.
                                val out = ByteArrayOutputStream()
                                copyBounded(zip_file.getInputStream(entry), out, MAX_MARKER_BYTES, "the search index marker")
                                marker_name = name
                            }
                            // Anything else (readme, checksums) is ignored.
                        }
                    }
                } finally {
                    zip_file.close()
                }

                // The manifest gate — the reason a CI-built index cannot mix
                // archives (see the routing manifest's doc).
                val index_manifest = manifest
                    ?: error("the search index file has no manifest — re-download the search " +
                        "index and the map archive from the same build, then install both")
                require(index_manifest.archiveFingerprint == region.searchFingerprint) {
                    "this search index was built from a different map archive — use the " +
                        "search index from the same download as your map archive"
                }
                // A 0/0 manifest would pass the integrity check against a
                // 0-byte DB (Room CREATES the schema into it and both counts
                // read 0), adopting a permanently-empty "complete" index.
                // Every real archive yields places; a manifest claiming
                // none is not a real index.
                require(index_manifest.places > 0) {
                    "the search index contains no places — re-download the search " +
                        "index and the map archive from the same build, then install both"
                }
                val name = db_name
                    ?: error("the search index file contains no search index — wrong file?")
                val db_fingerprint = name.removePrefix("search-").removeSuffix(".db")
                require(db_fingerprint == region.searchFingerprint) {
                    "the search index inside the file was built from a different map " +
                        "archive — use the search index from the same download as your map archive"
                }

                // Integrity check before mutating anything live: the DB
                // must open through THIS app's Room builder (Room validates
                // its schema identity hash) and answer the manifest's
                // counts — the artifact's own proof it is a complete index,
                // not a torn or foreign DB.
                val scratch_db = File(scratch, name)
                val places: Int
                val addresses: Int
                // try/finally, not use{}: PlaceDatabase is not Closeable on
                // the Room 2.8 artifact (same as the CLI's reopen pass).
                val db = openDatabase(context, scratch_db)
                try {
                    places = db.placeDao().count()
                    addresses = db.addressDao().count()
                } finally {
                    db.close()
                }
                require(places == index_manifest.places && addresses == index_manifest.addresses) {
                    "the search index inside the file does not match its manifest — " +
                        "re-download the search index and try again"
                }

                // Commit. The cached query handle for THIS region points at
                // whatever the search dir held before; drop it first — it
                // may be the file the rename is about to replace. Handles
                // for OTHER regions are untouched: their files are not.
                dropCachedDbsExcept(
                    keptHandles(RegionStore.loadAll(context)) - region.searchFingerprint,
                )
                val live_db = databaseFileFor(context, region)
                val tmp_db = File(search_dir, "${live_db.name}$TMP_SUFFIX")
                scratch_db.copyTo(tmp_db, overwrite = true)
                if (!tmp_db.renameTo(live_db)) {
                    live_db.delete()
                    check(tmp_db.renameTo(live_db)) { "could not install the search index database" }
                }
                // The marker LAST: complete only once the DB has landed.
                val live_marker = completionFileFor(context, region)
                val tmp_marker = File(search_dir, "${live_marker.name}$TMP_SUFFIX")
                tmp_marker.writeText("")
                if (!tmp_marker.renameTo(live_marker)) {
                    live_marker.delete()
                    check(tmp_marker.renameTo(live_marker)) { "could not install the search index marker" }
                }
                // Drop the cached query handle AGAIN: a search that ran
                // during the multi-GB scratch->tmp copy re-cached the OLD
                // file's handle (the swap had not happened yet), and that
                // handle still points at the unlinked old inode — it would
                // serve stale results forever. The commit above replaced
                // the file; this replaces the app's view of it.
                dropCachedDbsExcept(
                    keptHandles(RegionStore.loadAll(context)) - region.searchFingerprint,
                )
                deleteStaleIndexes(context)
                IndexAdoption(places, addresses)
            } finally {
                scratch.deleteRecursively()
            }
        } finally {
            indexing.set(false)
        }
    }

    /** The fingerprints whose DB+marker files survive [deleteStaleIndexes]. */
    private fun keptHandles(regions: List<RegionInfo>): Set<String> =
        regions.map { it.searchFingerprint }.toSet()
}