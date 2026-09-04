/**
 * Container for a track
 *
 * @author ab
 */
package com.danemadsen.atlas.beerouter.router

import androidx.collection.MutableLongObjectMap
import com.danemadsen.atlas.beerouter.geo.UNSET_ELEVATION
import com.danemadsen.atlas.beerouter.map.MatchedWaypoint
import com.danemadsen.atlas.beerouter.map.MatchedWaypoint.Companion.readFromStream
import com.danemadsen.atlas.beerouter.map.OsmPos
import kotlinx.io.EOFException
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlin.math.abs

public class OsmTrack {
    public var endPoint: MatchedWaypoint? = null
    public var nogoChecksums: LongArray = LongArray(3)
    public var profileTimestamp: Long = 0
    public var isDirty: Boolean = false

    public var showspeed: Boolean = false
    public var showSpeedProfile: Boolean = false
    public var showTime: Boolean = false

    public var params: MutableMap<String, String>? = null

    public var pois: MutableList<OsmNodeNamed> = mutableListOf()

    public data class OsmPathElementHolder(
        public var node: OsmPathElement? = null,
        public var nextHolder: OsmPathElementHolder? = null
    )

    public var nodes: MutableList<OsmPathElement> = mutableListOf()

    private var nodesMap: MutableLongObjectMap<OsmPathElementHolder> = MutableLongObjectMap()

    private var detourMap: MutableLongObjectMap<OsmPathElementHolder> = MutableLongObjectMap()

    public var voiceHints: VoiceHintList = VoiceHintList()

    public var name: String = "unset"

    public var matchedWaypoints: MutableList<MatchedWaypoint> = mutableListOf()
    public var exportWaypoints: Boolean = false
    public var exportCorrectedWaypoints: Boolean = false

    public fun addNode(node: OsmPathElement) {
        nodes.add(0, node)
    }

    private fun appendHolder(
        map: MutableLongObjectMap<OsmPathElementHolder>,
        id: Long,
        node: OsmPathElement?
    ) {
        val holder = OsmPathElementHolder(node = node)
        var current = map[id]
        if (current == null) {
            map.put(id, holder)
            return
        }

        while (current?.nextHolder != null) {
            current = current.nextHolder
        }
        current?.nextHolder = holder
    }

    public fun registerDetourForId(id: Long, detour: OsmPathElement?) {
        appendHolder(detourMap, id, detour)
    }

    public fun replaceDetours(source: OsmTrack) {
        val newMap = MutableLongObjectMap<OsmPathElementHolder>()
        source.detourMap.forEach { key, value ->
            newMap[key] = value
        }
        detourMap = newMap
    }

    public fun addDetours(source: OsmTrack) {
        source.detourMap.forEach { id, value ->
            if (!detourMap.contains(id) && source.nodesMap.contains(id)) {
                detourMap.put(id, value)
            }
        }
    }

    public var lastorigin: OsmPathElement? = null

    public fun buildMap() {
        for (node in nodes) {
            appendHolder(nodesMap, node.idFromPos, node)
        }
    }

    /**
     * @throws IllegalArgumentException if [endPoint] is null
     * @throws IOException if an I/O error occurs while writing to the sink
     */
    public fun writeBinary(sink: Sink) {
        requireNotNull(endPoint).writeToStream(sink)
        sink.writeInt(nodes.size)
        for (node in nodes) {
            node.writeToStream(sink)
        }
        sink.writeLong(nogoChecksums[0])
        sink.writeLong(nogoChecksums[1])
        sink.writeLong(nogoChecksums[2])
        sink.writeByte(if (isDirty) 1 else 0)
        sink.writeLong(profileTimestamp)
    }

    /**
     * Adds all nodes from another track and rebuilds the node map.
     */
    public fun addNodes(t: OsmTrack) {
        for (n in t.nodes) addNode(n)
        buildMap()
    }

    public fun containsNode(node: OsmPos): Boolean = nodesMap.contains(node.idFromPos)

    public fun getLink(n1: Long, n2: Long): OsmPathElement? {
        var h = nodesMap[n2]
        while (h != null) {
            val node = requireNotNull(h.node)
            val e1 = node.origin
            if (e1 != null && e1.idFromPos == n1) {
                return node
            }
            h = h.nextHolder
        }
        return null
    }

