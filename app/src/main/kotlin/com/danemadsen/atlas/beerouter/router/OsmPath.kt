/**
 * Container for link between two Osm nodes
 *
 * @author ab
 */
package com.danemadsen.atlas.beerouter.router

import com.danemadsen.atlas.beerouter.geo.CheapAngleMeter.turnAngle
import com.danemadsen.atlas.beerouter.geo.CheapRuler.DEG_TO_RAD
import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.geo.coordinateScaleAt
import com.danemadsen.atlas.beerouter.geo.withAltitude
import com.danemadsen.atlas.beerouter.map.OsmLink
import com.danemadsen.atlas.beerouter.map.OsmLinkHolder
import com.danemadsen.atlas.beerouter.map.OsmNode
import com.danemadsen.atlas.beerouter.map.TurnRestriction.Companion.isTurnForbidden
import kotlin.math.cos
import kotlin.math.sin

public abstract class OsmPath : OsmLinkHolder {
    /**
     * The cost of that path (a modified distance)
     */
    public var cost: Int = 0

    // the elevation assumed for that path can have a value
    // if the corresponding node has not
    public var selev: Short = 0

    public var airdistance: Int = 0 // distance to endpos

    public var sourceNode: OsmNode? = null
    public var targetNode: OsmNode? = null

    public var link: OsmLink? = null
        protected set
    public var originElement: OsmPathElement? = null
    public var myElement: OsmPathElement? = null
    private var deferredOriginPath: OsmPath? = null
    internal var originCost: Int = 0

    public var treedepth: Int = 0

    // The waypoint just before this path position (for angle calculation).
    public var originPosition: Position? = null

    // the classifier of the segment just before this paths position
    protected var lastClassifier: Float = 0f
    protected var lastInitialCost: Float = 0f

    protected var priorityclassifier: Int = 0

    protected var bitfield: Int = PathFlag.PATH_START.bit

    private fun getBit(flag: PathFlag): Boolean = (bitfield and flag.bit) != 0

    private fun setBit(flag: PathFlag, bit: Boolean) {
        if (getBit(flag) != bit) {
            bitfield = bitfield xor flag.bit
        }
    }

    public fun didEnterDestinationArea(): Boolean {
        return !getBit(PathFlag.HAD_DESTINATION_START) && getBit(PathFlag.IS_ON_DESTINATION)
    }

    public var message: MessageData? = null

    public fun init(link: OsmLink) {
        this.link = link
        targetNode = link.getTarget(null)
        selev = targetNode!!.altitude

        originPosition = null
    }

    public fun init(
        origin: OsmPath,
        link: OsmLink,
        refTrack: OsmTrack?,
        detailMode: Boolean,
        rc: RoutingContext
    ) {
        if (detailMode) {
            origin.materializeMyElement()
            this.originElement = origin.myElement
        } else {
            deferOriginElement(origin)
        }
        originCost = origin.cost
        this.link = link
        this.sourceNode = origin.targetNode
        this.targetNode = link.getTarget(sourceNode)
        this.cost = origin.cost
        this.lastClassifier = origin.lastClassifier
        this.lastInitialCost = origin.lastInitialCost
        this.bitfield = origin.bitfield
        this.priorityclassifier = origin.priorityclassifier
        init(origin)
        addAddionalPenalty(refTrack, detailMode, origin, link, rc)
    }

    protected abstract fun init(orig: OsmPath?)

    protected abstract fun resetState()

    internal fun resetForReuse() {
        cost = 0
        selev = 0
        airdistance = 0
        sourceNode = null
        targetNode = null
        link = null
        originElement = null
        myElement = null
        originCost = 0
        treedepth = 0
        originPosition = null
        lastClassifier = 0f
        lastInitialCost = 0f
        priorityclassifier = 0
        bitfield = PathFlag.PATH_START.bit
        message = null
        nextForLink = null
        deferredOriginPath = null
        resetState()
    }

    internal fun exportSearchState(): OsmPathSearchState = OsmPathSearchState(
        lastClassifier = lastClassifier,
        lastInitialCost = lastInitialCost,
        priorityclassifier = priorityclassifier,
        bitfield = bitfield,
    )

