package com.danemadsen.atlas.beerouter.router

import androidx.collection.MutableLongObjectMap
import com.danemadsen.atlas.beerouter.geo.CheapAngleMeter.bearing
import com.danemadsen.atlas.beerouter.geo.CheapAngleMeter.bearingDifference
import com.danemadsen.atlas.beerouter.geo.CheapAngleMeter.turnAngle
import com.danemadsen.atlas.beerouter.geo.CheapRuler.destination
import com.danemadsen.atlas.beerouter.geo.CheapRuler.distance
import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.geo.UNSET_ELEVATION
import com.danemadsen.atlas.beerouter.geo.clampEncodedAltitude
import com.danemadsen.atlas.beerouter.geo.encodedAltitudeToMeters
import com.danemadsen.atlas.beerouter.geo.encodeAltitudeMeters
import com.danemadsen.atlas.beerouter.geo.withAltitude
import com.danemadsen.atlas.beerouter.map.MatchedWaypoint
import com.danemadsen.atlas.beerouter.map.NodesCache
import com.danemadsen.atlas.beerouter.map.OsmLink
import com.danemadsen.atlas.beerouter.map.OsmNode
import com.danemadsen.atlas.beerouter.map.OsmNodePairSet
import com.danemadsen.atlas.beerouter.map.OsmNodesMap
import com.danemadsen.atlas.beerouter.map.OsmPos
import com.danemadsen.atlas.beerouter.router.OsmTrack.OsmPathElementHolder
import com.danemadsen.atlas.beerouter.util.SortedHeap
import com.danemadsen.atlas.beerouter.router.exceptions.RoutingException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.TimeSource

internal class BorderLoadDeferralPolicy {
    internal enum class OverBudgetAction {
        DEFER_BORDER_LOADS,
        MEMORY_LIMIT_REACHED,
    }

    internal var deferringBorderLoads: Boolean = false
        private set

    private var mustMakeProgressAfterDeferral: Boolean = false

    internal fun onDeferredFrontierRequeued() {
        deferringBorderLoads = false
        mustMakeProgressAfterDeferral = true
    }

    internal fun onPathAcceptedForExpansion() {
        mustMakeProgressAfterDeferral = false
    }

    internal fun onCleanupStillOverBudget(
        maxTotalCost: Int,
        fastPartialRecalc: Boolean,
    ): OverBudgetAction {
        if (maxTotalCost < UNBOUNDED_MAX_TOTAL_COST || mustMakeProgressAfterDeferral || fastPartialRecalc) {
            return OverBudgetAction.MEMORY_LIMIT_REACHED
        }
        deferringBorderLoads = true
        return OverBudgetAction.DEFER_BORDER_LOADS
    }

    internal companion object {
        internal const val UNBOUNDED_MAX_TOTAL_COST: Int = 1000000000
    }
}

public class RoutingEngine(private val routingContext: RoutingContext) {
    private var nodesCache: NodesCache? = null
    private val openSet = SortedHeap<OsmPath?>()

    private val extraWaypoints: MutableList<OsmNodeNamed> = mutableListOf()
    protected var matchedWaypoints: MutableList<MatchedWaypoint> = mutableListOf()
    private var linksProcessed: Int = 0

    private var nodeLimit = 0 // used for target island search
    private val maxNodesIslandCheck = 500
    private val islandNodePairs = OsmNodePairSet(maxNodesIslandCheck)
    private val useNodePoints = false // use the start/end nodes  instead of crosspoint

    private val maxStepsCheck = 500

    private val roundtripDefaultDirectionAdd = 45

    private val maxDynamicRange = 60000

    private var airDistanceCostFactor: Double = 0.0
    private var lastAirDistanceCostFactor: Double = 0.0

    private var guideTrack: OsmTrack? = null

    private var matchPath: OsmPathElement? = null

    private var boundary: SearchBoundary? = null

    private var extract: Array<Any?>? = null

    private val directWeaving = true //!Boolean.getBoolean("disableDirectWeaving")

    // Pre-allocated buffers for the routing hot loop — avoids per-node ArrayList + Pair allocation
    private val candidateLinks = ArrayList<OsmLink>(16)
    private val candidateNodes = ArrayList<OsmNode>(16)

    private fun log(message: String) {
        // Hot path: keep routing output silent by default.
    }

    /**
     * Performs routing for the given waypoints.
     *
     * @param waypoints list of waypoints to route through
     * @return the calculated track, or null if only info is requested
     * @throws RoutingException if no route can be found for the requested waypoints
     * @throws RoutingIslandException if the routing graph is disconnected (routing island)
     * @throws IllegalStateException if the routing engine encounters an invalid internal state
     * @throws IllegalArgumentException if a waypoint cannot be matched to the map data
     */
    public suspend fun doRouting(waypoints: List<OsmNodeNamed>): OsmTrack? {
        linksProcessed = 0
        val waypoints = waypoints.toMutableList()
        try {
            if (routingContext.allowSamewayback) {
                if (waypoints.size == 2) {
                    val onn = OsmNodeNamed(OsmNode(waypoints[0].position)).apply {
                        name = "to"
                    }
                    waypoints.add(onn)
                } else {
                    waypoints[waypoints.size - 1].name = "via${waypoints.size - 1}_center"
                    val newpoints = mutableListOf<OsmNodeNamed>()
                    for (i in waypoints.size - 2 downTo 0) {
                        // System.out.println("back " + waypoints.get(i));
                        val onn = OsmNodeNamed(
                            OsmNode(
                                waypoints[i].position
                            )
                        ).apply { name = "via" }
                        newpoints.add(onn)
                    }
                    newpoints[newpoints.size - 1].name = "to"
                    waypoints.addAll(newpoints)
                }
            }

            val nsections = waypoints.size - 1
            val refTracks = arrayOfNulls<OsmTrack>(nsections) // used ways for alternatives
            val lastTracks = arrayOfNulls<OsmTrack>(nsections)
            var track: OsmTrack?
            var i = 0
            while (true) {
                track = findTrack(waypoints, refTracks, lastTracks)!!

                // we are only looking for info
                if (routingContext.ai != null) return null

                track.name = "brouter_$i"

                if (i != min(3, max(0, routingContext.alternativeIdx))) {
                    i++
                    continue
                }
                break
            }
            return track
        } finally {
            nodesCache?.let {
                log("NodesCache status before close=${it.formatStatus()}")
                it.close()
                nodesCache = null
            }
            openSet.clear()
        }
    }

    /**
     * Retrieves routing information for the given waypoints without computing a full track.
     *
     * @param waypoints list of waypoints to query info for
     * @return track with info, or null if unavailable
     * @throws RoutingException if routing fails
     * @throws RoutingIslandException if the routing graph is disconnected (routing island)
     * @throws IllegalArgumentException if a waypoint cannot be matched to the map data
     * @throws IllegalStateException if the routing engine encounters an invalid internal state
     */
    public suspend fun doGetInfo(waypoints: List<OsmNodeNamed>): OsmTrack? {
        routingContext.freeNoWays()

        val wpt1 = MatchedWaypoint().apply {
            waypoint = waypoints[0]
            name = "wpt_info"
        }
        val listOne = mutableListOf<MatchedWaypoint>()
        listOne.add(wpt1)
        matchWaypointsToNodes(listOne)

        resetCache(true)
        nodesCache!!.nodesMap.cleanupMode = OsmNodesMap.CleanupMode.COUNT_ONLY

        val start1 = nodesCache!!.getGraphNode(listOne[0].node1!!)
        nodesCache!!.obtainNonHollowNode(start1)

        guideTrack = OsmTrack().apply {
            addNode(
                OsmPathElement(wpt1.node2!!.position.withAltitude(0), null)
            )
            addNode(
                OsmPathElement(wpt1.node1!!.position.withAltitude(0), null)
            )
        }

        matchedWaypoints.clear()
        val wp1 = MatchedWaypoint().apply {
            crosspoint = OsmNode(wpt1.node1!!.position)
            node1 = OsmNode(wpt1.node1!!.position)
            node2 = OsmNode(wpt1.node2!!.position)
        }
        matchedWaypoints.add(wp1)
        val wp2 = MatchedWaypoint().apply {
            crosspoint = OsmNode(wpt1.node2!!.position)
            node1 = OsmNode(wpt1.node1!!.position)
            node2 = OsmNode(wpt1.node2!!.position)
        }
        matchedWaypoints.add(wp2)

        val t = findTrack(wp1, wp2, null, null, false) ?: return null

        t.matchedWaypoints = matchedWaypoints
        t.name = "getinfo"

        // find nearest point
        var mindist = 99999
        var minIdx = -1
        for (i in t.nodes.indices) {
            val ope = t.nodes[i]
            val dist = ope.distanceTo(listOne[0].crosspoint!!)
            if (mindist > dist) {
                mindist = dist
                minIdx = i
            }
        }
        val otherIdx = if (minIdx == t.nodes.size - 1) {
            minIdx - 1
        } else {
            minIdx + 1
        }
        val minAltitude = if (minIdx != -1) t.nodes[minIdx].position.altitude else UNSET_ELEVATION
        val otherAltitude = if (minIdx != -1) t.nodes[otherIdx].position.altitude else UNSET_ELEVATION
        val waypointAltitude: Short = if (minAltitude != UNSET_ELEVATION && otherAltitude != UNSET_ELEVATION) {
            val otherdist = t.nodes[otherIdx].distanceTo(listOne[0].crosspoint!!)
            val minSElev = minAltitude.toInt()
            val otherSElev = otherAltitude.toInt()
            val diffSElev = otherSElev - minSElev
            val diff = mindist.toDouble() / (mindist + otherdist) * diffSElev
            (minSElev + diff.toInt()).toShort()
        } else {
            UNSET_ELEVATION
        }

        val n = OsmNodeNamed(listOne[0].crosspoint!!).apply {
            name = wpt1.name
            position = position.copy(altitude = waypointAltitude)
            nodeDescription = start1.firstlink?.descriptionBitmap
        }
        t.pois.add(n)
        t.matchedWaypoints = listOne
        t.exportWaypoints = routingContext.exportWaypoints

        return t
    }

