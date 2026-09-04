package com.danemadsen.atlas.beerouter.router

import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.map.OsmLink
import com.danemadsen.atlas.beerouter.map.OsmNode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StdPathReuseTest {
    @Test
    fun recycledStdPathDoesNotRetainEscapingState() {
        val model = StdModel()
        val source = OsmNode(Position(1, 2))
        val target = OsmNode(Position(3, 4))
        val link = OsmLink(source, target)
        val originElement = OsmPathElement(Position(5, 6), null)

        val path = model.createPath() as StdPath
        path.init(link)
        path.cost = 123
        path.selev = 99
        path.airdistance = 456
        path.sourceNode = source
        path.targetNode = target
        path.originElement = originElement
        path.myElement = OsmPathElement(Position(7, 8), originElement)
        path.treedepth = 9
        path.originPosition = Position(10, 11)
        path.message = MessageData()
        path.nextForLink = path

        model.recyclePath(path)

        val reused = model.createPath() as StdPath
        assertSame(path, reused)
        assertEquals(0, reused.cost)
        assertEquals(0, reused.selev)
        assertEquals(0, reused.airdistance)
        assertEquals(0, reused.treedepth)
        assertNull(reused.sourceNode)
        assertNull(reused.targetNode)
        assertNull(reused.link)
        assertNull(reused.originElement)
        assertNull(reused.myElement)
        assertNull(reused.originPosition)
        assertNull(reused.message)
        assertNull(reused.nextForLink)
    }

    @Test
    fun stdModelTracksAcceptedSnapshotsSeparatelyFromRejectedCandidates() {
        val model = StdModel()

        assertEquals(0, model.acceptedPathSnapshots)
        model.recordAcceptedPathSnapshotForTest()
        assertEquals(1, model.acceptedPathSnapshots)
    }

    @Test
    fun stdModelAcceptedSnapshotsIncreaseOnRouteGuard() {
        val rc = routingContextFromFiles(profilePathForSegments("trekking.brf", generatedTestSegmentDir), generatedTestSegmentDir)
        val model = rc.pm as StdModel
        val engine = RoutingEngine(rc)

        val track = runBlocking {
            engine.doRouting(
                listOf(
                    OsmNodeNamed(Position.fromDegrees(9.053658, 48.520272)).also { it.name = "Tübingen A" },
                    OsmNodeNamed(Position.fromDegrees(9.053500, 48.520189)).also { it.name = "Tübingen B" },
                )
            )
        }

        assertEquals(15, requireNotNull(track).distance)
        assertTrue(model.acceptedPathSnapshots > 0)
    }
}
