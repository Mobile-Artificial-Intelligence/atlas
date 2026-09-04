package com.danemadsen.atlas.beerouter.geo

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

public data class CoordinateScale(
    public val longitudeToMeters: Double,
    public val latitudeToMeters: Double,
)

private const val ILATLNG_TO_LATLNG: Double = 1e-6
private const val ILATLNG_TO_METERS: Double = ILATLNG_TO_LATLNG * 1000.0
private const val SCALE_CACHE_LENGTH = 1800
private const val SCALE_CACHE_LAST = SCALE_CACHE_LENGTH - 1
private const val SCALE_CACHE_INCREMENT = 100000

private data class ScaleCache(
    val kx: DoubleArray,
    val ky: DoubleArray,
)

internal fun cacheIndex(ilat: Int): Int =
    (ilat / SCALE_CACHE_INCREMENT).coerceIn(0, SCALE_CACHE_LAST)

private fun createScaleCache(): ScaleCache {
    val kxCache = DoubleArray(SCALE_CACHE_LENGTH)
    val kyCache = DoubleArray(SCALE_CACHE_LENGTH)
    for (i in 0 until SCALE_CACHE_LENGTH) {
        val ilat = i * SCALE_CACHE_INCREMENT + SCALE_CACHE_INCREMENT / 2
        val lat = CheapRuler.DEG_TO_RAD * (ilat * ILATLNG_TO_LATLNG - 90)
        val cos = cos(lat)
        val cos2 = 2 * cos * cos - 1
        val cos3 = 2 * cos * cos2 - cos
        val cos4 = 2 * cos * cos3 - cos2
        val cos5 = 2 * cos * cos4 - cos3
        kxCache[i] = (111.41513 * cos - 0.09455 * cos3 + 0.00012 * cos5) * ILATLNG_TO_METERS
        kyCache[i] = (111.13209 - 0.56605 * cos2 + 0.0012 * cos4) * ILATLNG_TO_METERS
    }
    return ScaleCache(kxCache, kyCache)
}

private val scaleCache: ScaleCache by lazy(::createScaleCache)

internal val kxCache: DoubleArray
    get() = scaleCache.kx

internal val kyCache: DoubleArray
    get() = scaleCache.ky

public fun coordinateScaleAt(ilat: Int): CoordinateScale {
    val idx = cacheIndex(ilat)
    return CoordinateScale(kxCache[idx], kyCache[idx])
}

public object CheapRuler {
    public const val DEG_TO_RAD: Double = kotlin.math.PI / 180.0

    public fun distance(lon1: Int, lat1: Int, lon2: Int, lat2: Int): Double {
        val idx = cacheIndex((lat1 + lat2) shr 1)
        val dlon = (lon1 - lon2) * kxCache[idx]
        val dlat = (lat1 - lat2) * kyCache[idx]
        return sqrt(dlat * dlat + dlon * dlon)
    }

    public fun distance(p1: Position, p2: Position): Double =
        distance(p1.longitude, p1.latitude, p2.longitude, p2.latitude)

    public fun destination(originLon: Int, originLat: Int, distance: Double, angle: Double): Position {
        val idx = cacheIndex(originLat)
        val radAngle = (90.0 - angle) * DEG_TO_RAD
        val longitude = (0.5 + originLon + cos(radAngle) * distance / kxCache[idx]).toInt()
        val latitude = (0.5 + originLat + sin(radAngle) * distance / kyCache[idx]).toInt()
        return Position(longitude, latitude)
    }

    public fun destination(origin: Position, distance: Double, angle: Double): Position =
        destination(origin.longitude, origin.latitude, distance, angle)
}
