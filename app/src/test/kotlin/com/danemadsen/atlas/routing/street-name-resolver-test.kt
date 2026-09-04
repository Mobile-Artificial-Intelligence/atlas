package com.danemadsen.atlas.routing

import com.danemadsen.atlas.pmtiles.PmtilesReader
import com.danemadsen.atlas.pmtiles.mvt.MvtGeomType
import com.danemadsen.atlas.pmtiles.tilePointToLonLat
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Street names resolved from the archive's `transportation_name` layer:
 * the road the post-turn route stretch lies on wins, sampling stops at the
 * next turn's junction, and nothing matches within the 30 m threshold
 * keeps the engine's nameless turn.
 */
class StreetNameResolverTest {

    // ---- minimal MVT writer (same encoding the decoder test uses) ----

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

    /** A horizontal linestring at [y] from x [x0] to [x1], extent 4096. */
    private fun horizontalGeometry(y: Int, x0: Int, x1: Int): List<Int> {
        require(x1 > x0)
        return listOf(
            (1 shl 3) or 1, // MoveTo ×1 — command is (count << 3) | id
            zigzag(x0), zigzag(y),
            (1 shl 3) or 2, // LineTo ×1
            zigzag(x1 - x0), 0,
        )
    }

    private fun featureBytes(name_key_index: Int, name_value_index: Int, geometry: List<Int>): ByteArray {
        val tags = packedVarintField(2, listOf(name_key_index, name_value_index))
        val type = varintField(3, MvtGeomType.LINESTRING.code.toLong())
        val geom = packedVarintField(4, geometry)
        return tags + type + geom
    }

    /** One named road: label plus local-geometry extent within the test tile. */
    private data class Road(val name: String, val y: Int, val x0: Int, val x1: Int)

    /**
     * A `transportation_name` tile over the named roads given as
     * (name, y, x0, x1) local-geometry rows.
     */
    private fun nameTile(roads: List<Road>): ByteArray {
        val version = varintField(15, 2L)
        val name = lenDelimited(1, "transportation_name".toByteArray(Charsets.UTF_8))
        val key0 = lenDelimited(3, "name".toByteArray(Charsets.UTF_8))
        // Value indices are sequential, so feature i references value i.
        val features = roads.mapIndexed { index, road ->
            lenDelimited(2, featureBytes(0, index, horizontalGeometry(road.y, road.x0, road.x1)))
        }
        val values = roads.map { road ->
            lenDelimited(4, lenDelimited(1, road.name.toByteArray(Charsets.UTF_8)))
        }.reduce { acc, bytes -> acc + bytes }
        val layer = (listOf(version, name) + features + listOf(key0, values, varintField(5, 4096L)))
            .reduce { acc, bytes -> acc + bytes }
        return lenDelimited(3, layer)
    }

    // ---- test world ----

    private val zoom = 14
    private val tile_x = 14789
    private val tile_y = 10051
    private val extent = 4096

    /** Local (x, y) in the test tile to route coordinates. */
    private fun point(local_x: Int, local_y: Int): GeoPoint {
        val (lon, lat) = tilePointToLonLat(zoom, tile_x, tile_y, local_x, local_y, extent)
        return GeoPoint(lon, lat)
    }

    private fun turn(command: TurnCommand, point_index: Int): TurnPoint =
        TurnPoint(
            command = command,
            lon = 0.0,
            lat = 0.0,
            pointIndex = point_index,
            distanceFromPreviousMeters = 0.0,
            streetName = null,
        )

    @Test
    fun resolvesTheRoadThePostTurnStretchLiesOn() {
        // Collins Street carries the route east; Bourke Street runs
        // parallel ~38 m north (80 local units ≈ 0.47 m each).
        val tile = nameTile(
            listOf(
                Road("Collins Street", 2000, 500, 3500),
                Road("Bourke Street", 2080, 500, 3500),
            ),
        )
        val points = listOf(
            point(1000, 2000), // the turn junction
            point(1800, 2000),
            point(2500, 2000),
        )
        val turns = listOf(turn(TurnCommand.TURN_LEFT, 0), turn(TurnCommand.ARRIVE, 2))

        val resolved = StreetNameResolver.resolveNames(points, turns) { _, _ -> tile }

        assertEquals("Collins Street", resolved[0].streetName)
        assertNull(resolved[1].streetName, "the arrival turn never carries a street")
    }

