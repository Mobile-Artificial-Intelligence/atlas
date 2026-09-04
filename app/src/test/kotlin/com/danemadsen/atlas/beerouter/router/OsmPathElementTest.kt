package com.danemadsen.atlas.beerouter.router

import com.danemadsen.atlas.beerouter.geo.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class OsmPathElementTest {
    @Test
    fun constructorStoresPositionOriginAndStableId() {
        val origin = OsmPathElement(Position(1, 2, 3), null)
        val position = Position(4, 5, 6)

        val element = OsmPathElement(position, origin)

        assertSame(position, element.position)
        assertSame(origin, element.origin)
        assertEquals(position.id, element.idFromPos)
        assertEquals(position.altitude, element.altitude)
    }

    @Test
    fun idTracksPositionCoordinateChanges() {
        val element = OsmPathElement(Position(1, 2, 3), null)

        element.position = Position(4, 5, 6)

        assertEquals(Position(4, 5, 6).id, element.idFromPos)
        assertEquals(6, element.altitude)
    }
}
