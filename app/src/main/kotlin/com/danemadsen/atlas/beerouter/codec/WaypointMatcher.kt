package com.danemadsen.atlas.beerouter.codec

import com.danemadsen.atlas.beerouter.geo.Position

/**
 * a waypoint matcher gets way geometries
 * from the decoder to find the closest
 * matches to the waypoints
 */
public interface WaypointMatcher {
    public fun start(
        startLon: Int,
        startLat: Int,
        targetLon: Int,
        targetLat: Int,
        useAsStartWay: Boolean
    ): Boolean

    public fun transferNode(lon: Int, lat: Int)

    public fun end()

    public fun hasMatch(lon: Int, lat: Int): Boolean

    public fun start(start: Position, target: Position, useAsStartWay: Boolean): Boolean =
        start(start.longitude, start.latitude, target.longitude, target.latitude, useAsStartWay)

    public fun transferNode(position: Position): Unit = transferNode(position.longitude, position.latitude)

    public fun hasMatch(position: Position): Boolean = hasMatch(position.longitude, position.latitude)
}
