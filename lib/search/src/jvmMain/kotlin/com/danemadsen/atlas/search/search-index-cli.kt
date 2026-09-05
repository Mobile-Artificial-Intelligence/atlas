package com.danemadsen.atlas.search

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.danemadsen.atlas.pmtiles.PmtilesReader
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * CI-side minter for the prebuilt search index (the search counterpart of
 * the app's graph-build CLI): runs the SAME [SearchIndexer.indexCheapPass]
 * the device's import runs, over the SAME archive, through the SAME Room
 * schema — the only difference is the driver, and Room's identity hash is
 * target-independent, so the minted DB opens on device as if the device
 * had built it itself. Importing the artifact dir (Settings → Load search
 * index, or the first-launch dialog) then takes search from "a
 * minutes-to-hours build" to "instant".
 *
 * The out dir IS the artifact: the DB, the completion marker the pass
 * writes, and the adopt gate's manifest. upload-artifact zips the dir's
 * contents to the artifact root — exactly the layout the app's adopt path
 * parses (same trick as the prebuilt routing segments).
 *
 *   ./gradlew :lib:search:searchIndexCli \
 *     -Parchive=out/atlas-$COUNTRY.pmtiles -Pout=out/search -Pheap=4g
 */
object SearchIndexCli {

    @JvmStatic
    fun main(args: Array<String>) {
        val archive_path = args.getOrNull(0)
            ?: error("usage: searchIndexCli <archive.pmtiles> <out_dir>")
        val out_path = args.getOrNull(1)
            ?: error("usage: searchIndexCli <archive.pmtiles> <out_dir>")
        val archive_file = File(archive_path)
        require(archive_file.isFile) { "archive not found: $archive_file" }
        val out_dir = File(out_path)
        out_dir.mkdirs()

        val fingerprint = SearchIndexer.contentFingerprint(archive_file)
        val db_file = SearchIndexer.databaseFile(out_dir, fingerprint)
        val marker_file = SearchIndexer.completionFile(out_dir, fingerprint)
        // A leftover DB from a previous run would RESUME (inserts are
        // idempotent) instead of building fresh — the right behaviour on
        // device, wrong for minting an artifact: the manifest must describe
        // a complete pass over this exact archive, so start from nothing.
        db_file.delete()
        marker_file.delete()

        val openDatabase = { file: File ->
            Room.databaseBuilder<PlaceDatabase>(file.absolutePath)
                .setDriver(BundledSQLiteDriver())
                .build()
        }
        val indexer = SearchIndexer(db_file, openDatabase)
        PmtilesReader(archive_file.absolutePath).use { reader ->
            runBlocking { indexer.indexCheapPass(reader) }
        }

        // The manifest's counts come from the minted DB itself, not from
        // the pass's insert tallies — the artifact's own proof it carries
        // what the manifest claims.
        val (places, addresses) = runBlocking {
            val db = openDatabase(db_file)
            try {
                db.placeDao().count() to db.addressDao().count()
            } finally {
                db.close()
            }
        }

        // A WAL log left behind would mean the artifact ships an incomplete
        // DB (its tail would be missing); closing the last connection
        // checkpoints and removes it, so a surviving -wal file is a bug to
        // fail loudly on, not a case to upload around.
        val wal_file = File(db_file.parentFile, db_file.name + "-wal")
        check(!wal_file.exists() || wal_file.length() == 0L) {
            "index DB left a WAL log behind: $wal_file — the artifact would be incomplete"
        }

        // Room's lock sidecar outlives the close (and a -shm rides with a
        // WAL). The artifact dir must carry only what the app's adopt path
        // parses — the DB, the marker, the manifest.
        File(db_file.parentFile, db_file.name + ".lck").delete()
        File(db_file.parentFile, db_file.name + "-shm").delete()

        File(out_dir, SEARCH_MANIFEST_FILE).writeText(
            renderSearchManifest(fingerprint, places, addresses),
        )
        println("SEARCH INDEX RESULT: fp=$fingerprint places=$places addresses=$addresses")
    }
}