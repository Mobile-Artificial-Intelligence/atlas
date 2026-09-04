package com.danemadsen.atlas.beerouter.geo

import kotlin.math.roundToInt

private const val COORDINATE_SCALE = 1e-6
private const val LONGITUDE_OFFSET = 180.0
private const val LATITUDE_OFFSET = 90.0
private const val ELEVATION_SCALE = 4.0
private const val MIN_ENCODED_ELEVATION: Int = Short.MIN_VALUE.toInt() + 1
private const val MAX_ENCODED_ELEVATION: Int = Short.MAX_VALUE.toInt()

public fun Double.toIntLongitude(): Int = ((this + LONGITUDE_OFFSET) / COORDINATE_SCALE).roundToInt()

public fun Double.toIntLatitude(): Int = ((this + LATITUDE_OFFSET) / COORDINATE_SCALE).roundToInt()

public fun Int.toDoubleLongitude(): Double = (this * COORDINATE_SCALE) - LONGITUDE_OFFSET

public fun Int.toDoubleLatitude(): Double = (this * COORDINATE_SCALE) - LATITUDE_OFFSET

public fun encodeAltitudeMeters(altitudeMeters: Double): Short =
    if (altitudeMeters.isNaN()) {
        UNSET_ELEVATION
    } else {
        altitudeMeters.times(ELEVATION_SCALE)
            .toInt()
            .coerceIn(MIN_ENCODED_ELEVATION, MAX_ENCODED_ELEVATION)
            .toShort()
    }

public fun clampEncodedAltitude(encodedAltitude: Double): Short =
    if (encodedAltitude.isNaN()) {
        UNSET_ELEVATION
    } else {
        encodedAltitude.toInt()
            .coerceIn(MIN_ENCODED_ELEVATION, MAX_ENCODED_ELEVATION)
            .toShort()
    }

public fun encodedAltitudeToMeters(altitude: Short): Double {
    require(altitude != UNSET_ELEVATION) { "unset elevation cannot be decoded as meters" }
    return altitude / ELEVATION_SCALE
}
