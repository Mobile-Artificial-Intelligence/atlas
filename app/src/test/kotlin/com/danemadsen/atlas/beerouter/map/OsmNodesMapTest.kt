package com.danemadsen.atlas.beerouter.map

import com.danemadsen.atlas.beerouter.geo.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OsmNodesMapTest {

    @Test
    fun cleanupPeninsulasRemovesDeadSubtree() {
        val map = OsmNodesMap()
        map.cleanupMode = OsmNodesMap.CleanupMode.PENINSULAS
        val a = node(180000000, 90000000)
        val b = node(180000100, 90000000)
        val c = node(180000200, 90000000)
        val linkAB = OsmLink()
        val linkBC = OsmLink()
        a.addLink(linkAB, false, b)
        b.addLink(linkBC, false, c)
        a.visitID = 1
        b.visitID = 0
        c.visitID = 0

        map.cleanupAndCount(listOf(a, b, c))

        assertTrue(a.firstlink == null || a.firstlink!!.isLinkUnused)
    }

    @Test
    fun cleanupAndCountAcceptsNodeArrayPrefix() {
        val map = OsmNodesMap()
        val active = node(180000000, 90000000)
        val inactive = node(180000100, 90000000)
        val ignored = node(180000200, 90000000)
        active.addLink(OsmLink(), false, inactive)

        map.cleanupAndCount(arrayOf<Any?>(active, inactive, ignored), 2)

        assertTrue(map.nodesCreated == 2)
    }

    @Test
    fun cleanupAndCountIgnoresUnmaterializedNodeSlots() {
        val map = OsmNodesMap()
        val active = node(180000000, 90000000)
        active.addLink(OsmLink(), false, node(180000100, 90000000))

        map.cleanupAndCount(arrayOf<Any?>(null, active, null), 3)

        assertEquals(1, map.nodesCreated)
    }

    /**
     * minVisitIdInSubtree must remain iterative; a deep linear graph used to
     * risk StackOverflowError when each node in the chain added a call frame.
     */
    @Test
    fun cleanupPeninsulasDoesNotStackOverflowOnDeepLinearGraph() {
        // 10 000 nodes in a chain is enough to exhaust the JVM stack
        val nodeCount = 10_000
        val nodes = (0 until nodeCount).map { i -> OsmNode(i, 0) }

        // Chain: nodes[0] — nodes[1] — nodes[2] — … — nodes[N-1]
        for (i in 0 until nodeCount - 1) {
            nodes[i].addLink(OsmLink(), false, nodes[i + 1])
        }

        // visitID == 1 marks a border node; cleanupPeninsulas starts DFS from it
        nodes[0].visitID = 1

        val map = OsmNodesMap()
        map.cleanupMode = OsmNodesMap.CleanupMode.PENINSULAS
        map.cleanupAndCount(nodes)
    }

    private fun node(lon: Int, lat: Int): OsmNode = OsmNode(Position(lon, lat)).apply { clearHollow() }
}
