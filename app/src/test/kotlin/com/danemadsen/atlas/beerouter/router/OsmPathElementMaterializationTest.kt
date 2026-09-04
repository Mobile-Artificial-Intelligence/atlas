package com.danemadsen.atlas.beerouter.router

import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.map.OsmLink
import com.danemadsen.atlas.beerouter.map.OsmNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class OsmPathElementMaterializationTest {
    @Test
    fun deferredOriginElementMaterializesOnlyWhenRequested() {
        val origin = StdPath().apply {
            targetNode = OsmNode(Position(1, 2))
            cost = 123
        }
        val child = StdPath()

        child.deferOriginElement(origin)

        assertNull(origin.myElement)
        assertNull(child.originElement)

        child.materializeOriginElement()

        assertSame(origin.myElement, child.originElement)
        assertEquals(Position(1, 2), child.originElement!!.position)
        assertEquals(123, child.originElement!!.cost)
    }

    @Test
    fun nonDetailInitKeepsParentCostWithoutMaterializingOriginElement() {
        ensureTestSegmentFile()
        val originNode = OsmNode(Position(1, 2))
        val targetNode = OsmNode(Position(3, 4))
        val origin = StdPath().apply {
            init(OsmLink(null, originNode))
            cost = 123
        }
        val child = StdPath()
        val rc = routingContextFromFiles(profilePath("fastbike.brf"), requireNotNull(testSegmentFile.parent))

        child.init(origin, OsmLink(originNode, targetNode), null, false, rc)

        assertNull(origin.myElement)
        assertNull(child.originElement)
        assertEquals(123, child.originCost)
    }
}
