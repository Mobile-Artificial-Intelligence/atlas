package com.danemadsen.atlas.beerouter.util

import com.danemadsen.atlas.beerouter.geo.CheapAngleMeter.bearing
import com.danemadsen.atlas.beerouter.geo.CheapAngleMeter.bearingDifference
import com.danemadsen.atlas.beerouter.geo.CheapAngleMeter.normalizeAngle
import com.danemadsen.atlas.beerouter.geo.CheapAngleMeter.rawBearing
import com.danemadsen.atlas.beerouter.geo.CheapAngleMeter.turnAngle
import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.geo.coordinateScaleAt
import com.danemadsen.atlas.beerouter.geo.toIntLatitude
import com.danemadsen.atlas.beerouter.geo.toIntLongitude
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

class CheapAngleMeterTest {
    @Test
    fun testTurnAngle() {
        // Segment ends
        var lon0: Int
        var lat0: Int
        var lon1: Int
        var lat1: Int
        var lon2: Int
        var lat2: Int

        lon0 = 2.317126.toIntLongitude()
        lat0 = 48.817927.toIntLatitude()
        lon1 = 2.317316.toIntLongitude()
        lat1 = 48.817978.toIntLatitude()
        lon2 = 2.317471.toIntLongitude()
        lat2 = 48.818043.toIntLatitude()
        assertEquals(
            -10.0,
            turnAngle(Position(lon0, lat0), Position(lon1, lat1), Position(lon2, lat2)).angle,
            0.05 * 10.0
        )

        lon0 = 2.317020662874013.toIntLongitude()
        lat0 = 48.81799440182911.toIntLatitude()
        lon1 = 2.3169460585876327.toIntLongitude()
        lat1 = 48.817812421536644.toIntLatitude()
        lon2 = lon0
        lat2 = lat0
        assertEquals(
            180.0,
            turnAngle(Position(lon0, lat0), Position(lon1, lat1), Position(lon2, lat2)).angle,
            0.05 * 180.0
        )

        lon0 = 2.317112.toIntLongitude()
        lat0 = 48.817802.toIntLatitude()
        lon1 = 2.317632.toIntLongitude()
        lat1 = 48.817944.toIntLatitude()
        lon2 = 2.317673.toIntLongitude()
        lat2 = 48.817799.toIntLatitude()
        assertEquals(
            100.0,
            turnAngle(Position(lon0, lat0), Position(lon1, lat1), Position(lon2, lat2)).angle,
            0.1 * 100.0
        )

        lon0 = 2.317128.toIntLongitude()
        lat0 = 48.818072.toIntLatitude()
        lon1 = 2.317532.toIntLongitude()
        lat1 = 48.818108.toIntLatitude()
        lon2 = 2.317497.toIntLongitude()
        lat2 = 48.818264.toIntLatitude()
        assertEquals(
            -100.0,
            turnAngle(Position(lon0, lat0), Position(lon1, lat1), Position(lon2, lat2)).angle,
            0.1 * 100.0
        )
    }

    @Test
    fun testTurnAngle2() {
        val lon1 = 8500000
        val lat1 = 49500000

        val scale = coordinateScaleAt(lat1)
        val lon2m = scale.longitudeToMeters
        val lat2m = scale.latitudeToMeters

        var afrom = -175.0
        while (afrom < 180.0) {
            val sf = sin(afrom * PI / 180.0)
            val cf = cos(afrom * PI / 180.0)

            val lon0 = (0.5 + lon1 - cf * 150.0 / lon2m).toInt()
            val lat0 = (0.5 + lat1 - sf * 150.0 / lat2m).toInt()

            var ato = -177.0
            while (ato < 180.0) {
                val st = sin(ato * PI / 180.0)
                val ct = cos(ato * PI / 180.0)

                val lon2 = (0.5 + lon1 + ct * 250.0 / lon2m).toInt()
                val lat2 = (0.5 + lat1 + st * 250.0 / lat2m).toInt()

                var a1 = afrom - ato
                if (a1 > 180.0) a1 -= 360.0
                if (a1 < -180.0) a1 += 360.0
                val angleMeasurement = turnAngle(
                    Position(lon0, lat0),
                    Position(lon1, lat1),
                    Position(lon2, lat2)
                )
                val c1 = cos(a1 * PI / 180.0)
                val c2 = angleMeasurement.cosAngle

                assertEquals(a1, angleMeasurement.angle, 0.2)
                assertEquals(c1, c2, 0.001)
                ato += 10.0
            }
            afrom += 10.0
        }
    }