    /**
     * Performs a round-trip routing for the given waypoints.
     *
     * @param waypoints list of waypoints to route through
     * @return the calculated round-trip track
     * @throws RoutingException if no route can be found for the requested waypoints
     * @throws RoutingIslandException if the routing graph is disconnected (routing island)
     * @throws IllegalArgumentException if a waypoint cannot be matched to the map data
     * @throws IllegalStateException if the routing engine encounters an invalid internal state
     */
    public suspend fun doRoundTrip(waypoints: List<OsmNodeNamed>): OsmTrack? {
        val waypoints = waypoints.toMutableList()
        routingContext.global.useDynamicDistance = true
        val searchRadius = (routingContext.roundTripDistance ?: 1500).toDouble()
        var direction = (routingContext.startDirection ?: -1).toDouble()
        if (direction == -1.0) direction =
            getRandomDirectionFromData(waypoints[0], searchRadius).toDouble()

        if (routingContext.allowSamewayback) {
            val pos = destination(waypoints[0].position, searchRadius, direction)
            val onn = OsmNodeNamed(OsmNode(pos)).apply { name = "rt1" }
            waypoints.add(onn)
        } else {
            buildPointsFromCircle(
                waypoints,
                direction,
                searchRadius,
                routingContext.roundTripPoints ?: 5
            )
        }

        routingContext.global.waypointCatchingRange = 250.0

        return doRouting(waypoints)
    }

    private fun buildPointsFromCircle(
        waypoints: MutableList<OsmNodeNamed>,
        startAngle: Double,
        searchRadius: Double,
        points: Int
    ) {
        //startAngle -= 90;
        for (i in 1..<points) {
            val anAngle = 90 - (180.0 * i / points)
            val pos = destination(waypoints[0].position, searchRadius, startAngle - anAngle)
            val onn = OsmNodeNamed(OsmNode(pos)).apply { name = "rt$i" }
            waypoints.add(onn)
        }

        val onn = OsmNodeNamed(waypoints[0]).apply { name = "to_rt" }
        waypoints.add(onn)
    }

    private fun getRandomDirectionFromData(wp: OsmNodeNamed, searchRadius: Double): Int {
        val start = TimeSource.Monotonic.markNow()

        val consider_elevation = routingContext.way.getVariableValue("consider_elevation", 0f) == 1f
        val consider_forest = routingContext.way.getVariableValue("consider_forest", 0f) == 1f
        val consider_river = routingContext.way.getVariableValue("consider_river", 0f) == 1f

        val preferredRandomType = if (consider_elevation) {
            AreaInfo.ResultType.ELEV50
        } else if (consider_forest) {
            AreaInfo.ResultType.GREEN
        } else if (consider_river) {
            AreaInfo.ResultType.RIVER
        } else {
            return (kotlin.random.Random.nextDouble() * 360).toInt()
        }

        val wpt1 = MatchedWaypoint().apply {
            waypoint = wp
            name = "info"
            radius = searchRadius * 1.5
        }

        val ais = mutableListOf<AreaInfo>()
        val areareader = AreaReader()
        routingContext.rawAreaSourceFactory?.let { sourceFactory ->
            runCatching {
                sourceFactory().buffered().use { source ->
                    areareader.readAreaInfo(source, wpt1, ais)
                }
            }
        }

        if (ais.isEmpty()) {
            val listStart = mutableListOf<MatchedWaypoint>().apply { add(wpt1) }
            val wpliststart = mutableListOf<OsmNodeNamed>().apply { add(wp) }
            val listOne = mutableListOf<OsmNodeNamed>()

            for (a in 45..315 step 90) {
                val pos = destination(wp.position, searchRadius * 1.5, a.toDouble())
                val onn = OsmNodeNamed(OsmNode(pos)).apply { name = "via$a" }
                listOne.add(onn)

                val wpt = MatchedWaypoint().apply {
                    waypoint = onn
                    name = onn.name
                }
                listStart.add(wpt)
            }

            val rc = RoutingContext(routingContext)
            val re = RoutingEngine(rc)
            rc.global.useDynamicDistance = true
            re.matchWaypointsToNodes(listStart)
            re.resetCache(true)

            val numForest = rc.way.getLookupKey("estimated_forest_class")
            val numRiver = rc.way.getLookupKey("estimated_river_class")

            val start1 = re.nodesCache!!.getStartNode(listStart[0].node1!!.idFromPos)

            val elev = start1?.altitude
                ?.takeUnless { it == UNSET_ELEVATION }
                ?.let(::encodedAltitudeToMeters)
                ?: 0.0 // listOne.get(0).crosspoint.getElev();

            var maxlon = Int.MIN_VALUE
            var minlon = Int.MAX_VALUE
            var maxlat = Int.MIN_VALUE
            var minlat = Int.MAX_VALUE
            for (on in listOne) {
                maxlon = max(on.position.longitude, maxlon)
                minlon = min(on.position.longitude, minlon)
                maxlat = max(on.position.latitude, maxlat)
                minlat = min(on.position.latitude, minlat)
            }

            val searchRect = OsmNogoPolygon(true).apply {
                addVertex(Position(maxlon, maxlat))
                addVertex(Position(maxlon, minlat))
                addVertex(Position(minlon, minlat))
                addVertex(Position(minlon, maxlat))
            }

            for (a in 0..3) {
                rc.ai = AreaInfo(a * 90 + 90)
                rc.ai!!.elevStart = elev
                rc.ai!!.numForest = numForest
                rc.ai!!.numRiver = numRiver

                val polygon = OsmNogoPolygon(true)
                rc.ai!!.polygon = polygon
                polygon.addVertex(wp.position)
                polygon.addVertex(listOne[a].position)
                if (a == 3) polygon.addVertex(listOne[0].position)
                else polygon.addVertex(listOne[a + 1].position)

                ais.add(rc.ai!!)
            }

            var maxscale = abs(searchRect.points[2].longitude - searchRect.points[0].longitude)
            maxscale = max(1, (maxscale / 31250f / 2).roundToInt() + 1)

            areareader.getDirectAllData(
                rc,
                wp,
                maxscale,
                rc.way,
                searchRect,
                ais
            )

            routingContext.rawAreaSinkFactory?.let { sinkFactory ->
                try {
                    wpt1.radius = searchRadius * 1.5
                    sinkFactory().buffered().use { sink ->
                        areareader.writeAreaInfo(sink, wpt1, ais)
                    }
                } catch (_: IOException) {
                    // Silently ignore I/O errors writing area info
                }
            }
            rc.ai = null
        }

        log("round trip execution time=${start.elapsedNow().inWholeMilliseconds / 1000.0} seconds")

        // for (AreaInfo ai: ais) {
        //  System.out.println("\n" + ai.toString());
        //}
        when (preferredRandomType) {
            AreaInfo.ResultType.ELEV50 -> ais.sortByDescending { it.elev50Weight }
            AreaInfo.ResultType.GREEN -> ais.sortByDescending { it.green }
            AreaInfo.ResultType.RIVER -> ais.sortByDescending { it.river }
        }

        val angle = ais[0].direction
        return angle - 30 + (kotlin.random.Random.nextDouble() * 60).toInt()
    }


    private fun postElevationCheck(track: OsmTrack) {
        var lastPt: OsmPathElement? = null
        var startPt: OsmPathElement? = null
        var lastElev = Short.MIN_VALUE
        var startElev = Short.MIN_VALUE
        var endElev = Short.MIN_VALUE
        var startIdx = 0
        var endIdx: Int
        var dist = 0
        val ourSize = track.nodes.size
        for (idx in 0..<ourSize) {
            val n = track.nodes[idx]
            if (n.position.altitude == UNSET_ELEVATION && lastElev != UNSET_ELEVATION && idx < ourSize - 1) {
                // start one point before entry point to get better elevation results
                if (idx > 1) startElev = track.nodes[idx - 2].position.altitude
                if (startElev == UNSET_ELEVATION) startElev = lastElev
                startIdx = idx
                startPt = lastPt
                dist = 0
                if (lastPt != null) dist += n.distanceTo(lastPt)
            } else if (n.position.altitude != UNSET_ELEVATION && lastElev == UNSET_ELEVATION && startElev != UNSET_ELEVATION) {
                // end one point behind exit point to get better elevation results
                if (idx + 1 < track.nodes.size) endElev =
                    track.nodes[idx + 1].position.altitude
                if (endElev == UNSET_ELEVATION) endElev = n.position.altitude
                endIdx = idx
                var tmpPt = track.nodes[if (startIdx > 1) startIdx - 2 else startIdx - 1]
                val diffElev = endElev - startElev
                dist += tmpPt.distanceTo(startPt!!)
                dist += n.distanceTo(lastPt!!)
                var distRest = dist
                var incline = diffElev / (dist / 100.0)
                var tmpincline = 0.0
                var startincline = 0.0
                var selev = track.nodes[startIdx - 2].position.altitude.toDouble()
                var hasInclineTags = false
                for (i in startIdx - 1..<endIdx + 1) {
                    val tmp = track.nodes[i]
                    if (tmp.message != null) {
                        val md = tmp.message!!.copy()
                        val msg = md.wayTags

                        val reverse = msg?.get("reversedirection") == "yes"

                        if (msg?.contains("incline") == true) {
                            hasInclineTags = true
                            tmpincline = try {
                                msg["incline"]!!
                                    .replace("%", "")
                                    .replace("°", "")
                                    .toDouble().let { if (reverse) -it else it }
                            } catch (e: NumberFormatException) {
                                0.0
                            }
                        } else {
                            tmpincline = 0.0
                        }
                        if (startincline == 0.0) {
                            startincline = tmpincline
                        } else if (startincline < 0 && tmpincline > 0) {
                            // for the way up find the exit point
                            val diff = endElev - selev
                            tmpincline = diff / (distRest / 100.0)
                        }

                    }
                    val tmpdist = tmp.distanceTo(tmpPt)
                    distRest -= tmpdist
                    if (hasInclineTags) incline = tmpincline
                    selev = (selev + (tmpdist / 100.0 * incline))
                    val encodedElevation = clampEncodedAltitude(selev)
                    tmp.position = tmp.position.copy(altitude = encodedElevation)
                    tmp.message!!.position =
                        tmp.message!!.position.copy(altitude = encodedElevation)
                    tmpPt = tmp
                }
                dist = 0
            } else if (n.position.altitude != UNSET_ELEVATION && lastElev == UNSET_ELEVATION && startIdx == 0) {
                // fill at start
                track.nodes.subList(0, idx).forEach {
                    it.position = it.position.copy(
                        altitude = n.position.altitude
                    )
                }
            } else if (n.position.altitude == UNSET_ELEVATION && idx == track.nodes.size - 1) {
                // fill at end
                startIdx = idx
                track.nodes.subList(startIdx, track.nodes.size).forEach {
                    it.position =
                        it.position.copy(altitude = lastElev)
                }
            } else if (n.position.altitude == UNSET_ELEVATION) {
                if (lastPt != null) dist += n.distanceTo(lastPt)
            }
            lastElev = n.position.altitude
            lastPt = n
        }
    }