    internal fun importSearchState(state: OsmPathSearchState) {
        lastClassifier = state.lastClassifier
        lastInitialCost = state.lastInitialCost
        priorityclassifier = state.priorityclassifier
        bitfield = state.bitfield
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

    internal fun deferOriginElement(origin: OsmPath) {
        deferredOriginPath = origin
        originElement = null
    }

    internal fun materializeOriginElement() {
        val origin = deferredOriginPath ?: return
        origin.materializeMyElement()
        originElement = origin.myElement
        deferredOriginPath = null
    }

    private fun materializeMyElement() {
        materializeOriginElement()
        if (myElement == null) {
            val n = requireNotNull(targetNode)
            myElement = OsmPathElement(
                n.positionWithAltitude(),
                originElement
            ).also {
                it.cost = cost
                it.message = message
            }
        }
    }

    protected fun addAddionalPenalty(
        refTrack: OsmTrack?,
        detailMode: Boolean,
        origin: OsmPath,
        link: OsmLink,
        rc: RoutingContext
    ) {
        val description = link.descriptionBitmap
        if (description == null) { // could be a beeline path
            message = MessageData().also {
                it.turnangle = 0f
                it.time = 1f
                it.energy = 0f
                it.priorityclassifier = 0
                it.classifiermask = 0
                val target = requireNotNull(targetNode)
                it.position = Position(target.position.longitude, target.position.latitude, target.altitude)
                it.linkdist = sourceNode!!.distanceTo(targetNode!!)
                it.wayTags = mapOf("direct_segment" to "$seg")
                seg++
            }
            // LOCAL PATCH (Atlas): a beeline section runs straight from the
            // source to the target node, so the position before its end —
            // what originPosition means everywhere else in the section loop
            // below — is the source node itself. Stock left it null, and
            // KinematicPrePath.initPrePath (which the kinematic models run
            // for every candidate link) requires it non-null, so any
            // desc-less mid-route link crashed the engine with
            // "Required value was null.". Such links occur at 5°-bucket
            // borders when the reverse-record side re-weaves a link whose
            // forward-side instance was consumed and desc-cleared.
            originPosition = sourceNode!!.position
            return
        }

        val recordTransferNodes = detailMode

        rc.nogoCost = 0.0

        // extract the 3 positions of the first section
        val initialPreviousPosition = origin.originPosition
        var previousLon = initialPreviousPosition?.longitude ?: 0
        var previousLat = initialPreviousPosition?.latitude ?: 0
        var previousId = initialPreviousPosition?.id ?: 0L
        var hasPreviousPosition = initialPreviousPosition != null

        var currentLon = sourceNode!!.longitude
        var currentLat = sourceNode!!.latitude
        var currentPositionForState: Position? = sourceNode!!.position
        var ele1 = origin.selev

        var linkdisttotal = 0

        message = if (detailMode) MessageData() else null

        val isReverse = link.isReverse(sourceNode)

        // evaluate the way tags
        rc.way.evaluate(rc.inverseDirection xor isReverse, description)

        // and check if is useful
        if (rc.ai != null && rc.ai!!.polygon!!.isWithin(
                currentLon.toLong(),
                currentLat.toLong()
            )
        ) {
            rc.ai!!.checkAreaInfo(rc.way, ele1.toDouble() / 4.0, description)
        }

        // calculate the costfactor inputs
        val costfactor = rc.way.costfactor
        val isTrafficBackbone = cost == 0 && rc.way.isTrafficBackbone > 0f
        val lastpriorityclassifier = priorityclassifier
        priorityclassifier = rc.way.priorityClassifier.toInt()

        // *** add initial cost if the classifier changed
        val newClassifier = rc.way.initialClassifier
        val newInitialCost = rc.way.initialcost
        val classifierDiff = newClassifier - lastClassifier
        if (newClassifier.toDouble() != 0.0 && lastClassifier.toDouble() != 0.0 && (classifierDiff > 0.0005 || classifierDiff < -0.0005)) {
            val initialcost = if (rc.inverseDirection) lastInitialCost else newInitialCost
            if (initialcost >= 1000000.0) {
                cost = -1
                return
            }

            val iicost = initialcost.toInt()
            if (message != null) {
                message!!.linkinitcost += iicost
            }
            cost += iicost
        }
        lastClassifier = newClassifier
        lastInitialCost = newInitialCost

        // *** destination logic: no destination access in between
        val classifiermask = rc.way.classifierMask.toInt()
        val newDestination = (classifiermask and 64) != 0
        val oldDestination = getBit(PathFlag.IS_ON_DESTINATION)
        if (getBit(PathFlag.PATH_START)) {
            setBit(PathFlag.PATH_START, false)
            setBit(PathFlag.CAN_LEAVE_DESTINATION, newDestination)
            setBit(PathFlag.HAD_DESTINATION_START, newDestination)
        } else {
            if (oldDestination && !newDestination) {
                if (getBit(PathFlag.CAN_LEAVE_DESTINATION)) {
                    setBit(PathFlag.CAN_LEAVE_DESTINATION, false)
                } else {
                    cost = -1
                    return
                }
            }
        }
        setBit(PathFlag.IS_ON_DESTINATION, newDestination)


        var transferNode = if (link.geometry == null)
            null
        else
            rc.geometryDecoder.decodeGeometry(link.geometry!!, sourceNode, targetNode!!, isReverse)

        var nsection = 0
        while (true) {
            var ele2: Short
            val originEle2: Short
            val nextLon: Int
            val nextLat: Int
            val nextId: Long

            if (transferNode == null) {
                val target = requireNotNull(targetNode)
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
            ele2 = originEle2

            var isStartpoint = !hasPreviousPosition

            // check turn restrictions (n detail mode (=final pass) no TR to not mess up voice hints)
            if (nsection == 0 && rc.global.considerTurnRestrictions && !detailMode && !isStartpoint) {
                if (if (rc.inverseDirection)
                        isTurnForbidden(
                            sourceNode!!.firstRestriction,
                            nextId,
                            previousId,
                            rc.global.bikeMode || rc.global.footMode,
                            rc.global.carMode
                        )
                    else
                        isTurnForbidden(
                            sourceNode!!.firstRestriction,
                            previousId,
                            nextId,
                            rc.global.bikeMode || rc.global.footMode,
                            rc.global.carMode
                        )
                ) {
                    cost = -1
                    return
                }
            }

            // if recording, new MessageData for each section (needed for turn-instructions)
            if (message?.wayTags != null) {
                originElement!!.message = message
                message = MessageData()
            }

            var dist = rc.distanceBetween(currentLon, currentLat, nextLon, nextLat)

            var stopAtEndpoint = false
            if (rc.shortestmatch) {
                if (rc.isEndpoint) {
                    stopAtEndpoint = true
                    ele2 = interpolateEle(ele1, ele2, rc.wayfraction)
                } else {
                    // we just start here, reset everything
                    cost = 0
                    resetState()
                    hasPreviousPosition = false
                    isStartpoint = true

                    if (recordTransferNodes) {
                        val shortestPosition = requireNotNull(rc.shortestPosition)
                        if (rc.wayfraction > 0.0) {
                            ele1 = interpolateEle(ele1, ele2, 1.0 - rc.wayfraction)
                            originElement = OsmPathElement(
                                Position(
                                    shortestPosition.longitude,
                                    shortestPosition.latitude,
                                    ele1
                                ),
                                null
                            )
                        } else {
                            originElement = null // prevent duplicate point
                        }
                    }

                    if (rc.checkPendingEndpoint()) {
                        dist = rc.distanceBetween(
                            requireNotNull(rc.shortestPosition).longitude,
                            requireNotNull(rc.shortestPosition).latitude,
                            nextLon,
                            nextLat
                        )
                        if (rc.shortestmatch) {
                            stopAtEndpoint = true
                            ele2 = interpolateEle(ele1, ele2, rc.wayfraction)
                        }
                    }
                }
            }

            message?.let { it.linkdist += dist }
            linkdisttotal += dist

            // apply a start-direction if appropriate (by faking the origin position)
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
            val angleMeasurement = turnAngle(
                previousLon,
                previousLat,
                currentLon,
                currentLat,
                nextLon,
                nextLat,
            )
            val angle = angleMeasurement.angle
            val cosangle = angleMeasurement.cosAngle

            // *** elevation stuff
            var delta_h = 0.0
            if (ele2 == Short.MIN_VALUE) ele2 = ele1
            if (ele1 != Short.MIN_VALUE) {
                delta_h = (ele2.toInt() - ele1.toInt()) / 4.0
                if (rc.inverseDirection) {
                    delta_h = -delta_h
                }
            }


            val elevation = if (ele2 == Short.MIN_VALUE) 100.0 else ele2.toDouble() / 4.0

            var sectionCost = processWaySection(
                rc,
                dist.toDouble(),
                delta_h,
                elevation,
                angle,
                cosangle,
                isStartpoint,
                nsection,
                lastpriorityclassifier
            )
            if ((sectionCost < 0.0 || costfactor > 9998.0 && !detailMode) || sectionCost + cost >= 2000000000.0) {
                cost = -1
                return
            }

            if (isTrafficBackbone) {
                sectionCost = 0.0
            }

            cost += sectionCost.toInt()

            // compute kinematic
            computeKinematic(rc, dist.toDouble(), delta_h, detailMode)

            message?.let {
                it.turnangle = angle.toFloat()
                it.time = this.totalTime.toFloat()
                it.energy = this.totalEnergy.toFloat()
                it.priorityclassifier = priorityclassifier
                it.classifiermask = classifiermask
                it.position = Position(
                    nextLon,
                    nextLat,
                    originEle2
                )
                it.wayTags = rc.way.getMap(isReverse, description)
            }

            if (stopAtEndpoint) {
                if (recordTransferNodes) {
                    val shortestPosition = requireNotNull(rc.shortestPosition)
                    originElement = OsmPathElement(
                        Position(
                            shortestPosition.longitude,
                            shortestPosition.latitude,
                            ele2
                        ),
                        originElement
                    )
                    originElement!!.cost = cost
                    originElement!!.message = message
                }
                originPosition = currentPositionForState ?: Position(currentLon, currentLat, ele1)
                cost = if (rc.nogoCost < 0) {
                    -1
                } else {
                    (cost + rc.nogoCost).toInt()
                }
                return
            }

            if (transferNode == null) {
                // *** penalty for being part of the reference track
                if (refTrack != null && refTrack.containsNode(targetNode!!) && refTrack.containsNode(
                        sourceNode!!
                    )
                ) {
                    val reftrackcost = linkdisttotal
                    cost += reftrackcost
                }
                selev = ele2
                originPosition = currentPositionForState ?: Position(currentLon, currentLat, ele1)
                break
            }
            transferNode = transferNode.next

            if (recordTransferNodes) {
                originElement =
                    OsmPathElement(
                        Position(
                            nextLon,
                            nextLat,
                            originEle2
                        ), originElement
                    )
                originElement!!.cost = cost
            }
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

        // check for nogo-matches (after the *actual* start of segment)
        if (rc.nogoCost < 0) {
            cost = -1
            return
        } else {
            cost = (cost + rc.nogoCost).toInt()
        }

        // add target-node costs
        val targetCost = processTargetNode(rc)
        if (targetCost < 0.0 || targetCost + cost >= 2000000000.0) {
            cost = -1
            return
        }
        cost += targetCost.toInt()
    }


    public fun interpolateEle(e1: Short, e2: Short, fraction: Double): Short {
        if (e1 == Short.MIN_VALUE || e2 == Short.MIN_VALUE) {
            return Short.MIN_VALUE
        }
        return (e1 * (1.0 - fraction) + e2 * fraction).toInt().toShort()
    }

    protected abstract fun processWaySection(
        rc: RoutingContext,
        dist: Double,
        delta_h: Double,
        elevation: Double,
        angle: Double,
        cosangle: Double,
        isStartpoint: Boolean,
        nsection: Int,
        lastpriorityclassifier: Int
    ): Double

    protected abstract fun processTargetNode(rc: RoutingContext): Double

    protected open fun computeKinematic(
        rc: RoutingContext,
        dist: Double,
        delta_h: Double,
        detailMode: Boolean
    ) {
    }

    public abstract fun elevationCorrection(): Int

    public abstract fun definitlyWorseThan(p: OsmPath?): Boolean

    public open val totalTime: Double
        get() = 0.0

    public open val totalEnergy: Double
        get() = 0.0

    public companion object {
        internal const val PATH_START_BIT: Int = 1
        internal const val CAN_LEAVE_DESTINATION_BIT: Int = 2
        internal const val IS_ON_DESTINATION_BIT: Int = 4
        internal const val HAD_DESTINATION_START_BIT: Int = 8

        public var seg: Int = 1
    }

    private enum class PathFlag(public val bit: Int) {
        PATH_START(PATH_START_BIT),
        CAN_LEAVE_DESTINATION(CAN_LEAVE_DESTINATION_BIT),
        IS_ON_DESTINATION(IS_ON_DESTINATION_BIT),
        HAD_DESTINATION_START(HAD_DESTINATION_START_BIT),
    }
}
