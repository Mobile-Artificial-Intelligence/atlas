package com.danemadsen.atlas.search

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One indexed named place or POI, from the archive's `place`/`poi` layers.
 *
 * [zoom] is the tile zoom the feature was read at: the SAME real-world
 * place appears at many zooms (a city is a point at z4 and z9), and the
 * indexer keeps the LOWEST-zoom occurrence — its representative point sits
 * at the place's conceptual center rather than a z14 edge-duplicated
 * vertex, and one row per real place is all the picker needs.
 */
@Entity(
    tableName = "place",
    indices = [Index(value = ["dedupeKey"], unique = true)],
)
data class PlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** OMT `class`: city/town/village/hamlet/suburb/neighbourhood/…/shop/restaurant… */
    val kind: String,
    val subclass: String?,
    /** OMT `rank` where present; lower = more prominent. MAX_RANK when absent. */
    val rank: Int,
    val lon: Double,
    val lat: Double,
    /** The tile zoom this row was indexed from (see class doc). */
    val zoom: Int,
    /** `"<name>|<kind>"` — the uniqueness key the indexer dedupes on. */
    val dedupeKey: String,
)

/**
 * FTS shadow of [PlaceEntity.name]. unicode61 splits names the way users
 * type them ("Port Macquarie" -> two tokens); the `simple` tokenizer would
 * fold all non-ASCII to nothing, which is fatal for a planet of
 * transliterated names.
 */
@Fts4(contentEntity = PlaceEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "place_fts")
data class PlaceFtsEntity(
    val name: String,
)