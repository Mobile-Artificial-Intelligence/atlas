package com.danemadsen.atlas.beerouter.geo

import kotlin.test.Test
import kotlin.test.assertEquals

class PrimitiveGeoHelperTest {
    @Test
    fun primitiveDistanceMatchesPositionDistance() {
        val a = Position(187000000, 139000000)
        val b = Position(190000000, 139200000)

        assertEquals(
            CheapRuler.distance(a, b),
            CheapRuler.distance(a.longitude, a.latitude, b.longitude, b.latitude),
            0.000001,
        )
    }

    @Test
    fun primitiveTurnAngleMatchesPositionTurnAngle() {
        val a = Position(187000000, 139000000)
        val b = Position(188000000, 139100000)
        val c = Position(190000000, 139200000)

        val expected = CheapAngleMeter.turnAngle(a, b, c)
        val actual = CheapAngleMeter.turnAngle(
            a.longitude,
            a.latitude,
            b.longitude,
            b.latitude,
            c.longitude,
            c.latitude,
        )

        assertEquals(expected.angle, actual.angle, 0.000001)
        assertEquals(expected.cosAngle, actual.cosAngle, 0.000001)
    }
}