    @Test
    fun samplingStopsAtTheNextTurnsJunction() {
        // The route turns north at the second junction onto an unnamed
        // connector that reaches Bourke Street. Sampling past that
        // junction would hand turn 1 Bourke's name; the cap must keep it
        // Collins — the stretch turn 1 actually leads onto.
        val tile = nameTile(
            listOf(
                Road("Collins Street", 2000, 500, 3500),
                Road("Bourke Street", 2080, 500, 3500),
            ),
        )
        val points = listOf(
            point(1000, 2000), // turn 1 junction: east onto Collins
            point(1800, 2000),
            point(2500, 2000), // turn 2 junction: north onto the connector
            point(2500, 2060), // connector rises toward Bourke
            point(2500, 2120),
        )
        val turns = listOf(
            turn(TurnCommand.TURN_LEFT, 0),
            turn(TurnCommand.TURN_RIGHT, 2),
            turn(TurnCommand.ARRIVE, 4),
        )

        val resolved = StreetNameResolver.resolveNames(points, turns) { _, _ -> tile }

        assertEquals("Collins Street", resolved[0].streetName)
        // Turn 2's stretch genuinely runs up to Bourke Street (the unnamed
        // connector is not in the tile), so Bourke is the right name there.
        assertEquals("Bourke Street", resolved[1].streetName)
    }

    @Test
    fun distantRoadsNeverClaimATurn() {
        val tile = nameTile(listOf(Road("Collins Street", 2000, 500, 3500)))
        // The samples sit ~470 m north of every named road.
        val points = listOf(point(1000, 3000), point(1800, 3000))
        val turns = listOf(turn(TurnCommand.TURN_LEFT, 0), turn(TurnCommand.ARRIVE, 1))

        val resolved = StreetNameResolver.resolveNames(points, turns) { _, _ -> tile }

        assertNull(resolved[0].streetName)
    }

    @Test
    fun missingTilesLeaveTheTurnsUntouched() {
        val points = listOf(point(1000, 2000), point(1800, 2000))
        val turns = listOf(turn(TurnCommand.TURN_LEFT, 0), turn(TurnCommand.ARRIVE, 1))

        val resolved = StreetNameResolver.resolveNames(points, turns) { _, _ -> null }

        assertEquals(turns, resolved)
        assertTrue(resolved === turns || resolved == turns)
    }

    /**
     * Real-archive smoke test against the dev-machine fixture (skipped when
     * it isn't present): a turn leading east out of the Collins/Swanston
     * intersection must resolve to the street the route runs along.
     */
    @Test
    fun resolvesRealArchiveStreets() {
        val archive = File(System.getProperty("user.home"), "atlas-prototype/tmp/australia.pmtiles")
        assumeTrue("dev fixture australia.pmtiles not present", archive.exists())
        PmtilesReader.open(archive.absolutePath).use { reader ->
            // East along the archive's Collins Street linework from Swanston
            // (its z14 vertices run 144.96562,-37.81581 → 144.96895,-37.81483;
            // the turn junction, then ~4 points on the far side).
            val points = listOf(
                GeoPoint(144.96550, -37.81583), // the junction itself
                GeoPoint(144.96562, -37.81581),
                GeoPoint(144.96600, -37.81571),
                GeoPoint(144.96650, -37.81557),
                GeoPoint(144.96700, -37.81542),
            )
            val turns = listOf(turn(TurnCommand.TURN_LEFT, 0), turn(TurnCommand.ARRIVE, 4))

            val resolved = StreetNameResolver.resolveNames(reader, points, turns)

            println("real-archive Collins Street turn -> ${resolved[0].streetName}")
            assertEquals("Collins Street", resolved[0].streetName)
        }
    }
}