    public fun appendTrack(t: OsmTrack) {
        val ourSize = nodes.size
        if (ourSize > 0 && t.nodes.size > 1) {
            val olde = nodes[ourSize - 1]
            t.nodes[1].origin = olde
        }
        val t0 = if (ourSize > 0) nodes[ourSize - 1].time else 0f
        val e0 = if (ourSize > 0) nodes[ourSize - 1].energy else 0f
        val c0 = if (ourSize > 0) nodes[ourSize - 1].cost else 0
        for ((index, element) in t.nodes.withIndex()) {
            if (index == 0 && ourSize > 0 && nodes[ourSize - 1].position.altitude == UNSET_ELEVATION) {
                nodes[ourSize - 1].position = nodes[ourSize - 1].position.copy(altitude = element.position.altitude)
            }
            if (index > 0 || ourSize == 0) {
                element.time += t0
                element.energy += e0
                element.cost += c0
                element.message?.let { message ->
                    if (message.position.id != element.position.id) {
                        message.position = element.position
                    }
                }
                nodes.add(element)
            }
        }

        if (t.voiceHints.isNotEmpty()) {
            if (ourSize > 0) {
                for (hint in t.voiceHints) {
                    hint.indexInTrack = hint.indexInTrack + ourSize - 1
                }
            }
            voiceHints.addAll(t.voiceHints)
        } else {
            addDetours(t)
        }

        distance += t.distance
        ascend += t.ascend
        plainAscend += t.plainAscend
        cost += t.cost
        energy = nodes[nodes.size - 1].energy.toInt()

        showspeed = showspeed or t.showspeed
        showSpeedProfile = showSpeedProfile or t.showSpeedProfile
    }

    public var distance: Int = 0
    public var ascend: Int = 0
    public var plainAscend: Int = 0
    public var cost: Int = 0
    public var energy: Int = 0
    public val itinerary: MutableList<String?> = mutableListOf()

    public fun getVoiceHint(i: Int): VoiceHint? = voiceHints.firstOrNull { it.indexInTrack == i }

    public fun getMatchedWaypoint(idx: Int): MatchedWaypoint? =
        matchedWaypoints.firstOrNull { idx == it.indexInTrack }

    private fun getVNode(i: Int): Int {
        val m1 = if (i + 1 < nodes.size) nodes[i + 1].message else null
        val m0 = if (i < nodes.size) nodes[i].message else null
        val vnode0 = m1?.vnode0 ?: 999
        val vnode1 = m0?.vnode1 ?: 999
        return if (vnode0 < vnode1) vnode0 else vnode1
    }

    public val totalSeconds: Int
        get() = ((if (nodes.size < 2) 0f else nodes[nodes.size - 1].time - nodes[0].time) + 0.5f).toInt()

    public fun equalsTrack(t: OsmTrack): Boolean {
        if (nodes.size != t.nodes.size) return false
        for (i in nodes.indices) {
            val e1 = nodes[i]
            val e2 = t.nodes[i]
            if (e1.position.longitude != e2.position.longitude ||
                e1.position.latitude != e2.position.latitude) return false
        }
        return true
    }

    public fun getFromDetourMap(id: Long): OsmPathElementHolder? = detourMap[id]

    public fun prepareSpeedProfile(rc: RoutingContext?) {
        // sendSpeedProfile = rc.keyValues != null && rc.keyValues.containsKey( "vmax" );
    }

    /**
     * @throws IllegalStateException if [nogopoints] contains an invalid OsmNogoPolygon
     */
    public fun processVoiceHints(rc: RoutingContext) {
        voiceHints = VoiceHintList()
        voiceHints.setTransportMode(rc.global.carMode, rc.global.bikeMode)

        if (!rc.generateTurns) {
            return
        }
        if (detourMap.isEmpty() && !rc.global.hasDirectRouting) {
            // only when no direct way points
            return
        }
        var nodeNr = nodes.size - 1
        var node = nodes.getOrNull(nodeNr)
        while (node != null) {
            node = node.origin
        }

        node = nodes[nodeNr]
        val inputs = mutableListOf<VoiceHint>()
        while (node != null) {
            val origin = node.origin
            if (origin != null) {
                if (nodeNr == nodes.size - 1) {
                    val input = VoiceHint()
                    inputs.add(0, input)
                    input.position = node.position
                    input.goodWay = node.message
                    input.oldWay = node.message
                    input.indexInTrack = nodes.size - 1
                    input.command = VoiceHint.Command.END
                }
                val input = VoiceHint()
                inputs.add(input)
                input.position = origin.position
                input.indexInTrack = --nodeNr
                input.goodWay = node.message
                input.oldWay = if (origin.message == null) node.message else origin.message

                if (rc.generateTurns) {
                    val mwpt = getMatchedWaypoint(nodeNr)
                    if (mwpt != null && mwpt.type == MatchedWaypoint.Type.DIRECT) {
                        input.command = VoiceHint.Command.BL
                        val turnMessage = if (nodeNr == 0) {
                            requireNotNull(origin.message)
                        } else {
                            requireNotNull(node.message)
                        }
                        input.angle =
                            turnMessage.turnangle
                        input.distanceToNext = node.distanceTo(origin).toDouble()
                    }
                }
                run {
                    val detours = detourMap[origin.idFromPos]
                    if (nodeNr >= 0 && detours != null) {
                        var h: OsmPathElementHolder? = detours
                        while (h != null) {
                            val e = h.node
                            input.addBadWay(startSection(e, origin))
                            h = h.nextHolder
                        }
                    }
                }
                /* else if (nodeNr == 0 && detours != null) {
          OsmPathElementHolder h = detours;
          OsmPathElement e = h.node;
          input.addBadWay(startSection(e, e));
        } */
            }
            node = node.origin
        }

        val transportMode = voiceHints.transportMode
        val vproc = VoiceHintProcessor(
            rc.global.turnInstructionCatchingRange,
            rc.global.turnInstructionRoundabouts,
            transportMode
        )
        val results = vproc.process(inputs)

        val minDistance = this.minDistance.toDouble()
        val resultsLast =
            vproc.postProcess(results, rc.global.turnInstructionCatchingRange, minDistance)
        for (hint in resultsLast) {
            voiceHints.list.add(hint)
        }
    }

