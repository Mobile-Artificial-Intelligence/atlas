package com.danemadsen.atlas.pmtiles.mvt

import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MvtDecoderTest {

    // ---- protobuf test writer ----

    private fun varint(value_in: Long): ByteArray {
        val out = ByteArrayOutputStream()
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
        val packed = ByteArrayOutputStream()
        values.forEach { packed.write(varint(it.toLong())) }
        return lenDelimited(fieldNumber, packed.toByteArray())
    }

    /** Geometry: MoveTo(1,1) to (100,200), LineTo(1,2) via deltas. */
    private fun linestringGeometry(): List<Int> = listOf(
        (1 shl 3) or 1, // MoveTo, 1 pair
        zigzag(100), zigzag(200),
        (2 shl 3) or 2, // LineTo, 2 pairs
        zigzag(50), zigzag(-30),
        zigzag(20), zigzag(10),
    )

    private fun buildTile(): ByteArray {
        // Feature: id=1 (varint), tags (packed), type=LINESTRING, geometry (packed)
        val tags = packedVarintField(2, listOf(0, 0, 1, 1))
        val type = varintField(3, MvtGeomType.LINESTRING.code.toLong())
        val geometry = packedVarintField(4, linestringGeometry())
        val featureBytes = varintField(1, 42L) + tags + type + geometry

        // Layer: version=2, name, features, keys, values, extent
        val version = varintField(15, 2L)
        val name = lenDelimited(1, "transportation".toByteArray(Charsets.UTF_8))
        val features = lenDelimited(2, featureBytes)
        val key0 = lenDelimited(3, "class".toByteArray(Charsets.UTF_8))
        val key1 = lenDelimited(3, "name".toByteArray(Charsets.UTF_8))
        val value0 = lenDelimited(4, lenDelimited(1, "primary".toByteArray(Charsets.UTF_8)))
        val value1 = lenDelimited(4, lenDelimited(1, "Swanston Street".toByteArray(Charsets.UTF_8)))
        val extent = varintField(5, 4096L)

        val layer = version + name + features + key0 + key1 + value0 + value1 + extent
        return lenDelimited(3, layer)
    }

    @Test
    fun decodesLayerFeatureTagsAndGeometry() {
        val tile = MvtTile.decode(buildTile())
        val layer = tile.layer("transportation")

        assertTrue(layer != null, "transportation layer missing")
        layer!!
        assertEquals(2, layer.version)
        assertEquals(4096, layer.extent)
        assertEquals(1, layer.features.size)

        val feature = layer.features[0]
        assertEquals(42L, feature.id)
        assertEquals(MvtGeomType.LINESTRING, feature.geomType)
        assertEquals(
            mapOf("class" to "primary", "name" to "Swanston Street"),
            layer.properties(feature),
        )

        val paths = layer.pathsLocal(feature)
        assertEquals(1, paths.size)
        assertEquals(
            listOf(TilePoint(100, 200), TilePoint(150, 170), TilePoint(170, 180)),
            paths[0],
        )
    }

    @Test
    fun convertsTileLocalToLonLat() {
        val tile = MvtTile.decode(buildTile())
        val layer = tile.layer("transportation")!!
        val feature = layer.features[0]
        // z14 tile covering Melbourne: x=14789, y=10051-ish; use exact z14 math.
        val zoom = 14
        val x = 14789
        val y = 10051
        val paths = layer.pathsLonLat(feature, zoom, x, y)
        val first = paths[0][0]
        // Full-extent local x should map to the tile's east edge, and y=0 to
        // the north edge; (100,200) is near the top-left of the tile.
        val westEdge = (x.toDouble() / (1 shl zoom)) * 360.0 - 180.0
        val tileWidth = 360.0 / (1 shl zoom)
        assertTrue(first.lon > westEdge, "lon ${first.lon} <= west edge $westEdge")
        assertTrue(first.lon < westEdge + tileWidth, "lon ${first.lon} beyond east edge")
        // y=200 of 4096 => near the north edge: lat close to the tile top.
        val northEdgeLat = Math.toDegrees(
            Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * (y.toDouble() / (1 shl zoom))))),
        )
        val southEdgeLat = Math.toDegrees(
            Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * ((y + 1).toDouble() / (1 shl zoom))))),
        )
        assertTrue(
            abs(first.lat - northEdgeLat) < abs(first.lat - southEdgeLat),
            "lat ${first.lat} should be near north edge $northEdgeLat",
        )
    }
}