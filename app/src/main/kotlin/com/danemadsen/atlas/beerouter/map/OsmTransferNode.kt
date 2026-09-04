/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package com.danemadsen.atlas.beerouter.map

import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.geo.UNSET_ELEVATION

public class OsmTransferNode {
    public var next: OsmTransferNode? = null
    public var longitude: Int = 0
    public var latitude: Int = 0
    public var altitude: Short = UNSET_ELEVATION

    public val idFromPos: Long
        get() = Position.computeId(longitude, latitude)

    public fun set(longitude: Int, latitude: Int, altitude: Short): Unit {
        this.longitude = longitude
        this.latitude = latitude
        this.altitude = altitude
    }

    public fun toPosition(): Position = Position(longitude, latitude, altitude)
}