    public val minDistance: Int
        get() {
            return when (voiceHints.transportMode) {
                VoiceHintList.TransportMode.CAR -> 20
                VoiceHintList.TransportMode.FOOT -> 3
                VoiceHintList.TransportMode.BIKE -> 5
            }
        }

    public fun getVoiceHintTime(i: Int): Float =
        when {
            voiceHints.list.isNotEmpty() && i < voiceHints.list.size -> voiceHints.list[i].time
            nodes.isEmpty() -> 0f
            else -> nodes[nodes.size - 1].time
        }

    public fun removeVoiceHint(i: Int): Boolean {
        val removeIndex = voiceHints.indexOfFirst { it.indexInTrack == i }
        if (removeIndex < 0) return false
        voiceHints.removeAt(removeIndex)
        return true
    }

    private fun startSection(element: OsmPathElement?, root: OsmPathElement): MessageData? {
        var e = element
        var cnt = 0
        val rootId = root.idFromPos
        while (e != null && e.origin != null) {
            val origin = requireNotNull(e.origin)
            if (origin.idFromPos == rootId) {
                return e.message
            }
            e = origin
            require(cnt++ != 1000000) { "ups: $root->$element" }
        }
        return null
    }

    public companion object {
        /**
         * Reads a track from a binary source.
         *
         * @param source the binary source to read from
         * @param newEp the expected endpoint waypoint
         * @param nogoChecksums the nogo checksums to validate against
         * @param profileChecksum the profile checksum to validate against
         * @param debugInfo optional debug info string builder
         * @return the read track, or null if validation fails
         * @throws IOException if an I/O error occurs while reading from the source
         * @throws IllegalArgumentException if the endpoint waypoint is missing from the stream
         */
        public fun readBinary(
            source: Source,
            newEp: OsmNodeNamed,
            nogoChecksums: LongArray,
            profileChecksum: Long,
            debugInfo: StringBuilder?
        ): OsmTrack? {
            val ep = readFromStream(source)
            val waypoint = requireNotNull(ep.waypoint)
            val dlon = waypoint.position.longitude - newEp.position.longitude
            val dlat = waypoint.position.latitude - newEp.position.latitude
            val targetMatch = dlon < 20 && dlon > -20 && dlat < 20 && dlat > -20
            debugInfo?.append("target-delta = $dlon/$dlat targetMatch=$targetMatch")
            if (!targetMatch) return null

            val track = OsmTrack()
            track.endPoint = ep
            val n = source.readInt()
            var lastPe: OsmPathElement? = null
            for (i in 0..<n) {
                val pe: OsmPathElement = OsmPathElement.readFromStream(source)
                pe.origin = lastPe
                lastPe = pe
                track.nodes.add(pe)
            }
            track.cost = requireNotNull(lastPe).cost
            track.buildMap()

            val al = LongArray(3)
            var pchecksum: Long = 0
            try {
                al[0] = source.readLong()
                al[1] = source.readLong()
                al[2] = source.readLong()
            } catch (_: EOFException) { /* kind of expected */
            }
            try {
                track.isDirty = source.readByte().toInt() != 0
            } catch (_: EOFException) { /* kind of expected */
            }
            try {
                pchecksum = source.readLong()
            } catch (_: EOFException) { /* kind of expected */
            }
            val nogoCheckOk =
                abs(al[0] - nogoChecksums[0]) <= 20 &&
                        abs(al[1] - nogoChecksums[1]) <= 20 &&
                        abs(al[2] - nogoChecksums[2]) <= 20
            val profileCheckOk = pchecksum == profileChecksum

            if (debugInfo != null) {
                debugInfo.append(" nogoCheckOk=$nogoCheckOk profileCheckOk=$profileCheckOk")
                debugInfo.append(" al=${formatLongs(al)} nogoChecksums=${formatLongs(nogoChecksums)}")
            }
            return if (nogoCheckOk && profileCheckOk) track else null
        }

        private fun formatLongs(al: LongArray): String = al.joinToString(" ", "{", "}")
    }
}