    private suspend fun doSearch(waypoints: List<OsmNodeNamed>) {
        val seedPoint = MatchedWaypoint()
        seedPoint.waypoint = waypoints[0]
        val listOne = mutableListOf(seedPoint)
        matchWaypointsToNodes(listOne)

        findTrack(seedPoint, null, null, null, false)

        openSet.clear()
    }

    private suspend fun findTrack(
        waypoints: MutableList<OsmNodeNamed>,
        refTracks: Array<OsmTrack?>,
        lastTracks: Array<OsmTrack?>
    ): OsmTrack? {
        while (true) {
            try {
                return tryFindTrack(waypoints, refTracks, lastTracks)
            } catch (rie: RoutingIslandException) {
                if (routingContext.global.useDynamicDistance) {
                    for (mwp in matchedWaypoints) {
                        if (mwp.name?.contains("_add") == true) {
                            val n1 = mwp.node1!!.idFromPos
                            val n2 = mwp.node2!!.idFromPos
                            islandNodePairs.addTempPair(n1, n2)
                        }
                    }
                }
                islandNodePairs.freezeTempPairs()
                nodesCache!!.clean(true)
                matchedWaypoints = mutableListOf()
            }
        }
    }

    private suspend fun tryFindTrack(
        waypoints: MutableList<OsmNodeNamed>,
        refTracks: Array<OsmTrack?>,
        lastTracks: Array<OsmTrack?>
    ): OsmTrack? {
        var refTracks = refTracks
        var lastTracks = lastTracks
        val totaltrack = OsmTrack()
        var nUnmatched = waypoints.size
        var hasDirectRouting = false

        if (useNodePoints && extraWaypoints.isNotEmpty()) {
            // add extra waypoints from the last broken round
            for (wp in extraWaypoints) {
                if (wp.type == MatchedWaypoint.Type.DIRECT) hasDirectRouting = true
                if (wp.name?.startsWith("from") == true) {
                    waypoints.add(1, wp)
                    waypoints[0].type = MatchedWaypoint.Type.DIRECT
                    nUnmatched++
                } else {
                    waypoints.add(waypoints.size - 1, wp)
                    waypoints[waypoints.size - 2].type = MatchedWaypoint.Type.DIRECT
                    nUnmatched++
                }
            }
            extraWaypoints.clear()
        }
        if (lastTracks.size < waypoints.size - 1) {
            refTracks = arrayOfNulls(waypoints.size - 1) // used ways for alternatives
            lastTracks = arrayOfNulls(waypoints.size - 1)
            hasDirectRouting = true
        }
        for (wp in waypoints) {
            if (wp.type == MatchedWaypoint.Type.DIRECT) {
                hasDirectRouting = true
            }
        }

        // check for a track for that target
        var nearbyTrack: OsmTrack? = null
        if (!hasDirectRouting && lastTracks[waypoints.size - 2] == null) {
            val debugInfo = StringBuilder()
            nearbyTrack = routingContext.rawTrackSourceFactory?.let { sourceFactory ->
                runCatching {
                    sourceFactory().buffered().use { source ->
                        OsmTrack.readBinary(
                            source,
                            waypoints[waypoints.size - 1],
                            routingContext.nogoChecksums,
                            routingContext.profileTimestamp,
                            debugInfo
                        )
                    }
                }.getOrNull()
            }
            if (nearbyTrack != null) {
                nUnmatched--
            }
            val found = nearbyTrack != null
            val dirty = found && nearbyTrack.isDirty
            log("read referenceTrack, found=$found dirty=$dirty $debugInfo")
        }

        if (matchedWaypoints.isEmpty()) { // could exist from the previous alternative level
            for (i in 0..<nUnmatched) {
                val mwp = MatchedWaypoint()
                mwp.waypoint = waypoints[i]
                mwp.name = waypoints[i].name
                mwp.type = waypoints[i].type
                matchedWaypoints.add(mwp)
            }
            val startSize = matchedWaypoints.size
            matchWaypointsToNodes(matchedWaypoints)
            if (startSize < matchedWaypoints.size) {
                refTracks =
                    arrayOfNulls(matchedWaypoints.size - 1) // used ways for alternatives
                lastTracks = arrayOfNulls(matchedWaypoints.size - 1)
                hasDirectRouting = true
            }

            routingContext.checkMatchedWaypointAgainstNogos(matchedWaypoints)

            // detect target islands: restricted search in inverse direction
            routingContext.inverseDirection = !routingContext.global.inverseRouting
            airDistanceCostFactor = 0.0
            for (i in 0..<matchedWaypoints.size - 1) {
                nodeLimit = maxNodesIslandCheck
                if (matchedWaypoints[i].type == MatchedWaypoint.Type.DIRECT) continue
                if (routingContext.global.inverseRouting) {
                    val seg = findTrack(matchedWaypoints[i], matchedWaypoints[i + 1], null, null, false)
                    require(!(seg == null && nodeLimit > 0)) { "start island detected for section $i" }
                } else {
                    val seg = findTrack(matchedWaypoints[i + 1], matchedWaypoints[i], null, null, false)
                    require(!(seg == null && nodeLimit > 0)) { "target island detected for section $i" }
                }
            }
            routingContext.inverseDirection = false
            nodeLimit = 0

            if (nearbyTrack != null) {
                matchedWaypoints.add(nearbyTrack.endPoint!!)
            }
        } else {
            if (lastTracks.size < matchedWaypoints.size - 1) {
                refTracks =
                    arrayOfNulls(matchedWaypoints.size - 1) // used ways for alternatives
                lastTracks = arrayOfNulls(matchedWaypoints.size - 1)
                hasDirectRouting = true
            }
        }


        routingContext.global.hasDirectRouting = hasDirectRouting

        OsmPath.seg = 1 // set segment counter
        for (i in 0..<matchedWaypoints.size - 1) {
            if (lastTracks[i] != null) {
                if (refTracks[i] == null) refTracks[i] = OsmTrack()
                refTracks[i]!!.addNodes(lastTracks[i]!!)
            }

            val seg: OsmTrack?
            val wptIndex: Int
            if (routingContext.global.inverseRouting) {
                routingContext.inverseDirection = true
                seg = searchTrack(
                    matchedWaypoints[i + 1],
                    matchedWaypoints[i],
                    null,
                    refTracks[i]
                )
                routingContext.inverseDirection = false
                wptIndex = i + 1
            } else {
                seg = searchTrack(
                    matchedWaypoints[i],
                    matchedWaypoints[i + 1],
                    if (i == matchedWaypoints.size - 2) nearbyTrack else null,
                    refTracks[i]
                )
                wptIndex = i
                if (routingContext.global.continueStraight) {
                    if (i < matchedWaypoints.size - 2) {
                        val lastPoint =
                            if (seg!!.containsNode(matchedWaypoints[i + 1].node1!!)) matchedWaypoints[i + 1].node1 else matchedWaypoints[i + 1].node2
                        val nogo = OsmNodeNamed(lastPoint!!)
                        nogo.radius = 5.0
                        nogo.name = "nogo" + (i + 1)
                        nogo.nogoWeight = 9999.0
                        nogo.isNogo = true
                        routingContext.nogopoints.add(nogo)
                    }
                }
            }
            if (seg == null) return null

            if (routingContext.ai != null) return null

            var changed = false
            if (routingContext.global.correctMisplacedViaPoints && matchedWaypoints[i].type != MatchedWaypoint.Type.DIRECT && !routingContext.allowSamewayback) {
                changed = snapPathConnection(
                    totaltrack,
                    seg,
                    if (routingContext.global.inverseRouting) matchedWaypoints[i + 1] else matchedWaypoints[i]
                )
            }
            if (wptIndex > 0) matchedWaypoints[wptIndex].indexInTrack =
                totaltrack.nodes.size - 1

            totaltrack.appendTrack(seg)
            lastTracks[i] = seg
        }

        postElevationCheck(totaltrack)

        recalcTrack(totaltrack)

        matchedWaypoints[matchedWaypoints.size - 1].indexInTrack = totaltrack.nodes.size - 1
        totaltrack.matchedWaypoints = matchedWaypoints.toMutableList()
        totaltrack.processVoiceHints(routingContext)
        totaltrack.prepareSpeedProfile(routingContext)

        totaltrack.showTime = routingContext.global.showTime
        totaltrack.params = routingContext.keyValues

        if (routingContext.poipoints.isNotEmpty()) totaltrack.pois = routingContext.poipoints

        return totaltrack
    }

