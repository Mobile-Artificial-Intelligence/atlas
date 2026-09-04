package com.danemadsen.atlas.beerouter.map

import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.geo.clampEncodedAltitude
import com.danemadsen.atlas.beerouter.geo.encodedAltitudeToMeters
import com.danemadsen.atlas.beerouter.geo.encodeAltitudeMeters
import com.danemadsen.atlas.beerouter.geo.toDoubleLatitude
import com.danemadsen.atlas.beerouter.geo.toDoubleLongitude
import com.danemadsen.atlas.beerouter.geo.toIntLatitude
import com.danemadsen.atlas.beerouter.geo.toIntLongitude
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CoordinateConversionTest {

    @Test
    fun testCoordinateConversionAccuracy() {
        // Test various coordinate values to ensure round-trip conversion works
        val testCases = listOf(
            Pair(0, 0),           // Origin
            Pair(1000000, 1000000), // Small positive values
            Pair(-1000000, -1000000), // Small negative values
            Pair(180000000, 90000000), // Max longitude/latitude
            Pair(-180000000, -90000000), // Min longitude/latitude
            Pair(45000000, 45000000), // Mid-range values
            Pair(12345678, 87654321), // Random values
        )

        for ((lon, lat) in testCases) {
            // Test integer to double to integer conversion using original formula
            val doubleLon = lon.toDoubleLongitude()
            val doubleLat = lat.toDoubleLatitude()

            val backToIntLon = doubleLon.toIntLongitude()
            val backToIntLat = doubleLat.toIntLatitude()

            // Allow for small rounding errors (typically within 1 unit due to floating point precision)
            assertEquals(
                lon,
                backToIntLon,
                message = "Longitude conversion should be accurate for $lon"
            )
            assertEquals(
                lat,
                backToIntLat,
                message = "Latitude conversion should be accurate for $lat"
            )
        }
    }

    @Test
    fun testPositionBasedConversion() {
        // Test the conversion using Position-based approach
        val testCases = listOf(
            Pair(0, 0),
            Pair(1000000, 1000000),
            Pair(-1000000, -1000000),
            Pair(180000000, 90000000),
            Pair(-180000000, -90000000),
            Pair(45000000, 45000000),
            Pair(12345678, 87654321),
        )

        for ((lon, lat) in testCases) {
            // Create an OsmNode with Position coordinates using conversion functions
            val position = Position(
                lon.toDoubleLongitude(),
                lat.toDoubleLatitude()
            )
            val node = OsmNode(position)

            // Check that the derived iLat/iLon properties match the original values (with tolerance for precision)
            assertEquals(lon, node.position.longitude, message = "iLon should match original")
            assertEquals(lat, node.position.latitude, message = "iLat should match original")

            // Check that the position is calculated correctly
            val expectedLon = lon.toDoubleLongitude()
            val expectedLat = lat.toDoubleLatitude()

            assertEquals(
                expectedLon,
                node.position.longitudeDegree,
                1e-6,
                "Longitude should be calculated correctly"
            )
            assertEquals(
                expectedLat,
                node.position.latitudeDegree,
                1e-6,
                "Latitude should be calculated correctly"
            )
        }
    }

    @Test
    fun testPositionToIntegerConversion() {
        // Test conversion from Position coordinates back to integers
        val testCases = listOf(
            Pair(0.0, 0.0),
            Pair(1.0, 1.0),
            Pair(-1.0, -1.0),
            Pair(180.0, 90.0),
            Pair(-180.0, -90.0),
            Pair(45.5, 45.5),
            Pair(12.345678, 87.987654),
        )

        for ((lon, lat) in testCases) {
            // Create an OsmNode with Position coordinates
            val node = OsmNode()
            node.position = Position(lon, lat)

            // Check that the derived iLat/iLon properties are calculated correctly using conversion functions
            val expectedILon = lon.toIntLongitude()
            val expectedILat = lat.toIntLatitude()

            assertEquals(
                expectedILon,
                node.position.longitude,
                message = "iLon should be calculated correctly from longitude $lon"
            )
            assertEquals(
                expectedILat,
                node.position.latitude,
                message = "iLat should be calculated correctly from latitude $lat"
            )
        }
    }

    @Test
    fun testExtremeCoordinateValues() {
        // Test extreme values to ensure conversion works across the full range
        val extremeCases = listOf(
            Pair(-180000000, -90000000), // Minimum possible values
            Pair(180000000, 90000000),   // Maximum possible values
            Pair(0, 90000000),           // North pole
            Pair(0, -90000000),          // South pole
            Pair(180000000, 0),          // Date line
            Pair(-180000000, 0),         // Date line opposite
        )

        for ((lon, lat) in extremeCases) {
            val position = Position(
                lon.toDoubleLongitude(),
                lat.toDoubleLatitude()
            )
            val node = OsmNode(position)

            assertEquals(
                lon,
                node.position.longitude,
                message = "iLon should match original for extreme value"
            )
            assertEquals(
                lat,
                node.position.latitude,
                message = "iLat should match original for extreme value"
            )
        }
    }

    @Test
    fun testConversionSymmetry() {
        // Test that conversion is symmetric (int -> Position -> int gives same result)
        val testCases = listOf(
            Pair(0, 0),
            Pair(1000000, 1000000),
            Pair(-1000000, -1000000),
            Pair(180000000, 90000000),
            Pair(-180000000, -90000000),
            Pair(45000000, 45000000),
        )

        for ((originalLon, originalLat) in testCases) {
            // Step 1: Create node with Position coordinates from integer values using conversion functions
            val position = Position(
                originalLon.toDoubleLongitude(),
                originalLat.toDoubleLatitude()
            )
            val node = OsmNode(position)

            // Step 3: Convert Position coordinates back to integer coordinates using conversion functions
            val resultLon = node.position.longitude
            val resultLat = node.position.latitude

            // Step 4: Check that the integer coordinates match the original (with tolerance for precision)
            assertEquals(
                originalLon,
                resultLon,
                message = "Longitude should be preserved through round-trip"
            )
            assertEquals(
                originalLat,
                resultLat,
                message = "Latitude should be preserved through round-trip"
            )
        }
    }

    @Test
    fun testPositionStoresEncodedElevationAsPrimitiveShort() {
        val position = Position.fromDegrees(12.0, 34.0, encodeAltitudeMeters(123.25))

        assertEquals(493.toShort(), position.altitude)
        assertEquals(Short.MIN_VALUE, Position(12.0, 34.0).altitude)
    }

    @Test
    fun testUnsetElevationUsesSentinel() {
        val position = Position(12.0, 34.0)

        assertEquals(Short.MIN_VALUE, position.altitude)
    }

    @Test
    fun testOutOfRangeAltitudeIsClampedInsteadOfWrapping() {
        val high = encodeAltitudeMeters(8849.0)
        val low = encodeAltitudeMeters(-9000.0)

        assertEquals(Short.MAX_VALUE, high)
        assertEquals((Short.MIN_VALUE + 1).toShort(), low)
    }

    @Test
    fun testAlreadyEncodedAltitudeIsClampedWithoutScalingAgain() {
        assertEquals(493.toShort(), clampEncodedAltitude(493.0))
        assertEquals(Short.MAX_VALUE, clampEncodedAltitude(40000.0))
        assertEquals((Short.MIN_VALUE + 1).toShort(), clampEncodedAltitude(-40000.0))
    }

    @Test
    fun testUnsetElevationCannotBeDecodedAsMeters() {
        assertFailsWith<IllegalArgumentException> {
            encodedAltitudeToMeters(Short.MIN_VALUE)
        }
    }
}
