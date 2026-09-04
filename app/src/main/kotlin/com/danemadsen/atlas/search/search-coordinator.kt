package com.danemadsen.atlas.search

import android.content.Context
import com.danemadsen.atlas.data.ArchiveInfo
import com.danemadsen.atlas.pmtiles.PmtilesReader
import com.danemadsen.atlas.pmtiles.TileBounds
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The search side's counterpart of GraphBuildCoordinator: owns the index
 * database for the current archive, runs the import-time cheap pass, and
 * serves queries through a cached DB handle.
 *
 * The index DB lives under `filesDir/search/` keyed by the archive
 * fingerprint; replacing the archive deletes the stale DBs before the new
 * index builds.
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

    fun searchDir(context: Context): File = File(context.filesDir, "search")

    fun fingerprintFor(archive: ArchiveInfo): String = SearchIndexer.archiveFingerprint(
        fileName = archive.fileName,
        sizeBytes = archive.sizeBytes,
        west = archive.west,
        south = archive.south,
        east = archive.east,
        north = archive.north,
        minZoom = archive.minZoom,
        maxZoom = archive.maxZoom,
    )

    fun databaseFor(context: Context, archive: ArchiveInfo): File =
        SearchIndexer.databaseFile(searchDir(context), fingerprintFor(archive))

    private fun completionFor(context: Context, archive: ArchiveInfo): File =
        SearchIndexer.completionFile(searchDir(context), fingerprintFor(archive))

    /**
     * True only for a COMPLETE index: DB plus its completion marker. A
     * cancelled or killed pass leaves a partial DB with no marker, which
     * must count as "needs indexing" — the next launch re-runs the pass
     * over it (inserts are idempotent on the unique keys).
     */
    fun indexExists(context: Context, archive: ArchiveInfo): Boolean =
        databaseFor(context, archive).isFile && completionFor(context, archive).isFile

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
    fun deleteStaleIndexes(context: Context, archive: ArchiveInfo) {
        val current_db = databaseFor(context, archive).name
        val current_marker = completionFor(context, archive).name
        searchDir(context).listFiles()?.forEach { file ->
            if (file.name != current_db && file.name != current_marker) file.delete()
        }
    }

    /**
     * The import-time (or lazy-resume) pass over the archive's place zooms
     * 0-9. Null when another pass is already running.
     */
    suspend fun buildCheapIndex(
        context: Context,
        archive: ArchiveInfo,
        archiveFile: File,
    ): SearchIndexer.PassResult? {
        if (!indexing.compareAndSet(false, true)) return null
        try {
            // An incomplete current index (partial DB, no marker) resumes;
            // an index-format bump orphans the previous fingerprint's DB —
            // GBs of dead disk — cleaned up while no pass holds it open.
            if (!indexExists(context, archive)) deleteStaleIndexes(context, archive)
            val indexer = SearchIndexer(context, databaseFor(context, archive))
            PmtilesReader(archiveFile.absolutePath).use { reader ->
                val bounds = TileBounds(
                    west = archive.west,
                    south = archive.south,
                    east = archive.east,
                    north = archive.north,
                )
                return indexer.indexCheapPass(reader, bounds)
            }
        } finally {
            indexing.set(false)
        }
    }

    /** Top hits for [query] around the center; empty without an index. */
    suspend fun search(
        context: Context,
        archive: ArchiveInfo,
        query: String,
        centerLon: Double,
        centerLat: Double,
    ): List<PlaceHit> {
        if (!indexExists(context, archive)) return emptyList()
        return searchPlaces(queryDb(context, archive), query, centerLon, centerLat)
    }

    private suspend fun queryDb(context: Context, archive: ArchiveInfo): PlaceDatabase {
        val fingerprint = fingerprintFor(archive)
        cached_db?.takeIf { it.first == fingerprint }?.let { return it.second }
        cached_db?.second?.close()
        cached_db = null
        val db = SearchIndexer(context, databaseFor(context, archive)).open()
        cached_db = fingerprint to db
        return db
    }
}