    private suspend fun getExtraSegment(start: OsmPathElement, end: OsmPathElement): OsmTrack? {
        val wptlist = mutableListOf<MatchedWaypoint?>()
        val wpt1 = MatchedWaypoint()
        wpt1.waypoint = OsmNode(start.position)
        wpt1.name = "wptx1"
        wpt1.crosspoint = OsmNode(start.position)
        wpt1.node1 = OsmNode(start.position)
        wpt1.node2 = OsmNode(end.position)
        wptlist.add(wpt1)
        val wpt2 = MatchedWaypoint()
        wpt2.waypoint = OsmNode(end.position)
        wpt2.name = "wptx2"
        wpt2.crosspoint = OsmNode(end.position)
        wpt2.node2 = OsmNode(start.position)
        wpt2.node1 = OsmNode(end.position)
        wptlist.add(wpt2)

        val mwp1 = wptlist[0]
        val mwp2 = wptlist[1]

        var mid: OsmTrack?

        val corr = routingContext.global.correctMisplacedViaPoints
        routingContext.global.correctMisplacedViaPoints = false

        guideTrack = OsmTrack()
        guideTrack!!.addNode(start)
        guideTrack!!.addNode(end)

        mid = findTrack(mwp1, mwp2, null, null, false)

        guideTrack = null
        routingContext.global.correctMisplacedViaPoints = corr

        return mid
    }

    private suspend fun snapRoundaboutConnection(
        tt: OsmTrack,
        t: OsmTrack,
        indexStart: Int,
        indexEnd: Int,
        indexMeeting: Int,
        startWp: MatchedWaypoint
    ): Int {
        var indexEnd = indexEnd
        val indexMeetingBack = (if (indexMeeting == -1) tt.nodes.size - 1 else indexMeeting)
        var indexMeetingFore = 0
        var indexStartBack = indexStart
        var indexStartFore = 0

        val ptStart = tt.nodes[indexStartBack]
        val ptMeeting = tt.nodes[indexMeetingBack]
        val ptEnd = t.nodes[indexEnd]

        val bMeetingIsOnRoundabout = ptMeeting.message!!.isRoundabout
        var bMeetsRoundaboutStart = false
        var wayDistance = 0

        var i = 0
        var lastN: OsmPathElement? = null

        while (i < indexEnd) {
            val n = t.nodes[i]
            if (lastN != null) wayDistance += n.distanceTo(lastN)
            lastN = n

            if (n.positionEquals(ptStart)) {
                indexStartFore = i
                bMeetsRoundaboutStart = true
            }
            if (n.positionEquals(ptMeeting)) {
                indexMeetingFore = i
            }

            i++
        }


        if (routingContext.global.correctMisplacedViaPointsDistance > 0 &&
            wayDistance > routingContext.global.correctMisplacedViaPointsDistance
        ) {
            return 0
        }

        if (!bMeetsRoundaboutStart && bMeetingIsOnRoundabout) {
            indexEnd = indexMeetingFore
        }
        if (bMeetsRoundaboutStart && bMeetingIsOnRoundabout) {
            indexEnd = indexStartFore
        }

        val removeList = mutableListOf<OsmPathElement?>()
        if (!bMeetsRoundaboutStart) {
            indexStartBack = indexMeetingBack
            while (!tt.nodes[indexStartBack].message!!.isRoundabout) {
                indexStartBack--
                if (indexStartBack == 2) break
            }
        }

        i = indexStartBack + 1
        while (i < tt.nodes.size) {
            val n = tt.nodes[i]
            val detours = tt.getFromDetourMap(n.idFromPos)
            if (detours != null) {
                var h = detours
                while (h != null) {
                    h = h.nextHolder
                }
            }
            removeList.add(n)
            i++
        }

        var ttend: OsmPathElement? = null
        if (!bMeetingIsOnRoundabout && !bMeetsRoundaboutStart) {
            ttend = tt.nodes[indexStartBack]
            val ttend_detours = tt.getFromDetourMap(ttend.idFromPos)
            if (ttend_detours != null) {
                tt.registerDetourForId(ttend.idFromPos, null)
            }
        }

        for (e in removeList) {
            tt.nodes.remove(e)
        }
        removeList.clear()


        i = 0
        while (i < indexEnd) {
            val n = t.nodes[i]
            if (n.positionEquals(if (bMeetsRoundaboutStart) ptStart else ptEnd)) break
            if (!bMeetingIsOnRoundabout && !bMeetsRoundaboutStart && n.message!!.isRoundabout) break

            val detours = t.getFromDetourMap(n.idFromPos)
            if (detours != null) {
                var h = detours
                while (h != null) {
                    h = h.nextHolder
                }
            }
            removeList.add(n)
            i++
        }

        // time hold
        var atime = 0f
        var aenergy = 0f
        var acost = 0
        if (i > 1) {
            atime = t.nodes[i].time
            aenergy = t.nodes[i].energy
            acost = t.nodes[i].cost
        }

        for (e in removeList) {
            t.nodes.remove(e)
        }
        removeList.clear()

        if (atime > 0f) {
            for (e in t.nodes) {
                e.time -= atime
                e.energy -= aenergy
                e.cost -= acost
            }
        }

        if (!bMeetingIsOnRoundabout && !bMeetsRoundaboutStart) {
            val ttend_detours = tt.getFromDetourMap(ttend!!.idFromPos)

            var mid: OsmTrack? = null
            if (ttend_detours != null && ttend_detours.node != null) {
                mid = getExtraSegment(ttend, ttend_detours.node!!)
            }

            val tt_end = tt.nodes[tt.nodes.size - 1]

            val lastCost = tt_end.cost
            val lastTime: Float = tt_end.time
            val lastEnergy: Float = tt_end.energy
            var tmpCost = 0
            var tmpTime = 0f
            var tmpEnergy = 0f

            if (mid != null) {
                var start = false
                for (e in mid.nodes) {
                    if (start) {
                        if (e.positionEquals(ttend_detours!!.node!!)) {
                            tmpCost = e.cost
                            tmpTime = e.time
                            tmpEnergy = e.energy
                            break
                        }
                        e.cost += lastCost
                        e.time += lastTime
                        e.energy += lastEnergy
                        tt.nodes.add(e)
                    }
                    if (e.positionEquals(tt_end)) start = true
                }

                ttend_detours!!.node!!.cost = lastCost + tmpCost
                ttend_detours.node!!.time = lastTime + tmpTime
                ttend_detours.node!!.energy = lastEnergy + tmpEnergy
                tt.nodes.add(ttend_detours.node!!)
                t.nodes.add(0, ttend_detours.node!!)
            }
        }

        tt.cost = tt.nodes[tt.nodes.size - 1].cost
        t.cost = t.nodes[t.nodes.size - 1].cost

        startWp.correctedpoint = OsmNode(ptStart.position)

        return (t.nodes.size)
    }

