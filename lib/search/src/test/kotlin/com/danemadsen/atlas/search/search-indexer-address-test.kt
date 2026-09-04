package com.danemadsen.atlas.search

import com.danemadsen.atlas.pmtiles.mvt.TilePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `address` layer extraction — hand-built MVT point tiles, no Android.
 * The protobuf writer is duplicated from lib/pmtiles' MvtDecoderTest (test
 * sources are not shared across modules; promoting it into pmtiles' main
 * sources would ship test-only code), extended with a POINT layer builder
 * and non-string property values (the merge pipeline can emit house numbers
 * as protobuf varints).
 */
class SearchIndexerAddressTest {

    // ---- protobuf test writer (duplicated from lib/pmtiles' MvtDecoderTest) ----

    private fun varint(value_in: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var value = value_in
        while (true) {
            val byte = (value and 0x7F).toInt()
            value = value ushr 7
            if (value == 0L) {
                out.write(byte)
                return out.toByteArray()
            }
            out.write(byte or 0x80)
        }
    }

    private fun zigzag(value: Int): Int = (value shl 1) xor (value shr 31)

    private fun field(fieldNumber: Int, wireType: Int) =
        varint(((fieldNumber shl 3) or wireType).toLong())

    private fun lenDelimited(fieldNumber: Int, payload: ByteArray): ByteArray =
        field(fieldNumber, 2) + varint(payload.size.toLong()) + payload

    private fun varintField(fieldNumber: Int, value: Long): ByteArray =
        field(fieldNumber, 0) + varint(value)

    private fun packedVarintField(fieldNumber: Int, values: List<Int>): ByteArray {
        val packed = java.io.ByteArrayOutputStream()
        values.forEach { packed.write(varint(it.toLong())) }
        return lenDelimited(fieldNumber, packed.toByteArray())
    }

    /** Geometry: one MoveTo — a POINT at tile-local (px, py). */
    private fun pointGeometry(px: Int, py: Int): List<Int> = listOf(
        (1 shl 3) or 1, // MoveTo, 1 pair
        zigzag(px), zigzag(py),
    )

    /**
     * One MVT value cell — string_value (field 1) or int64_value (field 4),
     * the shapes the merge pipeline and the extractor must both tolerate.
     */
    private fun mvtValue(value: Any): ByteArray = when (value) {
        is String -> lenDelimited(1, value.toByteArray(Charsets.UTF_8))
        is Long -> varintField(4, value)
        is Int -> varintField(4, value.toLong())
        else -> throw IllegalArgumentException("unsupported test value: $value")
    }

    /**
     * One MVT layer named [name], containing POINT features built from
     * (properties, tile-local point) pairs.
     */
    private fun buildLayer(
        name: String,
        features: List<Pair<Map<String, Any>, TilePoint>>,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(varintField(15, 2L)) // version
        out.write(lenDelimited(1, name.toByteArray(Charsets.UTF_8)))
        val keys = features.flatMap { it.first.keys }.distinct()
        val values = features.flatMap { it.first.values }.distinct()
        features.forEach { (props, point) ->
            val tags = ArrayList<Int>(props.size * 2)
            props.forEach { (key, value) ->
                tags.add(keys.indexOf(key))
                tags.add(values.indexOf(value))
            }
            val feature = varintField(1, 1L) +
                packedVarintField(2, tags) +
                varintField(3, 1L) + // MvtGeomType.POINT code
                packedVarintField(4, pointGeometry(point.x, point.y))
            out.write(lenDelimited(2, feature))
        }
        keys.forEach { out.write(lenDelimited(3, it.toByteArray(Charsets.UTF_8))) }
        values.forEach { out.write(lenDelimited(4, mvtValue(it))) }
        out.write(varintField(5, 4096L)) // extent
        return out.toByteArray()
    }

    /** A tile carrying the given layers. */
    private fun buildTile(layers: List<ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        layers.forEach { out.write(lenDelimited(3, it)) }
        return out.toByteArray()
    }

    private val Z14_TILE = 14789 to 10053 // Melbourne CBD

    private fun addressLayer(vararg features: Pair<Map<String, Any>, TilePoint>): ByteArray =
        buildLayer("address", features.toList())

    private fun placeLayer(vararg features: Pair<Map<String, Any>, TilePoint>): ByteArray =
        buildLayer("place", features.toList())

    private val MELB = 144.9631 to -37.8142