    @Test
    fun testRawBearing() {
        var lon1: Int
        var lat1: Int
        var lon2: Int
        var lat2: Int

        lon1 = 10.0.toIntLongitude()
        lat1 = 50.0.toIntLatitude()
        lon2 = 10.0.toIntLongitude()
        lat2 = 60.0.toIntLatitude()

        var angle = rawBearing(Position(lon1, lat1), Position(lon2, lat2))
        assertEquals(0.0, angle, 0.0)

        lon2 = 10.0.toIntLongitude()
        lat2 = 40.0.toIntLatitude()
        angle = rawBearing(Position(lon1, lat1), Position(lon2, lat2))
        assertEquals(180.0, angle, 0.0)

        lon2 = 0.0.toIntLongitude()
        lat2 = 50.0.toIntLatitude()
        angle = rawBearing(Position(lon1, lat1), Position(lon2, lat2))
        assertEquals(-90.0, angle, 0.0)

        lon2 = 20.0.toIntLongitude()
        lat2 = 50.0.toIntLatitude()
        angle = rawBearing(Position(lon1, lat1), Position(lon2, lat2))
        assertEquals(90.0, angle, 0.0)

        lon2 = 20.0.toIntLongitude()
        lat2 = 60.0.toIntLatitude()
        angle = rawBearing(Position(lon1, lat1), Position(lon2, lat2))
        assertEquals(45.0, angle, 0.0)

        lon2 = 0.0.toIntLongitude()
        lat2 = 60.0.toIntLatitude()
        angle = rawBearing(Position(lon1, lat1), Position(lon2, lat2))
        assertEquals(-45.0, angle, 0.0)

        lon1 = 1
        lat1 = 1
        lon2 = 2
        lat2 = 2
        angle = rawBearing(Position(lon1, lat1), Position(lon2, lat2))
        assertEquals(45.0, angle, 0.0)
    }

    @Test
    fun testBearing() {
        var lon1: Int
        var lat1: Int
        var lon2: Int
        var lat2: Int

        lon1 = 10.0.toIntLongitude()
        lat1 = 50.0.toIntLatitude()
        lon2 = 10.0.toIntLongitude()
        lat2 = 60.0.toIntLatitude()

        var angle = bearing(Position(lon1, lat1), Position(lon2, lat2))
        assertEquals(0.0, angle, 0.0)

        lon2 = 10.0.toIntLongitude()
        lat2 = 40.0.toIntLatitude()
        angle = bearing(Position(lon1, lat1), Position(lon2, lat2))
        assertEquals(180.0, angle, 0.0)

        lon2 = 0.0.toIntLongitude()
        lat2 = 50.0.toIntLatitude()
        angle = bearing(Position(lon1, lat1), Position(lon2, lat2))
        assertEquals(270.0, angle, 0.0)

        lon2 = 20.0.toIntLongitude()
        lat2 = 50.0.toIntLatitude()
        angle = bearing(Position(lon1, lat1), Position(lon2, lat2))
        assertEquals(90.0, angle, 0.0)

        lon2 = 20.0.toIntLongitude()
        lat2 = 60.0.toIntLatitude()
        angle = bearing(Position(lon1, lat1), Position(lon2, lat2))
        assertEquals(45.0, angle, 0.0)

        lon2 = 0.0.toIntLongitude()
        lat2 = 60.0.toIntLatitude()
        angle = bearing(Position(lon1, lat1), Position(lon2, lat2))
        assertEquals(315.0, angle, 0.0)

        lon1 = 1
        lat1 = 1
        lon2 = 2
        lat2 = 2
        angle = bearing(Position(lon1, lat1), Position(lon2, lat2))
        assertEquals(45.0, angle, 0.0)
    }

    @Test
    fun testNormalize() {
        var n = 1.0
        assertEquals(1.0, normalizeAngle(n), 0.0)

        n = -1.0
        assertEquals(359.0, normalizeAngle(n), 0.0)

        n = 361.0
        assertEquals(1.0, normalizeAngle(n), 0.0)

        n = 0.0
        assertEquals(0.0, normalizeAngle(n), 0.0)

        n = 360.0
        assertEquals(0.0, normalizeAngle(n), 0.0)
    }

    @Test
    fun testTurnAngle6() {
        var a1 = 90.0
        var a2 = 180.0
        assertEquals(
            90.0,
            bearingDifference(a1, a2),
            0.0
        )

        a1 = 180.0
        a2 = 90.0
        assertEquals(
            90.0,
            bearingDifference(a1, a2),
            0.0
        )

        a1 = 5.0
        a2 = 355.0
        assertEquals(
            10.0,
            bearingDifference(a1, a2),
            0.0
        )

        a1 = 355.0
        a2 = 5.0
        assertEquals(
            10.0,
            bearingDifference(a1, a2),
            0.0
        )

        a1 = 90.0
        a2 = 270.0
        assertEquals(
            180.0,
            bearingDifference(a1, a2),
            0.0
        )

        a1 = 270.0
        a2 = 90.0
        assertEquals(
            180.0,
            bearingDifference(a1, a2),
            0.0
        )
    }
}