    // check for way back on way point
    private suspend fun snapPathConnection(
        tt: OsmTrack,
        t: OsmTrack,
        startWp: MatchedWaypoint
    ): Boolean {
        if (
            startWp.name?.startsWith("via") != true &&
            startWp.name?.startsWith("rt") != true
        ) {
            return false
        }


        val ourSize = tt.nodes.size
        if (ourSize > 0) {
            tt.nodes[ourSize - 1]
            for (node in routingContext.poipoints) {
                val p0 = tt.nodes[ourSize - 2].position
                val p1 = startWp.crosspoint!!.position
                val p2 = node.position
                turnAngle(p0, p1, p2)
                val dist = node.distanceTo(startWp.crosspoint!!)
                if (dist < routingContext.global.waypointCatchingRange) return false
            }
            val removeBackList = mutableListOf<OsmPathElement?>()
            val removeForeList = mutableListOf<OsmPathElement?>()
            val removeVoiceHintList = mutableListOf<Int>()
            var last: OsmPathElement? = null
            val lastJunctions = MutableLongObjectMap<OsmPathElementHolder>()
            var newJunction: OsmPathElement?
            var newTarget: OsmPathElement? = null
            var tmpback: OsmPathElement?
            var tmpfore: OsmPathElement?
            var tmpStart: OsmPathElement? = null
            var indexback = ourSize - 1
            var indexfore = 0
            val stop = (if (indexback - maxStepsCheck > 1) indexback - maxStepsCheck else 1)
            var wayDistance = 0.0
            var nextDist: Double
            var bCheckRoundAbout = false
            var bBackRoundAbout = false
            var bForeRoundAbout = false
            var indexBackFound = 0
            var indexForeFound = 0
            var differentLanePoints = 0
            var indexMeeting = -1
            while (indexback >= 1 && indexback >= stop && indexfore < t.nodes.size) {
                tmpback = tt.nodes[indexback]
                tmpfore = t.nodes[indexfore]
                if (!bBackRoundAbout && tmpback.message != null && tmpback.message!!.isRoundabout) {
                    bBackRoundAbout = true
                    indexBackFound = indexfore
                }
                if (!bForeRoundAbout && tmpfore.message != null && tmpfore.message!!.isRoundabout ||
                    (tmpback.positionEquals(tmpfore) && tmpback.message!!.isRoundabout)
                ) {
                    bForeRoundAbout = true
                    indexForeFound = indexfore
                }
                if (indexfore == 0) {
                    tmpStart = t.nodes[0]
                } else {
                    val dirback =
                        bearing(tmpStart!!.position, tmpback.position)
                    val dirfore =
                        bearing(tmpStart.position, tmpfore.position)
                    val dirdiff = bearingDifference(dirback, dirfore)
                    // walking wrong direction
                    if (dirdiff > 60 && !bBackRoundAbout && !bForeRoundAbout) break
                }
                // seems no roundabout, only on one end
                if (bBackRoundAbout != bForeRoundAbout && indexfore - abs(indexForeFound - indexBackFound) > 8) break
                if (!tmpback.positionEquals(tmpfore)) differentLanePoints++
                if (tmpback.positionEquals(tmpfore)) indexMeeting = indexback
                bCheckRoundAbout = bBackRoundAbout && bForeRoundAbout
                if (bCheckRoundAbout) break
                indexback--
                indexfore++
            }
            //System.out.println("snap round result " + indexback + ": " + bBackRoundAbout + " - " + indexfore + "; " + bForeRoundAbout + " pts " + differentLanePoints);
            if (bCheckRoundAbout) {
                tmpback = tt.nodes[--indexback]
                while (tmpback!!.message != null && tmpback.message!!.isRoundabout) {
                    tmpback = tt.nodes[--indexback]
                }

                var ifore = ++indexfore
                var testfore = t.nodes[ifore]
                while (ifore < t.nodes.size && testfore.message != null && testfore.message!!.isRoundabout) {
                    testfore = t.nodes[ifore]
                    ifore++
                }

                snapRoundaboutConnection(tt, t, indexback, --ifore, indexMeeting, startWp)

                // remove filled arrays
                removeVoiceHintList.clear()
                removeBackList.clear()
                removeForeList.clear()
                return true
            }
            indexback = ourSize - 1
            indexfore = 0
            while (indexback >= 1 && indexback >= stop && indexfore < t.nodes.size) {
                var junctions = 0
                tmpback = tt.nodes[indexback]
                tmpfore = t.nodes[indexfore]
                if (tmpback.message != null && tmpback.message!!.isRoundabout) {
                    bCheckRoundAbout = true
                }
                if (tmpfore.message != null && tmpfore.message!!.isRoundabout) {
                    bCheckRoundAbout = true
                }
                run {
                    val dist = tmpback.distanceTo(tmpfore)
                    val detours = tt.getFromDetourMap(tmpback.idFromPos)
                    var h = detours
                    while (h != null) {
                        junctions++
                        lastJunctions.put(h.node!!.idFromPos, h)
                        h = h.nextHolder
                    }

                    if (dist == 1 && indexfore > 0) {
                        if (indexfore == 1) {
                            removeBackList.add(tt.nodes[tt.nodes.size - 1]) // last and first should be equal, so drop only on second also equal
                            removeForeList.add(t.nodes[0])
                            removeBackList.add(tmpback)
                            removeForeList.add(tmpfore)
                            removeVoiceHintList.add(tt.nodes.size - 1)
                            removeVoiceHintList.add(indexback)
                        } else {
                            removeBackList.add(tmpback)
                            removeForeList.add(tmpfore)
                            removeVoiceHintList.add(indexback)
                        }
                        nextDist = t.nodes[indexfore - 1].distanceTo(tmpfore).toDouble()
                        wayDistance += nextDist
                    }
                    if (dist > 1 || indexback == 1) {
                        if (removeBackList.isNotEmpty()) {
                            // recover last - should be the cross point
                            removeBackList.remove(removeBackList[removeBackList.size - 1])
                            removeForeList.remove(removeForeList[removeForeList.size - 1])
                            break
                        } else {
                            return false
                        }
                    }
                    indexback--
                    indexfore++
                    if (routingContext.global.correctMisplacedViaPointsDistance > 0 &&
                        wayDistance > routingContext.global.correctMisplacedViaPointsDistance
                    ) {
                        removeVoiceHintList.clear()
                        removeBackList.clear()
                        removeForeList.clear()
                        return false
                    }
                }
            }


            // time hold
            var atime = 0f
            var aenergy = 0f
            var acost = 0
            if (removeForeList.size > 1) {
                atime = t.nodes[indexfore - 1].time
                aenergy = t.nodes[indexfore - 1].energy
                acost = t.nodes[indexfore - 1].cost
            }

            for (e in removeBackList) {
                tt.nodes.remove(e)
            }
            for (e in removeForeList) {
                t.nodes.remove(e)
            }
            for (e in removeVoiceHintList) {
                tt.removeVoiceHint(e)
            }
            removeVoiceHintList.clear()
            removeBackList.clear()
            removeForeList.clear()

            if (atime > 0f) {
                for (e in t.nodes) {
                    e.time -= atime
                    e.energy -= aenergy
                    e.cost -= acost
                }
            }

            if (t.nodes.size < 2) return true
            if (tt.nodes.isEmpty()) return true
            last = if (tt.nodes.size == 1) {
                tt.nodes[0]
            } else {
                tt.nodes[tt.nodes.size - 2]
            }
            newJunction = t.nodes[0]
            newTarget = t.nodes[1]

            tt.cost = tt.nodes[tt.nodes.size - 1].cost
            t.cost = t.nodes[t.nodes.size - 1].cost

            // fill to correctedpoint
            startWp.correctedpoint = OsmNode(newJunction.position)

            return true
        }
        return false
    }

    private fun recalcTrack(t: OsmTrack) {
        var totaldist = 0
        var totaltime = 0
        var lasttime = 0f
        var lastenergy = 0f
        var speedMin = 9999f
        val directMap: MutableMap<Int, Int> = HashMap()
        var tmptime: Float
        var speed: Float
        var dist: Int
        var angle: Double

        var ascend = 0.0
        var ehb = 0.0
        val ourSize = t.nodes.size

        var eleStart = Short.MIN_VALUE
        var eleEnd = Short.MIN_VALUE
        val eleFactor = if (routingContext.global.inverseRouting) 0.25 else -0.25

        for (i in 0..<ourSize) {
            val n = t.nodes[i]
            if (n.message == null) n.message = MessageData()
            var nLast: OsmPathElement? = null
            when (i) {
                0 -> {
                    angle = 0.0
                    dist = 0
                }

                1 -> {
                    angle = 0.0
                    nLast = t.nodes[0]
                    dist = nLast.distanceTo(n)
                }

                else -> {
                    val p0 = t.nodes[i - 2].position
                    val p1 = t.nodes[i - 1].position
                    val p2 = t.nodes[i].position
                    angle = turnAngle(p0, p1, p2).angle
                    nLast = t.nodes[i - 1]
                    dist = nLast.distanceTo(n)
                }
            }
            n.message!!.linkdist = dist
            n.message!!.turnangle = angle.toFloat()
            totaldist += dist
            totaltime = (totaltime + n.time).toInt()
            tmptime = (n.time - lasttime)
            if (dist > 0) {
                speed = dist / tmptime * 3.6f
                speedMin = min(speedMin, speed)
            }
            if (tmptime == 1f) { // no time used here
                directMap[i] = dist
            }

            lastenergy = n.energy
            lasttime = n.time

            val ele = n.position.altitude
            if (ele != UNSET_ELEVATION) eleEnd = ele
            if (eleStart == UNSET_ELEVATION) eleStart = ele

            if (nLast != null) {
                val eleLast = nLast.position.altitude
                if (eleLast != UNSET_ELEVATION && ele != UNSET_ELEVATION) {
                    ehb += (eleLast - ele) * eleFactor
                }
                val filter = elevationFilter(n)
                if (ehb > 0) {
                    ascend += ehb
                    ehb = 0.0
                } else if (ehb < filter) {
                    ehb = filter
                }
            }
        }

        t.ascend = ascend.toInt()
        t.plainAscend = if (eleStart == UNSET_ELEVATION || eleEnd == UNSET_ELEVATION) {
            0
        } else {
            ((eleStart - eleEnd) * eleFactor + 0.5).toInt()
        }

        t.distance = totaldist

        //t.energy = totalenergy;
        val keys = directMap.keys.sorted()
        for (key in keys) {
            val value: Int = directMap[key]!!
            val addTime = (value / (speedMin / 3.6f))

            var addEnergy = 0.0
            if (key > 0) {
                val GRAVITY = 9.81 // in meters per second^(-2)
                val previousAltitude = t.nodes[key - 1].position.altitude
                val currentAltitude = t.nodes[key].position.altitude
                val incline =
                    if (previousAltitude == UNSET_ELEVATION || currentAltitude == UNSET_ELEVATION) {
                        0.0
                    } else {
                        (encodedAltitudeToMeters(previousAltitude) - encodedAltitudeToMeters(currentAltitude)) / value
                    }
                val f_roll =
                    routingContext.global.totalMass * GRAVITY * (routingContext.global.defaultC_r + incline)
                val spd = speedMin / 3.6
                addEnergy = value * (routingContext.global.S_C_x * spd * spd + f_roll)
            }
            for (j in key..<ourSize) {
                val n = t.nodes[j]
                n.time += addTime
                n.energy += addEnergy.toFloat()
            }
        }
        t.energy = t.nodes[t.nodes.size - 1].energy.toInt()

        log("track total distance=${t.distance} ascend=${t.ascend}")
    }

    /**
     * find the elevation type for position
     * to determine the filter value
     *
     * @param n the point
     * @return the filter value for 1sec / 3sec elevation source
     */
    private fun elevationFilter(n: OsmPos): Double {
        if (nodesCache != null) {
            val r = nodesCache!!.getElevationType(n.position)
            if (r == 1) return -5.0
        }
        return -10.0
    }

    // geometric position matching finding the nearest routable way-section
    private fun matchWaypointsToNodes(unmatchedWaypoints: MutableList<MatchedWaypoint>) {
        resetCache(false)
        val useDynamicDistance = routingContext.global.useDynamicDistance
        val bAddBeeline = routingContext.global.buildBeelineOnRange
        var range = routingContext.global.waypointCatchingRange
        var ok = nodesCache!!.matchWaypointsToNodes(unmatchedWaypoints, range, islandNodePairs)
        if (!ok && useDynamicDistance) {
            log("second check for way points")
            resetCache(false)
            range = -maxDynamicRange.toDouble()
            val tmp =
                unmatchedWaypoints.filter { it.crosspoint == null || it.radius >= routingContext.global.waypointCatchingRange }
                    .toMutableList()
            ok = nodesCache!!.matchWaypointsToNodes(tmp, range, islandNodePairs)
        }
        if (!ok) {
            for (mwp in unmatchedWaypoints) {
                requireNotNull(mwp.crosspoint) { "${mwp.name}-position not mapped in existing datafile" }
            }
        }
        // add beeline points when not already done
        if (useDynamicDistance && !useNodePoints && bAddBeeline) {
            val waypoints = mutableListOf<MatchedWaypoint>()
            for (i in unmatchedWaypoints.indices) {
                val wp = unmatchedWaypoints[i]
                if (wp.waypoint!!.distanceTo(wp.crosspoint!!) > routingContext.global.waypointCatchingRange) {
                    val nmw = MatchedWaypoint()
                    if (i == 0) {
                        var onn = OsmNodeNamed(wp.waypoint!!).apply { name = "from" }
                        nmw.apply {
                            waypoint = onn
                            name = onn.name
                            crosspoint = OsmNode(wp.waypoint!!.position)
                            type = MatchedWaypoint.Type.DIRECT
                        }
                        onn = OsmNodeNamed(wp.crosspoint!!).apply { name = "${wp.name}_add" }
                        wp.waypoint = onn
                        waypoints.add(nmw)
                        wp.name += "_add"
                        waypoints.add(wp)
                    } else {
                        val onn = OsmNodeNamed(wp.crosspoint!!).apply { name = "${wp.name}_add" }
                        nmw.apply {
                            waypoint = onn
                            crosspoint = OsmNode(wp.crosspoint!!.position)
                            node1 = OsmNode(wp.node1!!.position)
                            node2 = OsmNode(wp.node2!!.position)
                            type = MatchedWaypoint.Type.DIRECT
                            name = wp.name
                        }

                        waypoints.add(nmw)
                        wp.name += "_add"
                        waypoints.add(wp)
                        if (wp.name?.startsWith("via") == true) {
                            wp.type = MatchedWaypoint.Type.DIRECT
                            val emw = MatchedWaypoint().apply {
                                val onn2 =
                                    OsmNodeNamed(wp.crosspoint!!).apply { name = "${wp.name}_2" }
                                this.name = onn2.name
                                this.waypoint = onn2
                                this.crosspoint =
                                    OsmNode(nmw.crosspoint!!.position)
                                this.node1 = OsmNode(nmw.node1!!.position)
                                this.node2 = OsmNode(nmw.node2!!.position)
                                this.type = MatchedWaypoint.Type.SHAPING
                            }
                            waypoints.add(emw)
                        }
                        wp.crosspoint = OsmNode(wp.waypoint!!.position)
                    }
                } else {
                    waypoints.add(wp)
                }
            }
            unmatchedWaypoints.clear()
            unmatchedWaypoints.addAll(waypoints)
        }
    }

