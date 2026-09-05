package com.danemadsen.atlas.search

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One indexed OpenAddresses point (merged into the archive by the tile CI's
 * `address` layer), in its own table — NOT [PlaceEntity].
 *
 * Countries carry millions of address rows against thousands of places, so
 * sharing the place table would put every place query one doclist away from a
 * 14M-row enumeration; the split keeps place search O(places) and lets the
 * address query run only when the query looks like an address (see
 * [isAddressQuery]). Address rows also dedupe purely on their unique
 * [dedupeKey] index (location-quantized, so the same "69 Mott St" in two
 * towns stays two rows) — they never join the zoom-based dedupe places use.
 */
@Entity(
    tableName = "address",
    indices = [Index(value = ["dedupeKey"], unique = true)],
)
data class AddressEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** `"<unit/number> <title-cased street>"` — see [addressName]. */
    val name: String,
    /** Title-cased city, the results drawer's subtitle. */
    val city: String?,
    val lon: Double,
    val lat: Double,
    /** `"<number>|<unit>|<street>|<quantized lat/lon>"` — see [addressDedupeKey]. */
    val dedupeKey: String,
)

/**
 * FTS shadow of [AddressEntity.name], the same unicode61 setup as
 * [PlaceFtsEntity] — house numbers, unit prefixes and street words tokenize
 * exactly the way they were written into [AddressEntity.name].
 */
@Fts4(contentEntity = AddressEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "address_fts")
data class AddressFtsEntity(
    val name: String,
)