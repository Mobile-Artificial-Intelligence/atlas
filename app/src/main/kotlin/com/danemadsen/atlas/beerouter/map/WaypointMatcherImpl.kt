package com.danemadsen.atlas.beerouter.map

import com.danemadsen.atlas.beerouter.codec.WaypointMatcher
import com.danemadsen.atlas.beerouter.geo.CheapAngleMeter.bearing
import com.danemadsen.atlas.beerouter.geo.CheapAngleMeter.bearingDifference
import com.danemadsen.atlas.beerouter.geo.CheapAngleMeter.normalizeAngle
import com.danemadsen.atlas.beerouter.geo.CheapAngleMeter.rawBearing
import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.geo.coordinateScaleAt
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * the WaypointMatcher is feeded by the decoder with geoemtries of ways that are
 * already check for allowed access according to the current routing profile
 *
 *
 * It matches these geometries against the list of waypoints to find the best
 * match for each waypoint
 */
public class WaypointMatcherImpl(
    waypoints: MutableList<MatchedWaypoint>,
    maxDistance: Double,
    islandPairs: OsmNodePairSet
) : WaypointMatcher {
    private val waypoints: MutableList<MatchedWaypoint>
    private val islandPairs: OsmNodePairSet

    private var startLon: Int = 0
    private var startLat: Int = 0
    private var targetLon: Int = 0
    private var targetLat: Int = 0
    private var anyUpdate = false
    private var lastLon: Int = 0
    private var lastLat: Int = 0
    public var useAsStartWay: Boolean = true
    private var maxDistance: Double
    public var useDynamicRange: Boolean = false

    private val comparator: Comparator<MatchedWaypoint> =
        compareBy({ it.radius }, { it.directionDiff })

    init {
        require(waypoints.isNotEmpty()) { "waypoints must not be empty" }
        this.waypoints = waypoints
        this.islandPairs = islandPairs
        var effectiveMaxDistance = maxDistance
        if (effectiveMaxDistance < 0.0) {
            effectiveMaxDistance *= -1.0
            useDynamicRange = true
        }
        this.maxDistance = effectiveMaxDistance

        var last: MatchedWaypoint? = null
        for (mwp in waypoints) {
            mwp.radius = effectiveMaxDistance
            if (last != null && mwp.directionToNext == -1.0) {
                last.directionToNext = bearing(
                    requireNotNull(last.waypoint).position,
                    requireNotNull(mwp.waypoint).position
                )
            }
            last = mwp
        }
        // last point has no angle so we are looking back
        val lastWaypoint = requireNotNull(last)
        val previousIndex = waypoints.lastIndex - 1
        lastWaypoint.directionToNext = if (previousIndex < 0) {
            -1.0
        } else {
            bearing(
                requireNotNull(lastWaypoint.waypoint).position,
                requireNotNull(waypoints[previousIndex].waypoint).position
            )
        }
    }

    private fun checkSegment(startLon: Int, startLat: Int, endLon: Int, endLat: Int) {
        // todo: bounding-box pre-filter

        val scale = coordinateScaleAt((startLat + endLat) shr 1)
        val dlon2m = scale.longitudeToMeters
        val dlat2m = scale.latitudeToMeters

        val dx = (endLon - startLon) * dlon2m
        val dy = (endLat - startLat) * dlat2m
        val d = sqrt(dy * dy + dx * dx)

        if (d == 0.0) return

        //for ( MatchedWaypoint mwp : waypoints )
        for (i in waypoints.indices) {
            if (!useAsStartWay && i == 0) continue
            val mwp = waypoints[i]

            if (mwp.type == MatchedWaypoint.Type.DIRECT &&
                (i == 0 ||
                        waypoints[i - 1].type == MatchedWaypoint.Type.DIRECT)
            ) {
                if (mwp.crosspoint == null) {
                    mwp.crosspoint = OsmNode().also { it.position = mwp.waypoint!!.position }
                    mwp.hasUpdate = true
                    anyUpdate = true
                }
                continue
            }

            val wp = mwp.waypoint!!
            val waypointPosition = wp.position
            val x1 = (startLon - waypointPosition.longitude) * dlon2m
            val y1 = (startLat - waypointPosition.latitude) * dlat2m
            val x2 = (endLon - waypointPosition.longitude) * dlon2m
            val y2 = (endLat - waypointPosition.latitude) * dlat2m
            val r12 = x1 * x1 + y1 * y1
            val r22 = x2 * x2 + y2 * y2
            var radius = abs(if (r12 < r22) y1 * dx - x1 * dy else y2 * dx - x2 * dy) / d

            if (radius <= mwp.radius) {
                var s1 = x1 * dx + y1 * dy
                var s2 = x2 * dx + y2 * dy

                if (s1 < 0.0) {
                    s1 = -s1
                    s2 = -s2
                }
                if (s2 > 0.0) {
                    radius = sqrt(if (s1 < s2) r12 else r22)

                    if (radius > mwp.radius) {
                        continue
                    }
                }
                // new match for that waypoint
                mwp.radius = radius // shortest distance to way
                mwp.hasUpdate = true
                anyUpdate = true
                // calculate crosspoint
                val cp = mwp.crosspoint ?: OsmNode().also { mwp.crosspoint = it }
                if (s2 < 0.0) {
                    val wayfraction = -s2 / (d * d)
                    val xm = x2 - wayfraction * dx
                    val ym = y2 - wayfraction * dy
                    val newLon = (xm / dlon2m + waypointPosition.longitude).toInt()
                    val newLat = (ym / dlat2m + waypointPosition.latitude).toInt()
                    cp.position = Position(newLon, newLat)
                } else if (s1 > s2) {
                    cp.position = Position(endLon, endLat)
                } else {
                    cp.position = Position(startLon, startLat)
                }
            }
        }
    }

    override public fun start(startLon: Int, startLat: Int, targetLon: Int, targetLat: Int, useAsStartWay: Boolean): Boolean {
        if (islandPairs.size != 0) {
            if (islandPairs.hasPair(Position.computeId(startLon, startLat), Position.computeId(targetLon, targetLat))) {
                return false
            }
        }
        this.startLon = startLon
        this.startLat = startLat
        lastLon = startLon
        lastLat = startLat
        this.targetLon = targetLon
        this.targetLat = targetLat
        anyUpdate = false
        this.useAsStartWay = useAsStartWay
        return true
    }

    override public fun transferNode(lon: Int, lat: Int) {
        checkSegment(lastLon, lastLat, lon, lat)
        lastLon = lon
        lastLat = lat
    }

    override public fun end() {
        checkSegment(lastLon, lastLat, targetLon, targetLat)
        if (anyUpdate) {
            for (mwp in waypoints) {
                if (mwp.hasUpdate) {
                    var angle = normalizeAngle(rawBearing(startLon, startLat, targetLon, targetLat))
                    var diff = bearingDifference(mwp.directionToNext, angle)

                    mwp.hasUpdate = false

                    val waypointPos = requireNotNull(mwp.waypoint).position
                    val crosspointPos = requireNotNull(mwp.crosspoint).position

                    var mw = MatchedWaypoint()
                    mw.waypoint = OsmNode().also { it.position = waypointPos }
                    mw.crosspoint = OsmNode().also { it.position = crosspointPos }
                    mw.node1 = OsmNode(startLon, startLat)
                    mw.node2 = OsmNode(targetLon, targetLat)
                    mw.name = mwp.name + "_w_" + mwp.crosspoint.hashCode()
                    mw.radius = mwp.radius
                    mw.directionDiff = diff
                    mw.directionToNext = mwp.directionToNext

                    updateWayList(mwp.wayNearest, mw)

                    // revers
                    angle = normalizeAngle(rawBearing(targetLon, targetLat, startLon, startLat))
                    diff = bearingDifference(mwp.directionToNext, angle)
                    mw = MatchedWaypoint()
                    mw.waypoint = OsmNode().also { it.position = waypointPos }
                    mw.crosspoint = OsmNode().also { it.position = crosspointPos }
                    mw.node1 = OsmNode(targetLon, targetLat)
                    mw.node2 = OsmNode(startLon, startLat)
                    mw.name = mwp.name + "_w2_" + mwp.crosspoint.hashCode()
                    mw.radius = mwp.radius
                    mw.directionDiff = diff
                    mw.directionToNext = mwp.directionToNext

                    updateWayList(mwp.wayNearest, mw)

                    val way = mwp.wayNearest[0]
                    mwp.crosspoint!!.position = requireNotNull(way.crosspoint).position
                    mwp.node1 = OsmNode(requireNotNull(way.node1).position)
                    mwp.node2 = OsmNode(requireNotNull(way.node2).position)
                    mwp.directionDiff = way.directionDiff
                    mwp.radius = way.radius
                }
            }
        }
    }

    override public fun hasMatch(lon: Int, lat: Int): Boolean {
        val positionId = Position.computeId(lon, lat)
        for (mwp in waypoints) {
            val wpPos = mwp.waypoint?.position ?: continue
            if (wpPos.id == positionId &&
                (mwp.radius < this.maxDistance || mwp.crosspoint != null)
            ) {
                return true
            }
        }
        return false
    }

    // check limit of list size (avoid long runs)
    public fun updateWayList(ways: MutableList<MatchedWaypoint>, mw: MatchedWaypoint) {
        ways.add(mw)
        // use only shortest distances by smallest direction difference
        ways.sortWith(comparator)
        if (ways.size > MAX_POINTS) ways.removeAt(MAX_POINTS)
    }


    public companion object {
        private const val MAX_POINTS = 5
    }
}
