package com.danemadsen.atlas.beerouter.router

import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.map.OsmNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PathTraceBuilderTest {
    @Test
    fun buildsTerminalElementFromCurrentOsmPathCompatibilityPath() {
        val origin = StdPath().apply {
            targetNode = OsmNode(Position(1, 2, 12))
            cost = 10
        }
        val child = StdPath().apply {
            targetNode = OsmNode(Position(3, 4, 20))
            cost = 30
            message = MessageData().also { it.linkdist = 7 }
            deferOriginElement(origin)
        }

        val element = PathTraceBuilder.toPathElement(child)

        assertEquals(Position(3, 4, 20), element.position)
        assertEquals(30, element.cost)
        assertEquals(7, element.message?.linkdist)
        assertEquals(Position(1, 2, 12), element.origin?.position)
        assertEquals(10, element.origin?.cost)
        assertSame(origin.myElement, element.origin)
    }

    @Test
    fun finalTrackAndMatchPathConversionsUseSameCompatibilityElementForNow() {
        val path = StdPath().apply {
            targetNode = OsmNode(Position(9, 10, 11))
            cost = 44
        }

        assertEquals(PathTraceBuilder.toFinalTrackElement(path).position, PathTraceBuilder.toMatchPathElement(path).position)
        assertEquals(44, PathTraceBuilder.toDetourElement(path).cost)
    }

    @Test
    fun buildsFinalTrackElementFromAcceptedPathParentChain() {
        val root = AcceptedPath(
            targetNode = OsmNode(Position(1, 2, 8)),
            cost = 10,
            stdState = StdPathState(totalTime = 1.5, totalEnergy = 2.5),
        )
        val child = AcceptedPath(
            parent = root,
            sourceNode = root.targetNode,
            targetNode = OsmNode(Position(3, 4, 12)),
            cost = 30,
            stdState = StdPathState(totalTime = 4.5, totalEnergy = 6.5),
        )

        val element = PathTraceBuilder.toFinalTrackElement(child)

        assertEquals(Position(3, 4, 12), element.position)
        assertEquals(30, element.cost)
        assertEquals(4.5f, element.time)
        assertEquals(6.5f, element.energy)
        assertEquals(Position(1, 2, 8), element.origin?.position)
        assertEquals(10, element.origin?.cost)
    }

    @Test
    fun acceptedPathTraceMaterializationIsIterativeForDeepRoutes() {
        var path = AcceptedPath(
            targetNode = OsmNode(Position(0, 0, 1)),
            cost = 0,
        )
        repeat(20_000) { idx ->
            path = AcceptedPath(
                parent = path,
                targetNode = OsmNode(Position(idx + 1, 0, 1)),
                cost = idx + 1,
            )
        }

        val element = PathTraceBuilder.toFinalTrackElement(path)

        assertEquals(20_000, element.cost)
        var count = 0
        var cursor: OsmPathElement? = element
        while (cursor != null) {
            count++
            cursor = cursor.origin
        }
        assertEquals(20_001, count)
    }
}