    private suspend fun searchTrack(
        startWp: MatchedWaypoint,
        endWp: MatchedWaypoint,
        nearbyTrack: OsmTrack?,
        refTrack: OsmTrack?
    ): OsmTrack? {
        // remove nogos with waypoints inside
        try {
            if (startWp.type != MatchedWaypoint.Type.DIRECT) {
                return searchRoutedTrack(startWp, endWp, nearbyTrack, refTrack)
            }

            // we want a beeline-segment
            var path = routingContext.createPath(OsmLink(null, startWp.crosspoint))
            path = routingContext.createPath(
                path,
                OsmLink(startWp.crosspoint, endWp.crosspoint),
                null,
                false
            )
            return compileTrack(path, false)
        } finally {
            routingContext.restoreNogoList()
        }
    }

    private suspend fun searchRoutedTrack(
        startWp: MatchedWaypoint?,
        endWp: MatchedWaypoint?,
        nearbyTrack: OsmTrack?,
        refTrack: OsmTrack?
    ): OsmTrack? {
        var track: OsmTrack? = null
        val airDistanceCostFactors = doubleArrayOf(
            routingContext.global.pass1coefficient,
            routingContext.global.pass2coefficient
        )
        var isDirty = false
        var dirtyMessage: RoutingException? = null

        if (nearbyTrack != null) {
            airDistanceCostFactor = 0.0
            try {
                track = findTrack(startWp, endWp, nearbyTrack, refTrack, true)
            } catch (re: RoutingException) {
                // fast partial recalcs: if that timed out, but we had a match,
                // build the concatenation from the partial and the nearby track
                if (matchPath != null) {
                    track = mergeTrack(matchPath!!, nearbyTrack)
                    isDirty = true
                    dirtyMessage = re
                    log("using fast partial recalc")
                }
            }
        }

        if (track == null) {
            for (cfi in airDistanceCostFactors.indices) {
                if (cfi > 0) lastAirDistanceCostFactor = max(0.0, airDistanceCostFactors[cfi - 1])
                airDistanceCostFactor = airDistanceCostFactors[cfi]

                if (airDistanceCostFactor < 0.0) {
                    continue
                }

                var t: OsmTrack?
                t = findTrack(startWp, endWp, track, refTrack, false)
                if (routingContext.ai != null) return t

                if (t == null && track != null && matchPath != null) {
                    // ups, didn't find it, use a merge
                    t = mergeTrack(matchPath!!, track)
                    log("using sloppy merge cause pass1 didn't reach destination")
                }
                if (t != null) {
                    track = t
                } else {
                    throw RoutingException("no track found at pass=$cfi")
                }
            }
        }
        requireNotNull(track) { "no track found" }

        val wasClean = nearbyTrack != null && !nearbyTrack.isDirty
        if (refTrack == null && !(wasClean && isDirty)) { // do not overwrite a clean with a dirty track
            log("supplying new reference track, dirty=$isDirty")
            track.endPoint = endWp
            track.nogoChecksums = routingContext.nogoChecksums
            track.profileTimestamp = routingContext.profileTimestamp
            track.isDirty = isDirty
        }

        if (!wasClean && isDirty) {
            throw dirtyMessage!!
        }

        // final run for verbose log info and detail nodes
        airDistanceCostFactor = 0.0
        lastAirDistanceCostFactor = 0.0
        guideTrack = track
        try {
            val tt = findTrack(startWp, endWp, null, refTrack, false)
            requireNotNull(tt) { "error re-tracking track" }
            return tt
        } finally {
            guideTrack = null
        }
    }


    private fun resetCache(detailed: Boolean) {
        if (nodesCache != null) {
            log("NodesCache status=${nodesCache!!.formatStatus()} before reset")
        }
        nodesCache = NodesCache(
            routingContext.mapSource,
            routingContext.way,
            routingContext.global.forceSecondaryData,
            routingContext.memoryPolicy,
            nodesCache,
            detailed
        )
        islandNodePairs.clearTempPairs()
    }

    private fun getStartPath(
        n1: OsmNode,
        n2: OsmNode?,
        mwp: MatchedWaypoint,
        endPos: OsmNodeNamed?,
        sameSegmentSearch: Boolean
    ): OsmPath? {
        if (endPos != null) {
            endPos.radius = 1.5
        }
        val p = getStartPath(n1, n2, OsmNodeNamed(mwp.crosspoint!!), endPos, sameSegmentSearch)

        // special case: start+end on same segment
        if (p != null && p.cost >= 0 && sameSegmentSearch && endPos != null && endPos.radius < 1.5) {
            p.treedepth = 0 // hack: mark for the final-check
        }
        return p
    }


    private fun getStartPath(
        n1: OsmNode,
        n2: OsmNode?,
        wp: OsmNodeNamed,
        endPos: OsmNodeNamed?,
        sameSegmentSearch: Boolean
    ): OsmPath? {
        try {
            routingContext.setWaypoint(wp, if (sameSegmentSearch) endPos else null, false)
            var bestPath: OsmPath? = null
            var bestLink: OsmLink? = null
            val startLink = OsmLink(null, n1)
            val startPath = routingContext.createPath(startLink)
            startLink.addLinkHolder(startPath, null)
            var minradius = 1e10
            var link = n1.firstlink
            while (link != null) {
                val nextNode = link.getTarget(n1)
                if (nextNode!!.isHollow) {
                    link = link.getNext(n1)
                    continue  // border node?
                }
                if (nextNode.firstlink == null) {
                    link = link.getNext(n1)
                    continue  // don't care about dead ends
                }
                if (nextNode === n1) {
                    link = link.getNext(n1)
                    continue  // ?
                }
                if (nextNode !== n2) {
                    link = link.getNext(n1)
                    continue  // just that link
                }

                wp.radius = 1.5
                val testPath = routingContext.createPath(startPath, link, null, guideTrack != null)
                testPath.airdistance = if (endPos == null) 0 else nextNode.distanceTo(endPos)
                if (wp.radius < minradius) {
                    bestPath = testPath
                    minradius = wp.radius
                    bestLink = link
                }
                link = link.getNext(n1)
            }
            bestLink?.addLinkHolder(bestPath!!, n1)
            if (bestPath != null) bestPath.treedepth = 1

            return bestPath
        } finally {
            routingContext.unsetWaypoint()
        }
    }

    private suspend fun findTrack(
        startWp: MatchedWaypoint?,
        endWp: MatchedWaypoint?,
        costCuttingTrack: OsmTrack?,
        refTrack: OsmTrack?,
        fastPartialRecalc: Boolean
    ): OsmTrack? {
        try {
            val wpts2 = mutableListOf<OsmNode>()
            if (startWp != null) wpts2.add(startWp.waypoint!!)
            if (endWp != null) wpts2.add(endWp.waypoint!!)
            routingContext.cleanNogoList(wpts2)

            val detailed = guideTrack != null
            resetCache(detailed)
            nodesCache!!.nodesMap.cleanupMode =
                if (detailed) {
                    OsmNodesMap.CleanupMode.COUNT_ONLY
                } else if (routingContext.global.considerTurnRestrictions) {
                    OsmNodesMap.CleanupMode.TREE
                } else {
                    OsmNodesMap.CleanupMode.PENINSULAS
                }
            return _findTrack(
                startWp!!,
                endWp,
                costCuttingTrack,
                refTrack,
                fastPartialRecalc
            )
        } finally {
            routingContext.restoreNogoList()
            nodesCache!!.clean(false) // clean only non-virgin caches
        }
    }


