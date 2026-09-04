package com.danemadsen.atlas.beerouter.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

public data class AngleMeasurement(
    public val angle: Double,
    public val cosAngle: Double,
)

public object CheapAngleMeter {
    private const val RAD_TO_DEG = 180.0 / PI

    public fun turnAngle(
        lon0: Int,
        lat0: Int,
        lon1: Int,
        lat1: Int,
        lon2: Int,
        lat2: Int,
    ): AngleMeasurement {
        val idx = cacheIndex(lat1)
        val lon2m = kxCache[idx]
        val lat2m = kyCache[idx]

        val dx10 = (lon1 - lon0) * lon2m
        val dy10 = (lat1 - lat0) * lat2m
        val dx21 = (lon2 - lon1) * lon2m
        val dy21 = (lat2 - lat1) * lat2m

        val dd = sqrt((dx10 * dx10 + dy10 * dy10) * (dx21 * dx21 + dy21 * dy21))
        if (dd == 0.0) return AngleMeasurement(angle = 0.0, cosAngle = 1.0)

        val ddInv = 1.0 / dd
        var sinp = (dy10 * dx21 - dx10 * dy21) * ddInv
        val cosp = (dy10 * dy21 + dx10 * dx21) * ddInv

        var offset = 0.0
        var s2 = sinp * sinp
        if (s2 > 0.5) {
            if (sinp > 0.0) {
                offset = 90.0
                sinp = -cosp
            } else {
                offset = -90.0
                sinp = cosp
            }
            s2 = cosp * cosp
        } else if (cosp < 0.0) {
            sinp = -sinp
            offset = if (sinp > 0.0) -180.0 else 180.0
        }

        return AngleMeasurement(
            angle = offset + sinp * (57.4539 + s2 * (9.57565 + s2 * (4.30904 + s2 * 2.56491))),
            cosAngle = cosp,
        )
    }

    public fun turnAngle(p0: Position, p1: Position, p2: Position): AngleMeasurement =
        turnAngle(p0.longitude, p0.latitude, p1.longitude, p1.latitude, p2.longitude, p2.latitude)

    public fun rawBearing(startLon: Int, startLat: Int, endLon: Int, endLat: Int): Double {
        val xdiff = (endLat - startLat).toDouble()
        val ydiff = (endLon - startLon).toDouble()
        return atan2(ydiff, xdiff) * RAD_TO_DEG
    }

    public fun rawBearing(start: Position, end: Position): Double =
        rawBearing(start.longitude, start.latitude, end.longitude, end.latitude)

    public fun bearing(p1: Position, p2: Position): Double =
        normalizeAngle(rawBearing(p1, p2))

    public fun normalizeAngle(angle: Double): Double {
        val r = angle % 360.0
        return if (r < 0.0) r + 360.0 else r
    }

    public fun bearingDifference(b1: Double, b2: Double): Double {
        var r = (b2 - b1) % 360.0
        if (r < -180.0) r += 360.0
        if (r >= 180.0) r -= 360.0
        return abs(r)
    }

    public fun measureAngle(lon0: Int, lat0: Int, lon1: Int, lat1: Int, lon2: Int, lat2: Int): Double =
        turnAngle(lon0, lat0, lon1, lat1, lon2, lat2).angle
}
