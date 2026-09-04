/**
 * static helper class for handling datafiles
 *
 * @author ab
 */
package com.danemadsen.atlas.beerouter.router

import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.map.OsmNode

internal class SearchBoundary(n: OsmNode, private val radius: Int, val direction: Int) {
    private enum class Direction(public val id: Int) {
        NORTH(0),
        WEST(1),
        SOUTH(2),
        EAST(3);

        companion object {
            fun fromId(id: Int): Direction =
                entries.getOrElse(id) {
                    throw IllegalArgumentException("undefined direction: $id")
                }
        }
    }

    private data class Bounds(
        val minLon: Int,
        val minLat: Int,
        val maxLon: Int,
        val maxLat: Int,
    ) {
        fun contains(position: Position): Boolean =
            position.longitude in (minLon + 1)..<maxLon &&
                position.latitude > minLat &&
                position.latitude < maxLat
    }

    private val wideBounds: Bounds
    private val narrowBounds: Bounds
    private val p: OsmNode = OsmNode(n.position)
    private val searchDirection: Direction = Direction.fromId(direction)

    /**
     * @param n origin node
     * @param radius Search radius in meters.
     * @param direction search direction
     * @throws IllegalArgumentException if direction is not a valid direction ID
     */
    init {
        val origin = tileOrigin(n.position)

        wideBounds = Bounds(
            minLon = origin.longitude - 5_000_000,
            minLat = origin.latitude - 5_000_000,
            maxLon = origin.longitude + 10_000_000,
            maxLat = origin.latitude + 10_000_000,
        )
        narrowBounds = Bounds(
            minLon = origin.longitude - 1_000_000,
            minLat = origin.latitude - 1_000_000,
            maxLon = origin.longitude + 6_000_000,
            maxLat = origin.latitude + 6_000_000,
        )
    }

    fun isInBoundary(n: OsmNode, cost: Int): Boolean {
        if (radius > 0) {
            return n.distanceTo(p) < radius
        }
        return if (cost == 0) wideBounds.contains(n.position) else narrowBounds.contains(n.position)
    }

    fun getBoundaryDistance(n: OsmNode): Int {
        return when (searchDirection) {
            Direction.NORTH -> n.distanceTo(OsmNode(n.position.longitude, narrowBounds.minLat))
            Direction.WEST -> n.distanceTo(OsmNode(narrowBounds.minLon, n.position.latitude))
            Direction.SOUTH -> n.distanceTo(OsmNode(n.position.longitude, narrowBounds.maxLat))
            Direction.EAST -> n.distanceTo(OsmNode(narrowBounds.maxLon, n.position.latitude))
        }
    }

    companion object {
        private fun tileOrigin(position: Position): Position =
            Position(
                longitude = (position.longitude / 5_000_000) * 5_000_000,
                latitude = (position.latitude / 5_000_000) * 5_000_000,
            )

        fun getFileName(n: OsmNode): String {
            val origin = tileOrigin(n.position)
            val dlon = origin.longitude / 1_000_000 - 180
            val dlat = origin.latitude / 1_000_000 - 90

            val slon = if (dlon < 0) "W${-dlon}" else "E$dlon"
            val slat = if (dlat < 0) "S${-dlat}" else "N$dlat"
            return "${slon}_${slat}.trf"
        }
    }
}