    private suspend fun _findTrack(
        startWp: MatchedWaypoint,
        endWp: MatchedWaypoint?,
        costCuttingTrack: OsmTrack?,
        refTrack: OsmTrack?,
        fastPartialRecalc: Boolean
    ): OsmTrack? {
        var fastPartialRecalc = fastPartialRecalc
        val verbose = guideTrack != null
        val supportsGuideTrackDetours =
            guideTrack != null && routingContext.generateTurns

        val currentGuideTrack = guideTrack
        val useAcceptedStdSearch = currentGuideTrack == null && routingContext.isStdModel()
        var maxTotalCost =
            if (currentGuideTrack != null) {
                currentGuideTrack.cost + 5000
            } else {
                BorderLoadDeferralPolicy.UNBOUNDED_MAX_TOTAL_COST
            }
        var firstMatchCost = BorderLoadDeferralPolicy.UNBOUNDED_MAX_TOTAL_COST

        if (costCuttingTrack != null && costCuttingTrack.cost > 0) {
            // Any previously found track for the same section is a valid upper bound.
            maxTotalCost = min(maxTotalCost, costCuttingTrack.cost)
        }

        log("findtrack with airDistanceCostFactor=$airDistanceCostFactor")
        if (costCuttingTrack != null) log("costCuttingTrack.cost=${costCuttingTrack.cost}")

        matchPath = null
        var nodesVisited = 0

        val startNode1 = requireNotNull(startWp.node1)
        val startNode2 = requireNotNull(startWp.node2)
        val startNodeId1 = startNode1.idFromPos
        val startNodeId2 = startNode2.idFromPos
        val endNode1 = endWp?.node1
        val endNode2 = endWp?.node2
        val endNodeId1 = endNode1?.idFromPos ?: -1L
        val endNodeId2 = endNode2?.idFromPos ?: -1L
        var end1: OsmNode?
        var end2: OsmNode?
        var endPos: OsmNodeNamed? = null

        val cache = nodesCache ?: return null
        var sameSegmentSearch = false
        val start1 = cache.getGraphNode(startNode1)
        val start2 = cache.getGraphNode(startNode2)
        if (endWp != null) {
            end1 = cache.getGraphNode(requireNotNull(endNode1))
            end2 = cache.getGraphNode(requireNotNull(endNode2))
            cache.nodesMap.endNode1 = end1
            cache.nodesMap.endNode2 = end2
            endPos = OsmNodeNamed(requireNotNull(endWp.crosspoint))
            sameSegmentSearch =
                (start1 === end1 && start2 === end2) || (start1 === end2 && start2 === end1)
        }
        if (!cache.obtainNonHollowNode(start1)) {
            return null
        }
        cache.expandHollowLinkTargets(start1)
        if (!cache.obtainNonHollowNode(start2)) {
            return null
        }
        cache.expandHollowLinkTargets(start2)


        routingContext.startDirectionValid =
            routingContext.forceUseStartDirection || fastPartialRecalc
        routingContext.startDirectionValid =
            routingContext.startDirectionValid and (routingContext.startDirection != null && !routingContext.inverseDirection)
        if (routingContext.startDirectionValid) {
            log("using start direction ${routingContext.startDirection}")
        }

        var startPath1 = getStartPath(start1, start2, startWp, endPos, sameSegmentSearch)
        var startPath2 = getStartPath(start2, start1, startWp, endPos, sameSegmentSearch)
        if (startPath1 == null) return null
        if (startPath2 == null) return null
        if (useAcceptedStdSearch) {
            startPath1 = routingContext.toAcceptedStartPath(startPath1)
            startPath2 = routingContext.toAcceptedStartPath(startPath2)
            requireNotNull(startPath1.link).setFirstLinkHolder(null, startPath1.sourceNode)
            requireNotNull(startPath2.link).setFirstLinkHolder(null, startPath2.sourceNode)
            requireNotNull(startPath1.link).addLinkHolder(startPath1, startPath1.sourceNode)
            requireNotNull(startPath2.link).addLinkHolder(startPath2, startPath2.sourceNode)
        }

        // check for an INITIAL match with the cost-cutting-track
        if (costCuttingTrack != null) {
            val pe1 = costCuttingTrack.getLink(startNodeId1, startNodeId2)
            if (pe1 != null) {
                log("initialMatch pe1.cost=${pe1.cost}")
                var c = startPath1.cost - pe1.cost
                if (c < 0) c = 0
                if (c < firstMatchCost) firstMatchCost = c
            }

            val pe2 = costCuttingTrack.getLink(startNodeId2, startNodeId1)
            if (pe2 != null) {
                log("initialMatch pe2.cost=${pe2.cost}")
                var c = startPath2.cost - pe2.cost
                if (c < 0) c = 0
                if (c < firstMatchCost) firstMatchCost = c
            }
        }

        openSet.clear()
        addToOpenset(startPath1)
        addToOpenset(startPath2)

        val openBorderList = ArrayList<OsmPath>(4096)
        val borderLoadDeferral = BorderLoadDeferralPolicy()
        var testCandidate = StdPathCandidate()
        var bestCandidate = StdPathCandidate()

        // Pre-compute end position integer coordinates once to avoid repeated double→int conversions in the hot loop
        val endPosition = endPos?.position ?: Position.ZERO

        while (true) {
            currentCoroutineContext().ensureActive()

            val path: OsmPath? = openSet.popLowestKeyValue()
            if (path == null) {
                if (openBorderList.isEmpty()) {
                    break
                }
                for (p in openBorderList) {
                    openSet.add(p.cost + (p.airdistance * airDistanceCostFactor).toInt(), p)
                }
                openBorderList.clear()
                borderLoadDeferral.onDeferredFrontierRequeued()
                continue
            }

            if (path.airdistance == -1) {
                continue
            }

            val pathTargetNode = requireNotNull(path.targetNode)
            if (directWeaving && cache.hasHollowLinkTargets(pathTargetNode)) {
                if (!borderLoadDeferral.deferringBorderLoads) {
                    if (!cache.nodesMap.isInMemoryBounds(openSet.size, false)) {
                        cache.nodesMap.collectOutreachers()
                        while (true) {
                            val p3: OsmPath? = openSet.popLowestKeyValue()
                            if (p3 == null) break
                            val p3TargetNode = p3.targetNode ?: continue
                            if (p3.airdistance != -1 && cache.nodesMap.canEscape(p3TargetNode)) {
                                openBorderList.add(p3)
                            }
                        }
                        cache.nodesMap.clearTemp()
                        for (p in openBorderList) {
                            openSet.add(
                                p.cost + (p.airdistance * airDistanceCostFactor).toInt(),
                                p
                            )
                        }
                        openBorderList.clear()
                        if (!cache.nodesMap.isInMemoryBounds(openSet.size, true)) {
                            when (borderLoadDeferral.onCleanupStillOverBudget(maxTotalCost, fastPartialRecalc)) {
                                BorderLoadDeferralPolicy.OverBudgetAction.DEFER_BORDER_LOADS -> Unit
                                BorderLoadDeferralPolicy.OverBudgetAction.MEMORY_LIMIT_REACHED -> {
                                    throw RoutingException("memory limit reached")
                                }
                            }
                        }
                    }
                }
                if (borderLoadDeferral.deferringBorderLoads) {
                    openBorderList.add(path)
                    continue
                }
            }
            borderLoadDeferral.onPathAcceptedForExpansion()


            if (fastPartialRecalc && matchPath != null && costCuttingTrack != null && path.cost > 30L * firstMatchCost && !costCuttingTrack.isDirty) {
                log("early exit: firstMatchCost=$firstMatchCost path.cost=${path.cost}")

                // use an early exit, unless there's a realistc chance to complete within the timeout
                //                    if (path.cost > maxTotalCost / 2 && System.currentTimeMillis() - startTime < maxRunningTime / 3) {
                log("early exit supressed, running for completion, resetting timeout")
                //                    startTime = System.currentTimeMillis()
                fastPartialRecalc = false
                //                    } else {
                //                        throw IllegalArgumentException("early exit for a close recalc")
                //                    }
            }

            if (nodeLimit > 0) { // check node-limit for target island search
                if (--nodeLimit == 0) {
                    return null
                }
            }

            nodesVisited++
            linksProcessed++

            val currentLink = requireNotNull(path.link)
            val sourceNode = requireNotNull(path.sourceNode)
            val currentNode = pathTargetNode

            if (currentLink.isLinkUnused) {
                continue
            }

            val currentNodeId = currentNode.idFromPos
            val sourceNodeId = sourceNode.idFromPos

            if (!path.didEnterDestinationArea()) {
                islandNodePairs.addTempPair(sourceNodeId, currentNodeId)
            }

            if (path.treedepth != 1) {
                if (path.treedepth == 0) { // hack: sameSegment Paths marked treedepth=0 to pass above check
                    path.treedepth = 1
                }

                if ((sourceNodeId == endNodeId1 && currentNodeId == endNodeId2)
                    || (sourceNodeId == endNodeId2 && currentNodeId == endNodeId1)
                ) {
                    // track found, compile
                    log("found track at cost ${path.cost} nodesVisited = $nodesVisited")
                    val t = compileTrack(path, verbose)
                    t.showspeed = routingContext.global.showspeed
                    t.showSpeedProfile = routingContext.global.showSpeedProfile
                    return t
                }

                // check for a match with the cost-cutting-track
                if (costCuttingTrack != null) {
                    val pe = costCuttingTrack.getLink(sourceNodeId, currentNodeId)
                    if (pe != null) {
                        // remember first match cost for fast termination of partial recalcs
                        var parentcost = path.originCost

                        // hitting start-element of costCuttingTrack?
                        val c = path.cost - parentcost - pe.cost
                        if (c > 0) parentcost += c

                        if (parentcost < firstMatchCost) firstMatchCost = parentcost

                        val costEstimate = (path.cost
                                + path.elevationCorrection()
                                + (costCuttingTrack.cost - pe.cost))
                        if (costEstimate <= maxTotalCost) {
                            matchPath = PathTraceBuilder.toMatchPathElement(path)
                        }
                        if (costEstimate < maxTotalCost) {
                            log("maxcost $maxTotalCost -> $costEstimate")
                            maxTotalCost = costEstimate
                        }
                    }
                }
            }

            val firstLinkHolder = currentLink.getFirstLinkHolder(sourceNode)
            var linkHolder = firstLinkHolder
            while (linkHolder != null) {
                (linkHolder as OsmPath).airdistance =
                    -1 // invalidate the entry in the open set;
                linkHolder = linkHolder.nextForLink
            }

            if (path.treedepth > 1) {
                val isBidir = currentLink.isBidirectional
                sourceNode.unlinkLink(currentLink)

                // if the counterlink is alive and does not yet have a path, remove it
                if (isBidir && currentLink.getFirstLinkHolder(currentNode) == null && !routingContext.global.considerTurnRestrictions) {
                    currentNode.unlinkLink(currentLink)
                }
            }

            // recheck cutoff before doing expensive stuff
            val addDiff = 100
            if (path.cost + path.airdistance > maxTotalCost + addDiff) {
                continue
            }

            cache.nodesMap.currentMaxCost = maxTotalCost
            cache.nodesMap.currentPathCost = path.cost
            cache.nodesMap.destination = endPos

            routingContext.firstPrePath = null

            candidateLinks.clear()
            candidateNodes.clear()
            var link = currentNode.firstlink
            while (link != null) {
                val nextNode = link.getTarget(currentNode)

                val concreteNextNode = requireNotNull(nextNode)
                if (!cache.obtainNonHollowNode(concreteNextNode)) {
                    link = link.getNext(currentNode)
                    continue  // border node?
                }
                if (concreteNextNode.firstlink == null) {
                    link = link.getNext(currentNode)
                    continue  // don't care about dead ends
                }
                if (concreteNextNode === sourceNode) {
                    link = link.getNext(currentNode)
                    continue  // border node?
                }

                val prePath = routingContext.createPrePath(path, link)
                if (prePath != null) {
                    prePath.next = routingContext.firstPrePath
                    routingContext.firstPrePath = prePath
                }
                candidateLinks.add(link)
                candidateNodes.add(concreteNextNode)
                link = link.getNext(currentNode)
            }

            for (ci in candidateLinks.indices) {
                val candidateLink = candidateLinks[ci]
                val nextNode = candidateNodes[ci]

                if (currentGuideTrack != null) {
                    val gidx = path.treedepth + 1
                    if (gidx >= currentGuideTrack.nodes.size) {
                        continue
                    }
                    val guideNode =
                        currentGuideTrack.nodes[if (routingContext.global.inverseRouting) currentGuideTrack.nodes.size - 1 - gidx else gidx]
                    val nextId = nextNode.idFromPos
                    if (nextId != guideNode.idFromPos) {
                        // not along the guide-track, discard, but register for voice-hint processing
                        if (supportsGuideTrackDetours) {
                            val detour =
                                routingContext.createPath(path, candidateLink, refTrack, true)
                            if (detour.cost >= 0.0 && nextId != startNodeId1 && nextId != startNodeId2) {
                                currentGuideTrack.registerDetourForId(
                                    currentNode.idFromPos,
                                    PathTraceBuilder.toDetourElement(detour)
                                )
                            }
                            routingContext.recyclePath(detour)
                        }
                        continue
                    }
                }

                var bestPath: OsmPath? = null
                var hasBestCandidate = false

                var isFinalLink = false
                val targetNodeId = nextNode.idFromPos
                if (currentNodeId == endNodeId1 || currentNodeId == endNodeId2) {
                    if (targetNodeId == endNodeId1 || targetNodeId == endNodeId2) {
                        isFinalLink = true
                    }
                }

                var linkHolder = firstLinkHolder
                while (linkHolder != null) {
                    val otherPath = linkHolder as OsmPath
                    try {
                        if (isFinalLink) {
                            val finalEndPos = requireNotNull(endPos)
                            finalEndPos.radius =
                                1.5 // 1.5 meters is the upper limit that will not change the unit-test result..
                            routingContext.setWaypoint(finalEndPos, true)
                        }
                        val testPath = if (useAcceptedStdSearch) {
                            val accepted = routingContext.evaluateStdCandidateScratch(
                                testCandidate,
                                otherPath as AcceptedPath,
                                currentNode,
                                nextNode,
                                candidateLink,
                                refTrack,
                            )
                            if (accepted && (!hasBestCandidate || testCandidate.cost < bestCandidate.cost) &&
                                currentNode.idFromPos != nextNode.idFromPos
                            ) {
                                val swap = bestCandidate
                                bestCandidate = testCandidate
                                testCandidate = swap
                                hasBestCandidate = true
                            }
                            null
                        } else {
                            routingContext.createPath(
                                otherPath,
                                candidateLink,
                                refTrack,
                                currentGuideTrack != null
                            )
                        }
                        if (testPath != null && testPath.cost >= 0 && (bestPath == null || testPath.cost < bestPath.cost) &&
                            (requireNotNull(testPath.sourceNode).idFromPos != requireNotNull(
                                testPath.targetNode
                            ).idFromPos)
                        ) {
                            bestPath?.let { routingContext.recyclePath(it) }
                            bestPath = testPath
                        } else {
                            testPath?.let { routingContext.recyclePath(it) }
                        }
                    } finally {
                        if (isFinalLink) {
                            routingContext.unsetWaypoint()
                        }
                    }
                    linkHolder = linkHolder.nextForLink
                }
                if (useAcceptedStdSearch && hasBestCandidate) {
                    bestCandidate.airdistance = if (isFinalLink) 0 else {
                        max(1, distance(nextNode.position, endPosition).roundToInt())
                    }

                    val inRadius = boundary == null || boundary!!.isInBoundary(nextNode, bestCandidate.cost)

                    if (inRadius && (isFinalLink || bestCandidate.cost + bestCandidate.airdistance <= (if (lastAirDistanceCostFactor != 0.0) maxTotalCost * lastAirDistanceCostFactor else maxTotalCost.toDouble()) + addDiff)) {
                        var dominator = candidateLink.getFirstLinkHolder(currentNode)
                        while (dominator != null) {
                            val dp = dominator as AcceptedPath
                            if (dp.airdistance != -1 && bestCandidate.definitlyWorseThan(dp)) {
                                break
                            }
                            dominator = dominator.nextForLink
                        }

                        if (dominator == null) {
                            val acceptedPath = bestCandidate.snapshotAccepted(includeOriginCost = fastPartialRecalc)
                            acceptedPath.treedepth = path.treedepth + 1
                            if (routingContext.pm is StdModel) {
                                (routingContext.pm as StdModel).recordAcceptedPathSnapshot()
                            }
                            candidateLink.addLinkHolder(acceptedPath, currentNode)
                            addToOpenset(acceptedPath)
                        }
                    }
                } else if (bestPath != null) {
                    bestPath.airdistance = if (isFinalLink) 0 else {
                        max(1, distance(nextNode.position, endPosition).roundToInt())
                    }

                    val inRadius =
                        boundary == null || boundary!!.isInBoundary(nextNode, bestPath.cost)

                    if (inRadius && (isFinalLink || bestPath.cost + bestPath.airdistance <= (if (lastAirDistanceCostFactor != 0.0) maxTotalCost * lastAirDistanceCostFactor else maxTotalCost.toDouble()) + addDiff)) {
                        // add only if this may beat an existing path for that link
                        var dominator = candidateLink.getFirstLinkHolder(currentNode)
                        while (dominator != null) {
                            val dp = dominator as OsmPath
                            if (dp.airdistance != -1 && bestPath.definitlyWorseThan(dp)) {
                                break
                            }
                            dominator = dominator.nextForLink
                        }

                        if (dominator == null) {
                            bestPath.treedepth = path.treedepth + 1
                            if (routingContext.pm is StdModel) {
                                (routingContext.pm as StdModel).recordAcceptedPathSnapshot()
                            }
                            candidateLink.addLinkHolder(bestPath, currentNode)
                            addToOpenset(bestPath)
                        } else {
                            routingContext.recyclePath(bestPath)
                        }
                    } else {
                        routingContext.recyclePath(bestPath)
                    }
                }
            }
        }

        if (nodesVisited < maxNodesIslandCheck && islandNodePairs.freezeCount < 5) {
            throw RoutingIslandException()
        }

        return null
    }

