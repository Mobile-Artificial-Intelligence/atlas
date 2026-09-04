package com.danemadsen.atlas.beerouter.map

import com.danemadsen.atlas.beerouter.geo.Position
import kotlin.test.Test
import kotlin.test.assertEquals

class TurnRestrictionTest {
    @Test
    fun createFromCoordinatesComputesTheSameIdsAsPositions() {
        val from = Position(1, 2)
        val to = Position(3, 4)

        val restriction = TurnRestriction.create(
            isPositive = true,
            exceptions = 7,
            fromLongitude = from.longitude,
            fromLatitude = from.latitude,
            toLongitude = to.longitude,
            toLatitude = to.latitude,
        )

        assertEquals(true, restriction.isPositive)
        assertEquals(7, restriction.exceptions)
        assertEquals(from.id, restriction.fromId)
        assertEquals(to.id, restriction.toId)
    }
}
