package com.danemadsen.atlas.beerouter.router

import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.map.OsmLink
import com.danemadsen.atlas.beerouter.map.OsmNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class AcceptedPathTest {
    @Test
    fun acceptedPathStoresPersistentSearchStateAndLinkHolderPointer() {
        val parent = AcceptedPath(targetNode = OsmNode(Position(1, 2)), cost = 10)
        val source = OsmNode(Position(1, 2))
        val target = OsmNode(Position(3, 4))
        val link = OsmLink(source, target)
        val state = StdPathState(ehbd = 1, ehbu = 2, totalTime = 3.0, totalEnergy = 4.0)

        val path = AcceptedPath(
            parent = parent,
            sourceNode = source,
            targetNode = target,
            link = link,
            cost = 20,
            airdistance = 30,
            treedepth = 2,
            originPosition = Position(5, 6),
            selev = 7,
            stdState = state,
        )

        assertSame(parent, path.parent)
        assertSame(source, path.sourceNode)
        assertSame(target, path.targetNode)
        assertSame(link, path.link)
        assertEquals(20, path.cost)
        assertEquals(30, path.airdistance)
        assertEquals(2, path.treedepth)
        assertEquals(Position(5, 6), path.originPosition)
        assertEquals(7.toShort(), path.selev)
        assertEquals(state, path.stdState)
        assertNull(path.nextForLink)
    }

    @Test
    fun acceptedPathExportsIndependentStdStateSnapshot() {
        val path = AcceptedPath(
            targetNode = OsmNode(Position(1, 2)),
            stdState = StdPathState(ehbd = 1, ehbu = 2, totalTime = 3.0, totalEnergy = 4.0),
        )

        val exported = path.exportStdState()
        exported.ehbd = 99

        assertEquals(1, path.stdState.ehbd)
        assertEquals(99, exported.ehbd)
    }

    @Test
    fun acceptedPathBehavesAsSearchPathBridgeButDoesNotEvaluateTransitions() {
        val source = OsmNode(Position(1, 2, 8))
        val target = OsmNode(Position(3, 4, 12))
        val link = OsmLink(source, target)
        val parent = AcceptedPath(targetNode = source, cost = 7, treedepth = 1)
        val path = AcceptedPath(
            parent = parent,
            sourceNode = source,
            targetNode = target,
            link = link,
            cost = 20,
            airdistance = 30,
            treedepth = 2,
            originPosition = Position(5, 6, 8),
            selev = 9,
            stdState = StdPathState(ehbd = 1, ehbu = 2, totalTime = 3.0, totalEnergy = 4.0),
        )

        assertEquals(20, path.cost)
        assertEquals(30, path.airdistance)
        assertSame(source, path.sourceNode)
        assertSame(target, path.targetNode)
        assertSame(link, path.link)
        assertSame(parent, path.parent)
        assertEquals(4.0, path.totalEnergy)
        // Skips the whole test when the segment fixture is not provisioned
        // (the assumption must fire OUTSIDE assertFailsWith, or it reads
        // as "wrong exception").
        ensureTestSegmentFile()
        assertFailsWith<UnsupportedOperationException> {
            path.init(
                parent,
                link,
                null,
                false,
                routingContextFromFiles(profilePath("fastbike.brf"), requireNotNull(testSegmentFile.parent))
            )
        }
    }
}
