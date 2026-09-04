package com.danemadsen.atlas.beerouter.router

import com.danemadsen.atlas.beerouter.geo.CheapAngleMeter.turnAngle
import com.danemadsen.atlas.beerouter.geo.CheapRuler.DEG_TO_RAD
import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.geo.coordinateScaleAt
import com.danemadsen.atlas.beerouter.map.OsmLink
import com.danemadsen.atlas.beerouter.map.OsmNode
import com.danemadsen.atlas.beerouter.map.TurnRestriction.Companion.isTurnForbidden
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal class StdPathCandidate {
    private var parent: AcceptedPath? = null
    private var sourceNode: OsmNode? = null
    private var targetNode: OsmNode? = null
    private var link: OsmLink? = null
    private var lastClassifier: Float = 0f
    private var lastInitialCost: Float = 0f
    private var priorityclassifier: Int = 0
    private var bitfield: Int = OsmPath.PATH_START_BIT
    var cost: Int = 0
    var airdistance: Int = 0
    var treedepth: Int = 0
    var originPosition: Position? = null
    var selev: Short = 0
    val stdState: StdPathState = StdPathState()

    fun resetFrom(parent: AcceptedPath, sourceNode: OsmNode, targetNode: OsmNode, link: OsmLink) {
        this.parent = parent
        this.sourceNode = sourceNode
        this.targetNode = targetNode
        this.link = link
        cost = parent.cost
        airdistance = 0
        treedepth = parent.treedepth + 1
        originPosition = parent.originPosition
        selev = parent.selev
        parent.copyAcceptedSearchStateTo(this)
        parent.copyAcceptedStateTo(stdState)
    }

    internal fun importSearchState(
        lastClassifier: Float,
        lastInitialCost: Float,
        priorityclassifier: Int,
        bitfield: Int,
    ) {
        this.lastClassifier = lastClassifier
        this.lastInitialCost = lastInitialCost
        this.priorityclassifier = priorityclassifier
        this.bitfield = bitfield
    }

    fun evaluate(rc: RoutingContext, refTrack: OsmTrack?): Boolean {
        val source = requireNotNull(sourceNode)
        val target = requireNotNull(targetNode)
        val candidateLink = requireNotNull(link)
        val description = candidateLink.descriptionBitmap
        if (description == null) {
            return true
        }

        rc.nogoCost = 0.0

        var previousLon = originPosition?.longitude ?: 0
        var previousLat = originPosition?.latitude ?: 0
        var previousId = originPosition?.id ?: 0L
        var hasPreviousPosition = originPosition != null

        var currentLon = source.longitude
        var currentLat = source.latitude
        var currentPositionForState: Position? = source.position
        var ele1 = selev
        var linkdisttotal = 0

        val isReverse = candidateLink.isReverse(source)
        rc.way.evaluate(rc.inverseDirection xor isReverse, description)

        if (rc.ai != null && rc.ai!!.polygon!!.isWithin(currentLon.toLong(), currentLat.toLong())) {
            rc.ai!!.checkAreaInfo(rc.way, ele1.toDouble() / 4.0, description)
        }

        val costfactor = rc.way.costfactor
        val isTrafficBackbone = cost == 0 && rc.way.isTrafficBackbone > 0f
        val lastpriorityclassifier = priorityclassifier
        priorityclassifier = rc.way.priorityClassifier.toInt()

        val newClassifier = rc.way.initialClassifier
        val newInitialCost = rc.way.initialcost
        val classifierDiff = newClassifier - lastClassifier
        if (newClassifier.toDouble() != 0.0 && lastClassifier.toDouble() != 0.0 &&
            (classifierDiff > 0.0005 || classifierDiff < -0.0005)
        ) {
            val initialcost = if (rc.inverseDirection) lastInitialCost else newInitialCost
            if (initialcost >= 1000000.0) return reject()
            cost += initialcost.toInt()
        }
        lastClassifier = newClassifier
        lastInitialCost = newInitialCost

        val classifiermask = rc.way.classifierMask.toInt()
        val newDestination = (classifiermask and 64) != 0
        val oldDestination = getBit(OsmPath.IS_ON_DESTINATION_BIT)
        if (getBit(OsmPath.PATH_START_BIT)) {
            setBit(OsmPath.PATH_START_BIT, false)
            setBit(OsmPath.CAN_LEAVE_DESTINATION_BIT, newDestination)
            setBit(OsmPath.HAD_DESTINATION_START_BIT, newDestination)
        } else if (oldDestination && !newDestination) {
            if (getBit(OsmPath.CAN_LEAVE_DESTINATION_BIT)) {
                setBit(OsmPath.CAN_LEAVE_DESTINATION_BIT, false)
            } else {
                return reject()
            }
        }
        setBit(OsmPath.IS_ON_DESTINATION_BIT, newDestination)

        var transferNode = candidateLink.geometry?.let {
            rc.geometryDecoder.decodeGeometry(it, source, target, isReverse)
        }

        var nsection = 0
        while (true) {
            val originEle2: Short
            val nextLon: Int
            val nextLat: Int
            val nextId: Long
            if (transferNode == null) {
                originEle2 = target.altitude
                nextLon = target.longitude
                nextLat = target.latitude
                nextId = target.idFromPos
            } else {
                nextLon = transferNode.longitude
                nextLat = transferNode.latitude
                originEle2 = transferNode.altitude
                nextId = transferNode.idFromPos
            }
            var ele2 = originEle2
            var isStartpoint = !hasPreviousPosition

            if (nsection == 0 && rc.global.considerTurnRestrictions && !isStartpoint) {
                val forbidden = if (rc.inverseDirection) {
                    isTurnForbidden(
                        source.firstRestriction,
                        nextId,
                        previousId,
                        rc.global.bikeMode || rc.global.footMode,
                        rc.global.carMode,
                    )
                } else {
                    isTurnForbidden(
                        source.firstRestriction,
                        previousId,
                        nextId,
                        rc.global.bikeMode || rc.global.footMode,
                        rc.global.carMode,
                    )
                }
                if (forbidden) return reject()
            }

            var dist = rc.distanceBetween(currentLon, currentLat, nextLon, nextLat)
            var stopAtEndpoint = false
            if (rc.shortestmatch) {
                if (rc.isEndpoint) {
                    stopAtEndpoint = true
                    ele2 = interpolateEle(ele1, ele2, rc.wayfraction)
                } else {
                    cost = 0
                    resetState()
                    hasPreviousPosition = false
                    isStartpoint = true
                    if (rc.checkPendingEndpoint()) {
                        dist = rc.distanceBetween(
                            requireNotNull(rc.shortestPosition).longitude,
                            requireNotNull(rc.shortestPosition).latitude,
                            nextLon,
                            nextLat,
                        )
                        if (rc.shortestmatch) {
                            stopAtEndpoint = true
                            ele2 = interpolateEle(ele1, ele2, rc.wayfraction)
                        }
                    }
                }
            }

            linkdisttotal += dist

            if (isStartpoint) {
                if (rc.startDirectionValid) {
                    val dir = rc.startDirection!! * DEG_TO_RAD
                    val scale = coordinateScaleAt(currentLat)
                    previousLon = currentLon - (1000.0 * sin(dir) / scale.longitudeToMeters).toInt()
                    previousLat = currentLat - (1000.0 * cos(dir) / scale.latitudeToMeters).toInt()
                } else {
                    previousLon = currentLon - (nextLon - currentLon)
                    previousLat = currentLat - (nextLat - currentLat)
                }
                previousId = Position.computeId(previousLon, previousLat)
                hasPreviousPosition = true
            }

            val angleMeasurement = turnAngle(previousLon, previousLat, currentLon, currentLat, nextLon, nextLat)

            var deltaH = 0.0
            if (ele2 == Short.MIN_VALUE) ele2 = ele1
            if (ele1 != Short.MIN_VALUE) {
                deltaH = (ele2.toInt() - ele1.toInt()) / 4.0
                if (rc.inverseDirection) deltaH = -deltaH
            }
            val elevation = if (ele2 == Short.MIN_VALUE) 100.0 else ele2.toDouble() / 4.0

            var sectionCost = processWaySection(
                rc,
                dist.toDouble(),
                deltaH,
                elevation,
                angleMeasurement.cosAngle,
                lastpriorityclassifier,
            )
            if ((sectionCost < 0.0 || costfactor > 9998.0) || sectionCost + cost >= 2000000000.0) {
                return reject()
            }
            if (isTrafficBackbone) sectionCost = 0.0
            cost += sectionCost.toInt()

            if (stopAtEndpoint) {
                originPosition = currentPositionForState ?: Position(currentLon, currentLat, ele1)
                cost = if (rc.nogoCost < 0) -1 else (cost + rc.nogoCost).toInt()
                return cost >= 0
            }

            if (transferNode == null) {
                if (refTrack != null && refTrack.containsNode(target) && refTrack.containsNode(source)) {
                    cost += linkdisttotal
                }
                selev = ele2
                originPosition = currentPositionForState ?: Position(currentLon, currentLat, ele1)
                break
            }

            transferNode = transferNode.next
            previousLon = currentLon
            previousLat = currentLat
            previousId = Position.computeId(previousLon, previousLat)
            hasPreviousPosition = true
            currentLon = nextLon
            currentLat = nextLat
            currentPositionForState = null
            ele1 = ele2
            nsection++
        }

        if (rc.nogoCost < 0) return reject()
        cost = (cost + rc.nogoCost).toInt()

        val targetCost = processTargetNode(rc)
        if (targetCost < 0.0 || targetCost + cost >= 2000000000.0) return reject()
        cost += targetCost.toInt()
        return true
    }

    fun definitlyWorseThan(path: AcceptedPath): Boolean = path.isCandidateDefinitelyWorseThan(cost, stdState)

    fun snapshotAccepted(includeOriginCost: Boolean = false): AcceptedPath = AcceptedPath(
        parent = requireNotNull(parent),
        sourceNode = sourceNode,
        targetNode = targetNode,
        link = link,
        cost = cost,
        originCost = if (includeOriginCost) requireNotNull(parent).cost else 0,
        airdistance = airdistance,
        treedepth = treedepth,
        originPosition = originPosition,
        selev = selev,
        stdState = stdState,
        searchState = OsmPathSearchState(
            lastClassifier = lastClassifier,
            lastInitialCost = lastInitialCost,
            priorityclassifier = priorityclassifier,
            bitfield = bitfield,
        ),
    )

    private fun resetState() {
        stdState.ehbd = 0
        stdState.ehbu = 0
        stdState.totalTime = 0.0
        stdState.totalEnergy = 0.0
        stdState.uphillcostdiv = 0
        stdState.downhillcostdiv = 0
        stdState.elevationBuffer = 0f
    }

    private fun reject(): Boolean {
        cost = -1
        return false
    }

    private fun getBit(bit: Int): Boolean = (bitfield and bit) != 0

    private fun setBit(bit: Int, value: Boolean) {
        if (getBit(bit) != value) {
            bitfield = bitfield xor bit
        }
    }

    private fun processWaySection(
        rc: RoutingContext,
        dist: Double,
        deltaH: Double,
        elevation: Double,
        cosangle: Double,
        lastpriorityclassifier: Int,
    ): Double {
        val turncostbase = rc.way.turncost
        val uphillcutoff = rc.way.uphillcutoff * 10000
        val downhillcutoff = rc.way.downhillcutoff * 10000
        val uphillmaxslope = rc.way.uphillmaxslope * 10000
        val downhillmaxslope = rc.way.downhillmaxslope * 10000
        var cfup = rc.way.uphillCostfactor
        var cfdown = rc.way.downhillCostfactor
        val cf = rc.way.costfactor
        cfup = if (cfup == 0f) cf else cfup
        cfdown = if (cfdown == 0f) cf else cfdown

        stdState.downhillcostdiv = rc.way.downhillcost.toInt()
        if (stdState.downhillcostdiv > 0) {
            stdState.downhillcostdiv = 1000000 / stdState.downhillcostdiv
        }

        var downhillmaxslopecostdiv = rc.way.downhillmaxslopecost.toInt()
        downhillmaxslopecostdiv = if (downhillmaxslopecostdiv > 0) {
            1000000 / downhillmaxslopecostdiv
        } else {
            stdState.downhillcostdiv
        }

        stdState.uphillcostdiv = rc.way.uphillcost.toInt()
        if (stdState.uphillcostdiv > 0) {
            stdState.uphillcostdiv = 1000000 / stdState.uphillcostdiv
        }

        var uphillmaxslopecostdiv = rc.way.uphillmaxslopecost.toInt()
        uphillmaxslopecostdiv = if (uphillmaxslopecostdiv > 0) {
            1000000 / uphillmaxslopecostdiv
        } else {
            stdState.uphillcostdiv
        }

        val distInt = dist.toInt()
        val turncost = ((1.0 - cosangle) * turncostbase + 0.2).toInt()
        var sectionCost = turncost.toDouble()

        val deltaHMicros = (1000000.0 * deltaH).toInt()
        stdState.ehbd = (stdState.ehbd + (-deltaHMicros - distInt * downhillcutoff)).toInt()
        stdState.ehbu = (stdState.ehbu + (deltaHMicros - distInt * uphillcutoff)).toInt()

        var downweight = 0f
        if (stdState.ehbd > rc.global.elevationpenaltybuffer) {
            downweight = 1f
            var excess = stdState.ehbd - rc.global.elevationpenaltybuffer
            var reduce = distInt * rc.global.elevationbufferreduce
            if (reduce > excess) {
                downweight = excess.toFloat() / reduce
                reduce = excess
            }
            excess = stdState.ehbd - rc.global.elevationmaxbuffer
            if (reduce < excess) reduce = excess
            stdState.ehbd -= reduce
            var elevationCost = 0f
            if (stdState.downhillcostdiv > 0) {
                elevationCost += min(reduce.toFloat(), distInt * downhillmaxslope) / stdState.downhillcostdiv
            }
            if (downhillmaxslopecostdiv > 0) {
                elevationCost += max(0f, reduce - distInt * downhillmaxslope) / downhillmaxslopecostdiv
            }
            if (elevationCost > 0) sectionCost += elevationCost.toDouble()
        } else if (stdState.ehbd < 0) {
            stdState.ehbd = 0
        }

        var upweight = 0f
        if (stdState.ehbu > rc.global.elevationpenaltybuffer) {
            upweight = 1f
            var excess = stdState.ehbu - rc.global.elevationpenaltybuffer
            var reduce = distInt * rc.global.elevationbufferreduce
            if (reduce > excess) {
                upweight = excess.toFloat() / reduce
                reduce = excess
            }
            excess = stdState.ehbu - rc.global.elevationmaxbuffer
            if (reduce < excess) reduce = excess
            stdState.ehbu -= reduce
            var elevationCost = 0f
            if (stdState.uphillcostdiv > 0) {
                elevationCost += min(reduce.toFloat(), distInt * uphillmaxslope) / stdState.uphillcostdiv
            }
            if (uphillmaxslopecostdiv > 0) {
                elevationCost += max(0f, reduce - distInt * uphillmaxslope) / uphillmaxslopecostdiv
            }
            if (elevationCost > 0) sectionCost += elevationCost.toDouble()
        } else if (stdState.ehbu < 0) {
            stdState.ehbu = 0
        }

        val costfactor = cfup * upweight + cf * (1f - upweight - downweight) + cfdown * downweight
        sectionCost += (distInt * costfactor + 0.5f).toDouble()
        return sectionCost
    }

    private fun processTargetNode(rc: RoutingContext): Double {
        val nodeDescription = requireNotNull(targetNode).nodeDescription ?: return 0.0
        val nodeAccessGranted = rc.way.nodeAccessGranted.toDouble() != 0.0
        rc.node.evaluate(nodeAccessGranted, nodeDescription)
        val initialcost = rc.node.initialcost
        return if (initialcost >= 1000000.0) -1.0 else initialcost.toDouble()
    }

    private fun interpolateEle(e1: Short, e2: Short, fraction: Double): Short {
        if (e1 == Short.MIN_VALUE || e2 == Short.MIN_VALUE) {
            return Short.MIN_VALUE
        }
        return (e1 * (1.0 - fraction) + e2 * fraction).toInt().toShort()
    }
}
