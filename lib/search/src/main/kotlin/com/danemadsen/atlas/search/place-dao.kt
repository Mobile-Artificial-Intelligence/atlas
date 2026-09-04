package com.danemadsen.atlas.search

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction

/**
 * What one candidate row looks like to the scorer, before limit/truncation.
 */
data class PlaceCandidate(
    val id: Long,
    val name: String,
    val kind: String,
    val subclass: String?,
    val rank: Int,
    val lon: Double,
    val lat: Double,
)

@Dao
interface PlaceDao {

    /**
     * Raw FTS hits for a MATCH pattern, pre-ordered so a broad query cannot
     * evict the important rows from the returned window: ranked places
     * first, then rows closest to the map center (Manhattan order — the
     * exact haversine re-sort happens in [searchPlaces] over the returned
     * window). The pattern is built by [ftsPattern].
     */
    @Query(
        "SELECT p.id, p.name, p.kind, p.subclass, p.rank, p.lon, p.lat " +
            "FROM place p JOIN place_fts fts ON p.id = fts.rowid " +
            "WHERE place_fts MATCH :pattern " +
            "ORDER BY p.rank, (abs(p.lat - :centerLat) + abs(p.lon - :centerLon)) LIMIT :limit",
    )
    suspend fun match(pattern: String, centerLon: Double, centerLat: Double, limit: Int): List<PlaceCandidate>

    /**
     * The dedupe probe for incremental indexing: the lowest zoom each
     * existing (name, kind) pair was already indexed at, EXCLUDING
     * [exceptKind] rows. Address rows (millions) must never be loaded into
     * an in-memory dedupe map — their dedupe is the unique dedupeKey index
     * plus insert-or-ignore — so passes filter them out in SQL.
     */
    @Query("SELECT name, kind, zoom FROM place WHERE kind <> :exceptKind")
    suspend fun existingZoomsExcept(exceptKind: String): List<PlaceZoomRow>

    /**
     * Insert-or-ignore on the unique dedupeKey: a rebuilt or overlapping
     * pass that offers a row the table already holds is a no-op, and a
     * pass that offers a NEW zoom variant never displaces the kept one
     * (the indexer only offers candidates that beat what exists).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(places: List<PlaceEntity>)

    /**
     * Rebuild the FTS shadow after a bulk insert session. contentEntity
     * FTS tables auto-sync per write, but a single rebuild at the end of
     * thousands of single-row syncs is measurably cheaper on old devices.
     */
    @Query("INSERT INTO place_fts(place_fts) VALUES('rebuild')")
    suspend fun rebuildFts(): Unit

    @Transaction
    suspend fun insertBatch(places: List<PlaceEntity>) {
        insertAll(places)
    }

    @Query("SELECT COUNT(*) FROM place")
    suspend fun count(): Int
}

/** Row shape of [PlaceDao.existingZooms]. */
data class PlaceZoomRow(val name: String, val kind: String, val zoom: Int)

@Database(entities = [PlaceEntity::class, PlaceFtsEntity::class], version = 1, exportSchema = false)
abstract class PlaceDatabase : RoomDatabase() {
    abstract fun placeDao(): PlaceDao
}