package com.danemadsen.atlas.beerouter.router

import com.danemadsen.atlas.beerouter.geo.Position
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RoutingEvaluationStateTest {
    @BeforeTest
    fun before() {
        ensureTestSegmentFile()
    }

    @Test
    fun restoreReturnsCandidateMutatedRoutingContextFields() {
        val rc = routingContextFromFiles(profilePath("fastbike.brf"), requireNotNull(testSegmentFile.parent))
        val originalNogoPoints = mutableListOf(OsmNodeNamed(Position(1, 2)))
        val pending = OsmNodeNamed(Position(3, 4))
        val shortest = Position(5, 6)
        rc.nogopoints = originalNogoPoints
        rc.nogoCost = 12.5
        rc.isEndpoint = true
        rc.shortestmatch = true
        rc.wayfraction = 0.75
        rc.shortestPosition = shortest
        rc.setWaypoint(OsmNodeNamed(Position(7, 8)), pending, true)
        val waypointNogoPoints = rc.nogopoints

        val snapshot = rc.snapshotEvaluationState()

        rc.nogopoints = mutableListOf(OsmNodeNamed(Position(9, 10)))
        rc.nogoCost = -1.0
        rc.isEndpoint = false
        rc.shortestmatch = false
        rc.wayfraction = 0.25
        rc.shortestPosition = Position(11, 12)
        assertTrue(rc.checkPendingEndpoint())

        rc.restoreEvaluationState(snapshot)

        assertSame(waypointNogoPoints, rc.nogopoints)
        assertEquals(12.5, rc.nogoCost)
        assertTrue(rc.isEndpoint)
        assertTrue(rc.shortestmatch)
        assertEquals(0.75, rc.wayfraction)
        assertEquals(shortest, rc.shortestPosition)
        assertTrue(rc.checkPendingEndpoint())
        rc.unsetWaypoint()
        assertFalse(rc.isEndpoint)
        assertEquals(shortest, rc.shortestPosition)
    }
}
