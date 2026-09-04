/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package com.danemadsen.atlas.beerouter.map

import com.danemadsen.atlas.beerouter.util.ByteDataReader

public class GeometryDecoder {
    private val r = ByteDataReader()
    private val cachedNodes: Array<OsmTransferNode> = Array(CACHED_NODE_COUNT) { OsmTransferNode() }

    // result-cache
    private var firstTransferNode: OsmTransferNode? = null
    private var lastReverse = false
    private var lastGeometry: ByteArray? = null

    /**
     * @throws IllegalArgumentException if [sourceNode] is null and [reverseLink] is false
     */
    public fun decodeGeometry(
        geometry: ByteArray,
        sourceNode: OsmNode?,
        targetNode: OsmNode,
        reverseLink: Boolean
    ): OsmTransferNode? {
        if (lastGeometry === geometry && lastReverse == reverseLink) {
            return firstTransferNode
        }

        firstTransferNode = null
        var lastTransferNode: OsmTransferNode? = null
        val startnode: OsmNode = if (reverseLink) targetNode else requireNotNull(sourceNode)
        r.reset(geometry)
        var olon = startnode.longitude
        var olat = startnode.latitude
        var oselev = startnode.altitude.toInt()
        var idx = 0
        while (r.hasMoreData()) {
            val trans = cachedNodes.getOrNull(idx++) ?: OsmTransferNode()
            val lon = olon + r.readVarLengthSigned()
            val lat = olat + r.readVarLengthSigned()
            val selev = (oselev + r.readVarLengthSigned()).toShort()
            trans.set(lon, lat, selev)
            olon = lon
            olat = lat
            oselev = selev.toInt()
            if (reverseLink) { // reverse chaining
                trans.next = firstTransferNode
                firstTransferNode = trans
            } else {
                trans.next = null
                if (lastTransferNode == null) {
                    firstTransferNode = trans
                } else {
                    lastTransferNode.next = trans
                }
                lastTransferNode = trans
            }
        }

        lastReverse = reverseLink
        lastGeometry = geometry

        return firstTransferNode
    }

    private companion object {
        private const val CACHED_NODE_COUNT = 128
    }
}