    @Test
    fun `extracts address rows from the address layer`() {
        val tile = buildTile(
            listOf(
                addressLayer(
                    mapOf(
                        "number" to "69",
                        "street" to "MOTT STREET",
                        "unit" to "",
                        "city" to "SINGAPORE",
                    ) to TilePoint(2048, 2048),
                ),
            ),
        )
        val rows = SearchIndexer.candidatesFromTile(14, Z14_TILE.first, Z14_TILE.second, tile).addresses
        assertEquals(1, rows.size)
        val row = rows[0]
        assertEquals("69 Mott Street", row.name)
        assertEquals("Singapore", row.city)
        assertTrue(row.dedupeKey.startsWith("address|"), row.dedupeKey)
        assertTrue(row.lon in MELB.first - 0.01..MELB.first + 0.01, "lon was ${row.lon}")
        assertTrue(row.lat in MELB.second - 0.01..MELB.second + 0.01, "lat was ${row.lat}")
    }

    @Test
    fun `varint-encoded number reads without a decimal tail`() {
        // A merge pipeline can encode the house number as a protobuf varint;
        // the extractor must read it as a whole number ("69", never "69.0").
        val tile = buildTile(
            listOf(
                addressLayer(
                    mapOf(
                        "number" to 69L,
                        "street" to "MOTT STREET",
                        "unit" to "",
                        "city" to "",
                    ) to TilePoint(2048, 2048),
                ),
            ),
        )
        val rows = SearchIndexer.candidatesFromTile(14, Z14_TILE.first, Z14_TILE.second, tile).addresses
        assertEquals(1, rows.size)
        assertEquals("69 Mott Street", rows[0].name)
        assertEquals(null, rows[0].city)
    }

    @Test
    fun `unit composes into the display name`() {
        val tile = buildTile(
            listOf(
                addressLayer(
                    mapOf(
                        "number" to "45",
                        "street" to "HARBOUR RD",
                        "unit" to "12",
                        "city" to "SYDNEY",
                    ) to TilePoint(2048, 2048),
                ),
            ),
        )
        val rows = SearchIndexer.candidatesFromTile(14, Z14_TILE.first, Z14_TILE.second, tile).addresses
        assertEquals(1, rows.size)
        assertEquals("12/45 Harbour Rd", rows[0].name)
        assertEquals("Sydney", rows[0].city)
    }

    @Test
    fun `rows without a number or a street are skipped`() {
        val tile = buildTile(
            listOf(
                addressLayer(
                    mapOf("number" to "69", "street" to "", "city" to "") to TilePoint(2048, 2048),
                    mapOf("number" to "", "street" to "MOTT STREET", "city" to "") to TilePoint(1024, 1024),
                ),
            ),
        )
        val rows = SearchIndexer.candidatesFromTile(14, Z14_TILE.first, Z14_TILE.second, tile).addresses
        assertTrue(rows.isEmpty(), "blank number/street rows must be skipped")
    }

    @Test
    fun `dedupe keys differ for the same text in different towns`() {
        // Two points in different z14 tiles ~4 km apart in Melbourne's east.
        val tile_a = buildTile(
            listOf(addressLayer(
                mapOf("number" to "69", "street" to "HIGH ST", "city" to "") to TilePoint(2048, 2048),
            )),
        )
        val tile_b = buildTile(
            listOf(addressLayer(
                mapOf("number" to "69", "street" to "HIGH ST", "city" to "") to TilePoint(4096, 2048),
            )),
        )
        val row_a = SearchIndexer.candidatesFromTile(14, Z14_TILE.first, Z14_TILE.second, tile_a).addresses[0]
        val row_b = SearchIndexer.candidatesFromTile(14, Z14_TILE.first + 1, Z14_TILE.second, tile_b).addresses[0]
        assertTrue(row_a.dedupeKey != row_b.dedupeKey, "same text 4 km apart must not collapse")
    }

    @Test
    fun `address layer is invisible below the address zoom`() {
        val bytes = buildTile(
            listOf(
                placeLayer(
                    mapOf("name" to "Melbourne", "class" to "city", "rank" to "3") to TilePoint(2048, 2048),
                ),
                addressLayer(
                    mapOf("number" to "69", "street" to "MOTT STREET", "city" to "") to TilePoint(2048, 2048),
                ),
            ),
        )
        val at_13 = SearchIndexer.candidatesFromTile(13, Z14_TILE.first, Z14_TILE.second, bytes)
        assertTrue(at_13.addresses.isEmpty(), "z13 must not yield address rows")
        assertEquals(1, at_13.places.size)
        val at_14 = SearchIndexer.candidatesFromTile(14, Z14_TILE.first, Z14_TILE.second, bytes)
        assertEquals(1, at_14.places.size)
        assertEquals(1, at_14.addresses.size)
    }

    @Test
    fun `tiles without an address layer yield no address rows`() {
        val bytes = buildTile(listOf(placeLayer(
            mapOf("name" to "Melbourne", "class" to "city", "rank" to "3") to TilePoint(2048, 2048),
        )))
        val rows = SearchIndexer.candidatesFromTile(14, Z14_TILE.first, Z14_TILE.second, bytes)
        assertTrue(rows.addresses.isEmpty())
        assertEquals(1, rows.places.size)
    }
}