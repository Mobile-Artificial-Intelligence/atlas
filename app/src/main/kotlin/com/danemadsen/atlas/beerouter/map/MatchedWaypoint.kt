/**
 * Information on matched way point
 *
 * @author ab
 */
package com.danemadsen.atlas.beerouter.map

import com.danemadsen.atlas.beerouter.geo.Position
import kotlinx.io.Sink
import kotlinx.io.Source

public data class MatchedWaypoint(
    public var node1: OsmNode? = null,
    public var node2: OsmNode? = null,
    public var crosspoint: OsmNode? = null,
    public var waypoint: OsmNode? = null,
    public var correctedpoint: OsmNode? = null,
    public var name: String? = null,
    public var radius: Double = 0.0,
    public var type: Type = Type.SHAPING,
    public var indexInTrack: Int = 0,
    public var directionToNext: Double = -1.0,
    public var directionDiff: Double = 361.0,
    public var hasUpdate: Boolean = false
) {
    public enum class Type {
        /** route next to this point */
        SHAPING,

        /** visit this point  */
        MEETING,

        /** from this point go direct to next = beeline routing  */
        DIRECT
    }

    public val wayNearest: MutableList<MatchedWaypoint> = mutableListOf()

    public fun writeToStream(sink: Sink) {
        sink.writeNode(requireNotNull(node1))
        sink.writeNode(requireNotNull(node2))
        sink.writeNode(requireNotNull(crosspoint))
        sink.writeNode(requireNotNull(waypoint))
        sink.writeLong(radius.toBits())
    }

    public companion object {
        private fun nodeFromCoordinates(position: Position): OsmNode =
            OsmNode(position)

        private fun Source.readNode(): OsmNode {
            val lat = readInt()
            val lon = readInt()
            return nodeFromCoordinates(Position(lon, lat))
        }

        public fun readFromStream(source: Source): MatchedWaypoint {
            return MatchedWaypoint().apply {
                node1 = source.readNode()
                node2 = source.readNode()
                crosspoint = source.readNode()
                waypoint = source.readNode()
                radius = Double.fromBits(source.readLong())
            }
        }
    }
}

private fun Sink.writeNode(node: OsmNode) {
    writeInt(node.position.latitude)
    writeInt(node.position.longitude)
}
