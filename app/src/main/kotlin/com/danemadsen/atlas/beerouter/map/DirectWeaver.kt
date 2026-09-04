package com.danemadsen.atlas.beerouter.map

import com.danemadsen.atlas.beerouter.codec.DataBuffers
import com.danemadsen.atlas.beerouter.codec.NoisyDiffCoder
import com.danemadsen.atlas.beerouter.codec.StatCoderContext
import com.danemadsen.atlas.beerouter.codec.TagValueCoder
import com.danemadsen.atlas.beerouter.codec.TagValueValidator
import com.danemadsen.atlas.beerouter.codec.WaypointMatcher
import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.geo.latitudeFromId
import com.danemadsen.atlas.beerouter.geo.longitudeFromId
import com.danemadsen.atlas.beerouter.util.ByteDataWriter

/**
 * DirectWeaver does the same decoding as MicroCache2, but decodes directly
 * into the instance-graph, not into the intermediate nodes-cache
 */
public class DirectWeaver(
    bc: StatCoderContext,
    dataBuffers: DataBuffers,
    lonIdx: Int,
    latIdx: Int,
    divisor: Int,
    wayValidator: TagValueValidator,
    waypointMatcher: WaypointMatcher?,
    hollowNodes: OsmNodesMap
) : ByteDataWriter() {
    private val id64Base: Long

    init {
        val cellsize = 1000000 / divisor
        id64Base = Position.computeId(lonIdx * cellsize, latIdx * cellsize)

        var nodes = dataBuffers.objectBuffer1
        var clearLimit = nodes.size
        try {
            val wayTagCoder = TagValueCoder(bc, dataBuffers, wayValidator)
            val nodeTagCoder = TagValueCoder.rawDataDecoder(bc, dataBuffers, null)
            val nodeIdxDiff = NoisyDiffCoder(bc)
            val nodeEleDiff = NoisyDiffCoder(bc)
            val extLonDiff = NoisyDiffCoder(bc)
            val extLatDiff = NoisyDiffCoder(bc)
            val transEleDiff = NoisyDiffCoder(bc)

            val size = bc.decodeNoisyNumber(5)
            clearLimit = size

            val faid = if (size > dataBuffers.ibuf2.size) IntArray(size) else dataBuffers.ibuf2

            bc.decodeSortedArray(faid, 0, size, 29, 0)

            if (size > dataBuffers.objectBuffer1.size) {
                dataBuffers.objectBuffer1 = arrayOfNulls(size)
            }
            nodes = dataBuffers.objectBuffer1
            val nodeElevations = ShortArray(size)
            val nodeDescriptions = arrayOfNulls<ByteArray>(size)
            val nodeRestrictions = arrayOfNulls<TurnRestriction>(size)

            nodes.fill(null, 0, size)
            for (n in 0..<size) {
                val id = expandId(faid[n])
                var node = hollowNodes.get(id)
                if (node != null) {
                    node.visitID = 1
                    hollowNodes.remove(node)
                    nodes[n] = node
                }
            }

            val netdatasize = bc.decodeNoisyNumber(10) // (not needed for direct weaving)
            ab = dataBuffers.bbuf1
            aboffset = 0

            var selev = 0
            for (n in 0..<size) { // loop over nodes
                var node = nodes[n] as OsmNode?
                val nodeId = expandId(faid[n])
                val nodeLongitude = nodeId.longitudeFromId()
                val nodeLatitude = nodeId.latitudeFromId()

            // future escapes (turn restrictions?)
            var trExceptions: Short = 0
            var firstRestriction: TurnRestriction? = null
            while (true) {
                val featureId = bc.decodeVarBits()
                if (featureId == 0) break
                val bitsize = bc.decodeNoisyNumber(5)

                when (featureId) {
                    2 -> { // exceptions to turn-restriction
                        trExceptions = bc.decodeBounded(1023).toShort()
                    }

                    1 -> { // turn-restriction
                        val exceptions = trExceptions
                        trExceptions = 0
                        val isPositive = bc.decodeBit()
                        val fromLon = nodeLongitude + bc.decodeNoisyDiff(10)
                        val fromLat = nodeLatitude + bc.decodeNoisyDiff(10)
                        val toLon = nodeLongitude + bc.decodeNoisyDiff(10)
                        val toLat = nodeLatitude + bc.decodeNoisyDiff(10)
                        val fromId = Position.computeId(fromLon, fromLat)
                        val toId = Position.computeId(toLon, toLat)
                        firstRestriction = TurnRestriction(
                            isPositive = isPositive,
                            exceptions = exceptions,
                            fromId = fromId,
                            toId = toId,
                            next = firstRestriction,
                        )
                    }

                    else -> {
                        for (i in 0..<bitsize) bc.decodeBit() // unknown feature, just skip
                    }
                }
            }

            selev += nodeEleDiff.decodeSignedValue()
            val nodeElevation = selev.toShort()
            val nodeDescription = nodeTagCoder.decodeTagValueData() // TODO: unified?
            nodeElevations[n] = nodeElevation
            nodeDescriptions[n] = nodeDescription
            nodeRestrictions[n] = firstRestriction
            var nodeDataPending = true
            if (node != null) {
                nodeDataPending = false
                applyNodeData(node, nodeElevation, nodeDescription, firstRestriction)
            }

            val links = bc.decodeNoisyNumber(1)
            for (li in 0..<links) {
                val nodeIdx = n + nodeIdxDiff.decodeSignedValue()

                var dlonRemaining: Int
                var dlatRemaining: Int

                var isReverse = false
                if (nodeIdx != n) { // internal (forward-) link
                    val targetId = expandId(faid[nodeIdx])
                    dlonRemaining = targetId.longitudeFromId() - nodeLongitude
                    dlatRemaining = targetId.latitudeFromId() - nodeLatitude
                } else {
                    isReverse = bc.decodeBit()
                    dlonRemaining = extLonDiff.decodeSignedValue()
                    dlatRemaining = extLatDiff.decodeSignedValue()
                }

                val wayTags = wayTagCoder.decodeTagValueSet()

                val linklon = nodeLongitude + dlonRemaining
                val linklat = nodeLatitude + dlatRemaining
                aboffset = 0
                if (!isReverse) { // write geometry for forward links only
                    var matcher =
                        if (wayTags == null || wayTags.accessType < 2) null else waypointMatcher
                    val ilontarget = nodeLongitude + dlonRemaining
                    val ilattarget = nodeLatitude + dlatRemaining
                    if (matcher != null) {
                        val useAsStartWay =
                            wayTags == null || wayValidator.checkStartWay(wayTags.data)
                        if (!matcher.start(nodeLongitude, nodeLatitude, ilontarget, ilattarget, useAsStartWay)) {
                            matcher = null
                        }
                    }

                    val transcount = bc.decodeVarBits()
                    var count = transcount + 1
                    for (i in 0..<transcount) {
                        val dlon = bc.decodePredictedValue(dlonRemaining / count)
                        val dlat = bc.decodePredictedValue(dlatRemaining / count)
                        dlonRemaining -= dlon
                        dlatRemaining -= dlat
                        count--
                        val elediff = transEleDiff.decodeSignedValue()
                        if (wayTags != null) {
                            writeVarLengthSigned(dlon)
                            writeVarLengthSigned(dlat)
                            writeVarLengthSigned(elediff)
                        }

                        matcher?.transferNode(ilontarget - dlonRemaining, ilattarget - dlatRemaining)
                    }
                    matcher?.end()
                }

                if (wayTags != null) {
                    var geometry: ByteArray? = null
                    if (aboffset > 0) {
                        geometry = ByteArray(aboffset)
                        ab.copyInto(geometry, 0, 0, aboffset)
                    }

                    if (nodeIdx != n) { // valid internal (forward-) link
                        var sourceNode = node
                        if (sourceNode == null) {
                            sourceNode = OsmNode(nodeId)
                            nodes[n] = sourceNode
                            node = sourceNode
                        }
                        if (nodeDataPending) {
                            nodeDataPending = false
                            applyNodeData(sourceNode, nodeElevation, nodeDescription, firstRestriction)
                        }
                        var node2 = nodes[nodeIdx] as OsmNode?
                        if (node2 == null) {
                            node2 = OsmNode(expandId(faid[nodeIdx]))
                            nodes[nodeIdx] = node2
                            if (nodeIdx < n) {
                                applyNodeData(
                                    node2,
                                    nodeElevations[nodeIdx],
                                    nodeDescriptions[nodeIdx],
                                    nodeRestrictions[nodeIdx],
                                )
                            }
                        }
                        var link: OsmLink? =
                            if (sourceNode.isLinkUnused) sourceNode else (if (node2.isLinkUnused) node2 else null)
                        if (link == null) {
                            link = OsmLink()
                        }
                        link.descriptionBitmap = wayTags.data
                        link.geometry = geometry
                        sourceNode.addLink(link, isReverse, node2)
                    } else { // weave external link
                        var sourceNode = node
                        if (sourceNode == null) {
                            sourceNode = OsmNode(nodeId)
                            nodes[n] = sourceNode
                            node = sourceNode
                        }
                        if (nodeDataPending) {
                            nodeDataPending = false
                            applyNodeData(sourceNode, nodeElevation, nodeDescription, firstRestriction)
                        }
                        sourceNode.addLink(
                            linklon,
                            linklat,
                            wayTags.data,
                            geometry,
                            hollowNodes,
                            isReverse
                        )
                        sourceNode.visitID = 1
                    }
                }
            } // ... loop over links
        } // ... loop over nodes

            hollowNodes.cleanupAndCount(nodes, size)
        } finally {
            nodes.fill(null, 0, clearLimit)
        }
    }

    public fun expandId(id32: Int): Long {
        return id64Base + id32_00[id32 and 1023] + id32_10[(id32 shr 10) and 1023] + id32_20[(id32 shr 20) and 1023]
    }

    private fun applyNodeData(
        node: OsmNode,
        altitude: Short,
        nodeDescription: ByteArray?,
        firstRestriction: TurnRestriction?
    ) {
        node.clearHollow()
        node.altitude = altitude
        node.nodeDescription = nodeDescription
        if (firstRestriction != null) {
            var tail: TurnRestriction = firstRestriction
            while (tail.next != null) tail = requireNotNull(tail.next)
            tail.next = node.firstRestriction
            node.firstRestriction = firstRestriction
        }
    }

    public companion object {
        private val id32_00 = LongArray(1024)
        private val id32_10 = LongArray(1024)
        private val id32_20 = LongArray(1024)

        init {
            for (i in 0..1023) {
                id32_00[i] = _expandId(i)
                id32_10[i] = _expandId(i shl 10)
                id32_20[i] = _expandId(i shl 20)
            }
        }

        private fun _expandId(id32: Int): Long {
            var id32 = id32
            var dlon = 0
            var dlat = 0

            var bm = 1
            while (bm < 0x8000) {
                if ((id32 and 1) != 0) dlon = dlon or bm
                if ((id32 and 2) != 0) dlat = dlat or bm
                id32 = id32 shr 2
                bm = bm shl 1
            }
            return Position.computeId(dlon, dlat)
        }
    }
}
