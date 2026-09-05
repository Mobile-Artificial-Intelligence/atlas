package com.danemadsen.atlas.search

import android.content.Context
import androidx.room.Room
import com.danemadsen.atlas.graph.copyBounded
import com.danemadsen.atlas.pmtiles.PmtilesReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/**
 * The search side's counterpart of GraphBuildCoordinator: owns the index
 * database for the current archive, runs the import-time cheap pass, and
 * serves queries through a cached DB handle.
 *
 * The index DB lives under `filesDir/search/` keyed by the archive's
 * CONTENT fingerprint ([SearchIndexer.contentFingerprint] — the same
 * 127-header-bytes identity the routing manifest keys on); replacing the
 * archive deletes the stale DBs before the new index builds.
 */
object SearchCoordinator {

    /**
     * Single-flight for the cheap pass: the VM's import hook and its lazy
     * resume check can both fire it, and two concurrent writers on one DB
     * file is a corruption risk, not a speedup.
     */
    private val indexing = AtomicBoolean(false)

    /** The open query DB, keyed by the archive fingerprint it was opened for. */
    private var cached_db: Pair<String, PlaceDatabase>? = null

    private const val ADOPT_SCRATCH_DIR = "adopt-scratch"
    private const val TMP_SUFFIX = ".tmp"
    private const val MAX_MANIFEST_BYTES = 1L shl 20 // 1 MB
    private const val MAX_MARKER_BYTES = 1L shl 10 // 1 KB; the real marker is empty
    private const val MAX_INDEX_DB_BYTES = 8L shl 30 // 8 GB; the largest country index is a few GB
    private const val MIN_FREE_DISK_BYTES = 256L shl 20 // leave 256 MB free
    private val INDEX_DB_ENTRY_RE = Regex("search-([0-9a-f]{64})\\.db")
    private val INDEX_DONE_ENTRY_RE = Regex("search-([0-9a-f]{64})\\.done")

    fun searchDir(context: Context): File = File(context.filesDir, "search")

    fun fingerprintFor(archiveFile: File): String = SearchIndexer.contentFingerprint(archiveFile)

    fun databaseFor(context: Context, archiveFile: File): File =
        SearchIndexer.databaseFile(searchDir(context), fingerprintFor(archiveFile))

    private fun completionFor(context: Context, archiveFile: File): File =
        SearchIndexer.completionFile(searchDir(context), fingerprintFor(archiveFile))

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
     * True only for a COMPLETE index: DB plus its completion marker. A
     * cancelled or killed pass leaves a partial DB with no marker, which
     * must count as "needs indexing" — the next launch re-runs the pass
     * over it (inserts are idempotent on the unique keys).
     */
    fun indexExists(context: Context, archiveFile: File): Boolean =
        databaseFor(context, archiveFile).isFile && completionFor(context, archiveFile).isFile

    /**
     * Deletes every index DB (a replaced archive must never read a stale
     * one) and drops the cached open handle — it points at a deleted file.
     */
    fun deleteIndexes(context: Context) {
        cached_db?.second?.close()
        cached_db = null
        SearchIndexer.deleteAll(searchDir(context))
    }

    /**
     * Deletes index DBs, markers and any other file other than the current
     * archive's (an index-format bump orphans the previous fingerprint's DB
     * — GBs of dead disk). Only called while the current index is NOT
     * complete, and a pass never runs concurrently with this call, so no
     * pass can hold an about-to-be-deleted file open. The current archive's
     * own files are kept: a partial DB is worth resuming, not deleting.
     */
    fun deleteStaleIndexes(context: Context, archiveFile: File) {
        val current_db = databaseFor(context, archiveFile).name
        val current_marker = completionFor(context, archiveFile).name
        searchDir(context).listFiles()?.forEach { file ->
            if (file.name != current_db && file.name != current_marker) file.delete()
        }
    }

    private fun indexerFor(context: Context, archiveFile: File): SearchIndexer =
        SearchIndexer(databaseFor(context, archiveFile)) { file ->
            openDatabase(context, file)
        }

    /**
     * The import-time (or lazy-resume) pass over the archive's place zooms
     * 0-9. Null when another pass is already running.
     */
    suspend fun buildCheapIndex(
        context: Context,
        archiveFile: File,
    ): SearchIndexer.PassResult? {
        if (!indexing.compareAndSet(false, true)) return null
        try {
            // An incomplete current index (partial DB, no marker) resumes;
            // an index-format bump orphans the previous fingerprint's DB —
            // GBs of dead disk — cleaned up while no pass holds it open.
            if (!indexExists(context, archiveFile)) deleteStaleIndexes(context, archiveFile)
            val indexer = indexerFor(context, archiveFile)
            PmtilesReader(archiveFile.absolutePath).use { reader ->
                return indexer.indexCheapPass(reader)
            }
        } finally {
            indexing.set(false)
        }
    }

