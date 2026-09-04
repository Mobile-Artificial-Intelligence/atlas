package com.danemadsen.atlas.beerouter.geo

import kotlin.math.roundToInt

private const val COORDINATE_SCALE = 1e-6
private const val LONGITUDE_OFFSET = 180.0
private const val LATITUDE_OFFSET = 90.0
private const val COORDINATE_FACTOR = 1_000_000.0

/**
 * Geographic position stored as E6 integers (~11cm precision).
 * Longitude: 0..360_000_000 (offset by +180°)
 * Latitude:  0..180_000_000 (offset by +90°)
 * Altitude:  quarter meters (-8,191.75m to +8,191.75m), with Short.MIN_VALUE reserved for unset.
 */
public data class Position(
    public val longitude: Int,
    public val latitude: Int,
    public val altitude: Short = UNSET_ELEVATION,
) {
    /** Unique ID derived from coordinates. Upper 32 bits = longitude, lower 32 bits = latitude. */
    public val id: Long = computeId(longitude, latitude)

    /**
     * @throws IllegalArgumentException if [longitude] or [latitude] is NaN
     */
    public constructor(longitude: Double, latitude: Double) : this(
        longitude = encodeCoordinate(longitude, LONGITUDE_OFFSET),
        latitude = encodeCoordinate(latitude, LATITUDE_OFFSET),
        altitude = UNSET_ELEVATION,
    )

    public val longitudeDegree: Double
        get() = longitude * COORDINATE_SCALE - LONGITUDE_OFFSET

    public val latitudeDegree: Double
        get() = latitude * COORDINATE_SCALE - LATITUDE_OFFSET

    public companion object {
        public val ZERO: Position = Position(longitude = 0, latitude = 0)

        public fun computeId(longitude: Int, latitude: Int): Long =
            (longitude.toLong() shl 32) or (latitude.toLong() and 0xFFFFFFFFL)

        /**
         * @throws IllegalArgumentException if [longitude] or [latitude] is NaN
         */
        public fun fromDegrees(longitude: Double, latitude: Double, altitude: Short = UNSET_ELEVATION): Position =
            Position(
                longitude = encodeCoordinate(longitude, LONGITUDE_OFFSET),
                latitude = encodeCoordinate(latitude, LATITUDE_OFFSET),
                altitude = altitude,
            )

        private fun encodeCoordinate(value: Double, offset: Double): Int =
            ((value + offset) * COORDINATE_FACTOR).roundToInt()
    }
}

public const val UNSET_ELEVATION: Short = Short.MIN_VALUE

public fun Long.longitudeFromId(): Int = (this shr 32).toInt()

public fun Long.latitudeFromId(): Int = (this and 0xffffffffL).toInt()

public fun Long.toPosition(): Position = Position(longitudeFromId(), latitudeFromId())

public fun Position.withAltitude(altitude: Short): Position =
    if (this.altitude == altitude) this else Position(longitude, latitude, altitude)
