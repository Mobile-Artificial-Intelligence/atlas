package com.danemadsen.atlas.beerouter.map

import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.geo.UNSET_ELEVATION
import kotlin.test.Test
import kotlin.test.assertEquals

class OsmTransferNodeTest {
    @Test
    fun storesCoordinatesAndElevationAsPrimitiveFields() {
        val node = OsmTransferNode()

        node.set(longitude = 123, latitude = 456, altitude = 789)

        assertEquals(123, node.longitude)
        assertEquals(456, node.latitude)
        assertEquals(789.toShort(), node.altitude)
        assertEquals(Position.computeId(123, 456), node.idFromPos)
        assertEquals(Position(123, 456, 789), node.toPosition())
    }

    @Test
    fun defaultsElevationToUnsetSentinel() {
        val node = OsmTransferNode()

        assertEquals(UNSET_ELEVATION, node.altitude)
    }
}