    /**
     * Top hits for [query] around the center; empty without an index.
     *
     * Serves from a PARTIAL index too: stage 1's place rows commit before
     * the address sweep starts (its own doc contract), so search works
     * during the minutes-to-hours the address stage runs — gating on the
     * completion marker instead would dead-search the whole import. The
     * DB file's presence is the gate, NOT the marker.
     */
    suspend fun search(
        context: Context,
        archiveFile: File,
        query: String,
        centerLon: Double,
        centerLat: Double,
    ): List<PlaceHit> {
        if (!databaseFor(context, archiveFile).isFile) return emptyList()
        return searchPlaces(queryDb(context, archiveFile), query, centerLon, centerLat)
    }

    private suspend fun queryDb(context: Context, archiveFile: File): PlaceDatabase {
        val fingerprint = fingerprintFor(archiveFile)
        cached_db?.takeIf { it.first == fingerprint }?.let { return it.second }
        cached_db?.second?.close()
        cached_db = null
        val db = indexerFor(context, archiveFile).open()
        cached_db = fingerprint to db
        return db
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
     * artifact from the same build as the installed archive): search then
     * works immediately instead of after the minutes-to-hours on-device
     * build. Throws with a user-presentable message when the file is not
     * a usable Atlas search index for THIS archive.
     *
     * Same hardening as the routing adoption, adapted to two files:
     * - The manifest gate: the fingerprint must match this archive's
     *   [content fingerprint][fingerprintFor] — a daily-rebuilt archive and
     *   yesterday's index must not mix, and the check turns the mismatch
     *   into an actionable refusal instead of a silently-wrong search.
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
        archiveFile: File,
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
                ZipInputStream(BufferedInputStream(zip)).use { input ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val name = entry.name.substringAfterLast('/')
                        when {
                            name == SEARCH_MANIFEST_FILE -> {
                                // Not readBytes(): a crafted ZIP entry can
                                // decompress GBs onto the UI-process heap;
                                // the real manifest is one short line.
                                val out = ByteArrayOutputStream()
                                copyBounded(input, out, MAX_MANIFEST_BYTES, "the search index manifest")
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
                                require(scratch.usableSpace > MIN_FREE_DISK_BYTES) {
                                    "not enough free storage to install the " +
                                        "search index — free up space and try again"
                                }
                                if (entry.size > 0) {
                                    require(scratch.usableSpace - MIN_FREE_DISK_BYTES >= entry.size) {
                                        "not enough free storage to install the " +
                                            "search index — free up space and try again"
                                    }
                                }
                                val out = File(scratch, name)
                                out.outputStream().use { output ->
                                    copyBounded(input, output, MAX_INDEX_DB_BYTES, "the search index database")
                                }
                                db_name = name
                            }
                            INDEX_DONE_ENTRY_RE.matchEntire(name) != null -> {
                                // Zero bytes in a real artifact; bounded for
                                // the same bomb reason as the manifest.
                                val out = ByteArrayOutputStream()
                                copyBounded(input, out, MAX_MARKER_BYTES, "the search index marker")
                                marker_name = name
                            }
                            // Anything else (readme, checksums) is ignored.
                        }
                    }
                }

                // The manifest gate — the reason a CI-built index cannot mix
                // archives (see the routing manifest's doc).
                val index_manifest = manifest
                    ?: error("the search index file has no manifest — re-download the search " +
                        "index and the map archive from the same build, then install both")
                require(index_manifest.archiveFingerprint == fingerprintFor(archiveFile)) {
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
                require(db_fingerprint == fingerprintFor(archiveFile)) {
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

                // Commit. The cached query handle points at whatever the
                // search dir held before; drop it first — it may be the
                // file the rename is about to replace.
                cached_db?.second?.close()
                cached_db = null
                val live_db = databaseFor(context, archiveFile)
                val tmp_db = File(search_dir, "${live_db.name}$TMP_SUFFIX")
                scratch_db.copyTo(tmp_db, overwrite = true)
                if (!tmp_db.renameTo(live_db)) {
                    live_db.delete()
                    check(tmp_db.renameTo(live_db)) { "could not install the search index database" }
                }
                // The marker LAST: complete only once the DB has landed.
                val live_marker = completionFor(context, archiveFile)
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
                cached_db?.second?.close()
                cached_db = null
                deleteStaleIndexes(context, archiveFile)
                IndexAdoption(places, addresses)
            } finally {
                scratch.deleteRecursively()
            }
        } finally {
            indexing.set(false)
        }
    }
}