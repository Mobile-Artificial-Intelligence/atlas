package com.danemadsen.atlas.beerouter.router

import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.map.MatchedWaypoint
import com.danemadsen.atlas.beerouter.map.NodesCache
import com.danemadsen.atlas.beerouter.map.OsmLink
import com.danemadsen.atlas.beerouter.map.OsmNode
import com.danemadsen.atlas.beerouter.map.OsmNodePairSet
import com.danemadsen.atlas.beerouter.map.RoutingMemoryPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class StdPathCandidateTest {
    @Test
    fun routingContextIdentifiesStdModelProfiles() {
        val stdContext = routingContextFromFiles(profilePath("fastbike.brf"), requireNotNull(testSegmentFile.parent))
        val kinematicContext = routingContextFromFiles(profilePath("car-vario.brf"), requireNotNull(testSegmentFile.parent))

        assertEquals(true, stdContext.isStdModel())
        assertEquals(false, kinematicContext.isStdModel())
    }

    @Test
    fun candidateSnapshotsIntoAcceptedPathWithoutSharingMutableStateObject() {
        val parent = AcceptedPath(targetNode = OsmNode(Position(1, 2)), cost = 10)
        val source = OsmNode(Position(1, 2))
        val target = OsmNode(Position(3, 4))
        val link = OsmLink(source, target)
        val candidate = StdPathCandidate()

        candidate.resetFrom(parent, source, target, link)
        candidate.cost = 25
        candidate.airdistance = 40
        candidate.treedepth = 3
        candidate.selev = 8
        candidate.originPosition = Position(5, 6)
        candidate.stdState.ehbd = 11

        val accepted = candidate.snapshotAccepted()
        val fastPartialAccepted = candidate.snapshotAccepted(includeOriginCost = true)
        candidate.stdState.ehbd = 99

        assertSame(parent, accepted.parent)
        assertSame(source, accepted.sourceNode)
        assertSame(target, accepted.targetNode)
        assertSame(link, accepted.link)
        assertEquals(25, accepted.cost)
        assertEquals(40, accepted.airdistance)
        assertEquals(3, accepted.treedepth)
        assertEquals(8.toShort(), accepted.selev)
        assertEquals(Position(5, 6), accepted.originPosition)
        assertEquals(0, accepted.originCost)
        assertEquals(parent.cost, fastPartialAccepted.originCost)
        assertEquals(11, accepted.stdState.ehbd)
    }

    @Test
    fun candidateDominanceMatchesStdStateComparisonWithoutExportingAcceptedState() {
        val candidate = StdPathCandidate().apply {
            cost = 120
            stdState.ehbd = 20
            stdState.ehbu = 40
            stdState.downhillcostdiv = 10
            stdState.uphillcostdiv = 20
        }
        val accepted = AcceptedPath(
            cost = 100,
            stdState = StdPathState(
                ehbd = 60,
                ehbu = 100,
                downhillcostdiv = 10,
                uphillcostdiv = 20,
            ),
        )

        assertEquals(
            candidate.stdState.isDefinitelyWorseThan(candidate.cost, accepted.cost, accepted.stdState),
            candidate.definitlyWorseThan(accepted),
        )
    }

    @Test
    fun candidateEvaluationMatchesCompatibilityStdPathForSimpleLink() {
        val rc = routingContextFromFiles(profilePathForSegments("trekking.brf", generatedTestSegmentDir), generatedTestSegmentDir)
        val nodesCache = NodesCache(
            rc.mapSource,
            rc.way,
            rc.global.forceSecondaryData,
            RoutingMemoryPolicy.default(),
            null,
            false,
        )
        val waypoint = MatchedWaypoint(
            waypoint = OsmNode(Position.fromDegrees(9.053658, 48.520272)),
            name = "Tübingen A",
        )
        val matched = mutableListOf(waypoint)
        nodesCache.matchWaypointsToNodes(matched, rc.global.waypointCatchingRange, OsmNodePairSet(16))
        val node1 = requireNotNull(waypoint.node1)
        val node2 = requireNotNull(waypoint.node2)
        val (source, link) = expandedLinkBetween(rc, node1, node2)
            ?: expandedLinkBetween(rc, node2, node1)
            ?: error("matched waypoint endpoints are not connected by a decoded link")
        val target = requireNotNull(link.getTarget(source))
        val origin = StdPath().apply {
            init(OsmLink(null, source))
            cost = 17
        }
        val acceptedOrigin = AcceptedPath(
            targetNode = source,
            link = origin.link,
            cost = origin.cost,
            selev = origin.selev,
            stdState = origin.exportStdState(),
        )

        val compatibility = rc.createPath(origin, link, null, false) as StdPath
        val candidate = StdPathCandidate()

        val accepted = rc.evaluateStdCandidate(candidate, acceptedOrigin, source, target, link, null)

        assertEquals(compatibility.cost, accepted?.cost)
        assertEquals(compatibility.selev, accepted?.selev)
        assertEquals(compatibility.originPosition, accepted?.originPosition)
        assertEquals(compatibility.exportStdState(), accepted?.stdState)
        rc.recyclePath(compatibility)
    }

    private fun expandedLinkBetween(rc: RoutingContext, sourceTemplate: OsmNode, targetTemplate: OsmNode): Pair<OsmNode, OsmLink>? {
        val source = NodesCache(
            rc.mapSource,
            rc.way,
            rc.global.forceSecondaryData,
            RoutingMemoryPolicy.default(),
            null,
            false,
        ).getStartNode(sourceTemplate.idFromPos) ?: return null
        val targetId = targetTemplate.idFromPos
        val link = generateSequence(source.firstlink) { it.getNext(source) }
            .firstOrNull { it.descriptionBitmap != null && it.getTarget(source)?.idFromPos == targetId }
            ?: return null
        return source to link
    }

    @Test
    fun rejectedCompatibilityCandidateRestoresEvaluationState() {
        val rc = routingContextFromFiles(profilePath("fastbike.brf"), requireNotNull(testSegmentFile.parent))
        rc.nogoCost = 5.0
        rc.shortestmatch = true
        rc.wayfraction = 0.5
        rc.shortestPosition = Position(7, 8)

        val stateBefore = rc.snapshotEvaluationState()
        rc.evaluateStdCompatibilityCandidate {
            rc.nogoCost = -1.0
            rc.shortestmatch = false
            null
        }

        val stateAfter = rc.snapshotEvaluationState()
        assertEquals(stateBefore, stateAfter)
    }
}
