package com.danemadsen.atlas.beerouter.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PositionTest {
    @Test
    fun withAltitudeReusesPositionWhenAltitudeMatches() {
        val position = Position(1, 2, 3)

        assertSame(position, position.withAltitude(3))
    }

    @Test
    fun withAltitudeKeepsCoordinatesAndChangesAltitude() {
        val position = Position(1, 2, 3)

        val adjusted = position.withAltitude(4)

        assertEquals(Position(1, 2, 4), adjusted)
    }
}
