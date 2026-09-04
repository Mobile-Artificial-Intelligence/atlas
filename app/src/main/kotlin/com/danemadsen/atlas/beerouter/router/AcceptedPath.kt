package com.danemadsen.atlas.beerouter.router

import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.map.OsmLink
import com.danemadsen.atlas.beerouter.map.OsmLinkHolder
import com.danemadsen.atlas.beerouter.map.OsmNode

internal class AcceptedPath(
    val parent: AcceptedPath? = null,
    sourceNode: OsmNode? = null,
    targetNode: OsmNode? = null,
    link: OsmLink? = null,
    cost: Int = 0,
    originCost: Int = 0,
    airdistance: Int = 0,
    treedepth: Int = 0,
    originPosition: Position? = null,
    selev: Short = 0,
    stdState: StdPathState? = null,
    searchState: OsmPathSearchState? = null,
    message: MessageData? = null,
) : OsmPath() {
    private var ehbd: Int = stdState?.ehbd ?: 0
    private var ehbu: Int = stdState?.ehbu ?: 0
    private var stdTotalTime: Double = stdState?.totalTime ?: 0.0
    private var stdTotalEnergy: Double = stdState?.totalEnergy ?: 0.0
    private var elevationBuffer: Float = stdState?.elevationBuffer ?: 0f
    private var uphillcostdiv: Int = stdState?.uphillcostdiv ?: 0
    private var downhillcostdiv: Int = stdState?.downhillcostdiv ?: 0

    init {
        this.sourceNode = sourceNode
        this.targetNode = targetNode
        this.link = link
        this.cost = cost
        this.originCost = originCost
        this.airdistance = airdistance
        this.treedepth = treedepth
        this.originPosition = originPosition
        this.selev = selev
        this.message = message
        if (searchState != null) importSearchState(searchState)
    }

    val stdState: StdPathState
        get() = exportStdState()

    internal fun exportStdState(): StdPathState = StdPathState(
        ehbd = ehbd,
        ehbu = ehbu,
        totalTime = stdTotalTime,
        totalEnergy = stdTotalEnergy,
        elevationBuffer = elevationBuffer,
        uphillcostdiv = uphillcostdiv,
        downhillcostdiv = downhillcostdiv,
    )

    internal fun copyAcceptedStateTo(state: StdPathState) {
        state.ehbd = ehbd
        state.ehbu = ehbu
        state.totalTime = stdTotalTime
        state.totalEnergy = stdTotalEnergy
        state.elevationBuffer = elevationBuffer
        state.uphillcostdiv = uphillcostdiv
        state.downhillcostdiv = downhillcostdiv
    }

    internal fun copyAcceptedSearchStateTo(candidate: StdPathCandidate) {
        candidate.importSearchState(
            lastClassifier = lastClassifier,
            lastInitialCost = lastInitialCost,
            priorityclassifier = priorityclassifier,
            bitfield = bitfield,
        )
    }

    internal fun isCandidateDefinitelyWorseThan(candidateCost: Int, candidateState: StdPathState): Boolean {
        var correctedCost = cost
        if (downhillcostdiv > 0) {
            val delta = ehbd / downhillcostdiv -
                (if (candidateState.downhillcostdiv > 0) candidateState.ehbd / candidateState.downhillcostdiv else 0)
            if (delta > 0) correctedCost += delta
        }
        if (uphillcostdiv > 0) {
            val delta = ehbu / uphillcostdiv -
                (if (candidateState.uphillcostdiv > 0) candidateState.ehbu / candidateState.uphillcostdiv else 0)
            if (delta > 0) correctedCost += delta
        }
        return candidateCost > correctedCost
    }

    override fun init(orig: OsmPath?) {
        throw UnsupportedOperationException("AcceptedPath does not evaluate transitions")
    }

    override fun resetState() {
        throw UnsupportedOperationException("AcceptedPath is not recycled")
    }

    override fun processWaySection(
        rc: RoutingContext,
        dist: Double,
        delta_h: Double,
        elevation: Double,
        angle: Double,
        cosangle: Double,
        isStartpoint: Boolean,
        nsection: Int,
        lastpriorityclassifier: Int
    ): Double {
        throw UnsupportedOperationException("AcceptedPath does not evaluate transitions")
    }

    override fun processTargetNode(rc: RoutingContext): Double {
        throw UnsupportedOperationException("AcceptedPath does not evaluate transitions")
    }

    override fun elevationCorrection(): Int =
        (if (downhillcostdiv > 0) ehbd / downhillcostdiv else 0) +
            (if (uphillcostdiv > 0) ehbu / uphillcostdiv else 0)

    override fun definitlyWorseThan(p: OsmPath?): Boolean {
        val path = p as AcceptedPath
        return path.isCandidateDefinitelyWorseThan(cost, exportStdState())
    }

    override val totalTime: Double
        get() = stdTotalTime

    override val totalEnergy: Double
        get() = stdTotalEnergy

    override var nextForLink: OsmLinkHolder? = null
}