    private fun addToOpenset(path: OsmPath) {
        if (path.cost >= 0) {
            openSet.add(path.cost + (path.airdistance * airDistanceCostFactor).toInt(), path)
        }
    }

    private fun compileTrack(path: OsmPath, verbose: Boolean): OsmTrack {
        var element: OsmPathElement? = PathTraceBuilder.toFinalTrackElement(path)

        // for final track, cut endnode
        if (guideTrack != null && requireNotNull(element).origin != null) {
            element = element.origin
        }

        val firstElement = requireNotNull(element)
        val totalTime = firstElement.time
        val totalEnergy = firstElement.energy

        val track = OsmTrack()
        track.cost = path.cost
        track.energy = path.totalEnergy.toInt()
        val compiledNodes = ArrayList<OsmPathElement>()

        var distance = 0

        if (routingContext.global.inverseRouting) -0.25 else 0.25
        while (element != null) {
            if (guideTrack != null && element.message == null) {
                element.message = MessageData()
            }
            val nextElement = element.origin
            // ignore double element
            if (nextElement != null && nextElement.positionEquals(element)) {
                element = nextElement
                continue
            }
            if (routingContext.global.inverseRouting) {
                element.time = totalTime - element.time
                element.energy = totalEnergy - element.energy
            }
            compiledNodes.add(element)

            if (nextElement != null) {
                distance += element.distanceTo(nextElement)
            }
            element = nextElement
        }

        if (routingContext.global.inverseRouting) {
            track.nodes.addAll(compiledNodes)
        } else {
            for (idx in compiledNodes.lastIndex downTo 0) {
                track.nodes.add(compiledNodes[idx])
            }
        }

        track.distance = distance
        log("track total distance=${track.distance}")
        track.buildMap()

        // for final track..
        if (guideTrack != null) {
            track.replaceDetours(guideTrack!!)
        }
        return track
    }

    private fun mergeTrack(match: OsmPathElement, oldTrack: OsmTrack): OsmTrack {
        log("merging match=${match.cost} with oldTrack=${oldTrack.cost}")
        var element: OsmPathElement? = match
        val track = OsmTrack()
        track.cost = oldTrack.cost

        while (element != null) {
            track.addNode(element)
            element = element.origin
        }
        var lastId: Long = 0
        val id1 = match.idFromPos
        val id0 = match.origin?.idFromPos ?: 0
        var appending = false
        for (n in oldTrack.nodes) {
            if (appending) {
                track.nodes.add(n)
            }

            val id = n.idFromPos
            if (id == id1 && lastId == id0) {
                appending = true
            }
            lastId = id
        }

        track.buildMap()
        return track
    }
}
