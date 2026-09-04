/**
 * Container for an osm node
 *
 * @author ab
 */
package com.danemadsen.atlas.beerouter.router

import com.danemadsen.atlas.beerouter.geo.CheapRuler.distance
import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.geo.coordinateScaleAt
import com.danemadsen.atlas.beerouter.map.MatchedWaypoint
import com.danemadsen.atlas.beerouter.map.OsmNode
import kotlin.math.sqrt

public open class OsmNodeNamed : OsmNode {
    public var name: String? = null

    public var radius: Double = 0.0 // radius of nogopoint (in meters)
    public var nogoWeight: Double = 0.0 // weight for nogopoint
    public var isNogo: Boolean = false

    public var type: MatchedWaypoint.Type = MatchedWaypoint.Type.SHAPING

    public constructor()

    public constructor(position: Position) : super(position)

    public constructor(n: OsmNode) : super(Position(n.position.longitude, n.position.latitude, n.altitude))

    override fun toString(): String =
        if (nogoWeight.isNaN()) "${position.longitude},${position.latitude},$name"
        else "${position.longitude},${position.latitude},$name,$nogoWeight"

    public fun distanceWithinRadius(
        start: Position,
        end: Position,
        totalSegmentLength: Double
    ): Double {
        val center = position
        var currentStart = start
        var currentEnd = end
        val scale = coordinateScaleAt((currentStart.latitude + currentEnd.latitude) shr 1)

        var isFirstPointWithinCircle = distance(currentStart, center) < radius
        var isLastPointWithinCircle = distance(currentEnd, center) < radius
        // First point is within the circle
        if (isFirstPointWithinCircle) {
            // Last point is within the circle
            if (isLastPointWithinCircle) {
                return totalSegmentLength
            }
            // Last point is not within the circle
            // Just swap points and go on with first first point not within the
            // circle now.
            // Swap positions
            val temp = currentEnd
            currentEnd = currentStart
            currentStart = temp

            // Fix boolean values
            isLastPointWithinCircle = isFirstPointWithinCircle
            isFirstPointWithinCircle = false
        }
        // Distance between the initial point and projection of center of
        // the circle on the current segment.
        val initialToProject: Double =
            (((currentEnd.longitude - currentStart.longitude) * (center.longitude - currentStart.longitude) * scale.longitudeToMeters * scale.longitudeToMeters
                    + (currentEnd.latitude - currentStart.latitude) * (center.latitude - currentStart.latitude) * scale.latitudeToMeters * scale.latitudeToMeters
                    ) / totalSegmentLength)
        // Distance between the initial point and the center of the circle.
        val initialToCenter = distance(center, currentStart)
        // Half length of the segment within the circle
        val halfDistanceWithin = sqrt(
            radius * radius - (initialToCenter * initialToCenter -
                    initialToProject * initialToProject
                    )
        )
        // Last point is within the circle
        return if (isLastPointWithinCircle) {
            halfDistanceWithin + (totalSegmentLength - initialToProject)
        } else {
            2 * halfDistanceWithin
        }
    }

    public companion object {
        /**
         * @throws IllegalArgumentException if the input string does not contain at least three comma-separated parts
         */
        public fun decodeNogo(s: String): OsmNodeNamed {
            val parts = s.split(',', limit = 4)
            require(parts.size >= 3) { "invalid nogo format: $s" }

            val lon = parts[0].toInt()
            val lat = parts[1].toInt()
            val name = parts[2]
            val nogoWeight = parts.getOrNull(3)?.toDouble() ?: Double.NaN

            return OsmNodeNamed().apply {
                this.name = name
                this.nogoWeight = nogoWeight
                isNogo = true
                position = Position(lon, lat)
            }
        }
    }
}
