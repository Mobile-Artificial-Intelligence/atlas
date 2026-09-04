package com.danemadsen.atlas.search

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * A matched address row, before limit — the same shape [searchPlaces] scores
 * places and addresses with (rank is the constant [SearchIndexer.ADDRESS_RANK]).
 */
data class AddressCandidate(
    val id: Long,
    val name: String,
    val city: String?,
    val lon: Double,
    val lat: Double,
)

@Dao
interface AddressDao {

    /**
     * Raw FTS hits for a MATCH pattern, nearest to the map center first
     * (Manhattan order — the exact haversine re-sort happens in
     * [searchPlaces] over the returned window). All address rows share one
     * rank, so distance is the only ranking there is. The pattern is built by
     * [ftsPattern] and this query only runs for address-shaped queries (see
     * [isAddressQuery]) — never per keystroke of a place search.
     */
    @Query(
        "SELECT a.id, a.name, a.city, a.lon, a.lat " +
            "FROM address a JOIN address_fts fts ON a.id = fts.rowid " +
            "WHERE address_fts MATCH :pattern " +
            "ORDER BY (abs(a.lat - :centerLat) + abs(a.lon - :centerLon)) LIMIT :limit",
    )
    suspend fun match(pattern: String, centerLon: Double, centerLat: Double, limit: Int): List<AddressCandidate>

    /**
     * Insert-or-ignore on the unique dedupeKey: re-scanned tiles and
     * overlapping passes are no-ops for rows the table already holds.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(addresses: List<AddressEntity>)
}