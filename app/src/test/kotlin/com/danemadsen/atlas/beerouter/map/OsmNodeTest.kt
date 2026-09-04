package com.danemadsen.atlas.beerouter.map

import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.geo.UNSET_ELEVATION
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class OsmNodeTest {
    @Test
    fun constructorStoresElevationOutsideBasePosition() {
        val node = OsmNode(Position(123, 456, 789))

        assertEquals(789.toShort(), node.altitude)
        assertEquals(Position(123, 456), node.position)
    }

    @Test
    fun positionWithAltitudeReusesCachedAltitudePosition() {
        val node = OsmNode(Position(123, 456))

        node.altitude = 789

        val first = node.positionWithAltitude()
        val second = node.positionWithAltitude()

        assertEquals(Position(123, 456, 789), first)
        assertSame(first, second)
    }

    @Test
    fun constructorReusesPositionWhenAltitudeIsUnset() {
        val position = Position(123, 456, UNSET_ELEVATION)

        val node = OsmNode(position)

        assertSame(position, node.position)
    }

    @Test
    fun addLinkByCoordinatesReusesExistingHollowNode() {
        val source = OsmNode(Position(1, 2))
        val target = OsmNode(Position(3, 4)).apply { setHollow() }
        val hollowNodes = OsmNodesMap()
        hollowNodes.put(target)
        val description = byteArrayOf(1, 2, 3)

        source.addLink(3, 4, description, null, hollowNodes, false)

        val link = source.firstlink!!
        assertSame(target, link.getTarget(source))
        assertSame(description, link.descriptionBitmap)
    }

    // The three tests below guard the 5°-bucket border fix: the writer
    // (OsmNodeP.writeNodeData2) stores the way description on reverse
    // records for external targets, but the runtime dropped it. A border
    // link whose instance was born from the reverse record then stayed
    // desc-less, and routing through it took the zero-cost beeline branch
    // — crashing kinematic profiles in KinematicPrePath and silently
    // making border crossings free.

    @Test
    fun reverseRecordWithDescriptionTagsFreshInstance() {
        val source = OsmNode(Position(1, 2))
        val hollowNodes = OsmNodesMap()
        val description = byteArrayOf(9, 9)

        source.addLink(3, 4, description, null, hollowNodes, true)

        val link = source.firstlink!!
        assertSame(description, link.descriptionBitmap)
    }

    @Test
    fun reverseRecordDoesNotOverwriteExistingDescription() {
        val source = OsmNode(Position(1, 2))
        val target = OsmNode(Position(3, 4)).apply { setHollow() }
        val hollowNodes = OsmNodesMap()
        hollowNodes.put(target)
        val forwardDescription = byteArrayOf(7)
        val reverseDescription = byteArrayOf(8)

        source.addLink(3, 4, forwardDescription, null, hollowNodes, false)
        source.addLink(3, 4, reverseDescription, null, hollowNodes, true)

        assertSame(forwardDescription, source.firstlink!!.descriptionBitmap)
    }

    @Test
    fun reverseRecordWithoutDescriptionKeepsExistingDescription() {
        // internal reverse records are written desc-less and must not wipe
        // the description an earlier forward record attached
        val source = OsmNode(Position(1, 2))
        val target = OsmNode(Position(3, 4)).apply { setHollow() }
        val hollowNodes = OsmNodesMap()
        hollowNodes.put(target)
        val forwardDescription = byteArrayOf(7)

        source.addLink(3, 4, forwardDescription, null, hollowNodes, false)
        source.addLink(3, 4, null, null, hollowNodes, true)

        assertSame(forwardDescription, source.firstlink!!.descriptionBitmap)
    }

    @Test
    fun forwardRecordCompletesReverseBornInstanceInsteadOfForking() {
        // regression for the merge-order defect found in patch-B verification:
        // the reverse record (parsed in A's body) pre-attaches the description
        // to the fresh proxy (technical inheritance). The forward record
        // (parsed later in B's body) must complete the SAME instance — filling
        // geometry — instead of failing the merge condition and forking a
        // duplicate, geometry-less parallel link.
        val hollowNodes = OsmNodesMap()
        val a = OsmNode(Position(1, 2))
        a.addLink(3, 4, byteArrayOf(9, 9), null, hollowNodes, true)

        val b = a.firstlink!!.getTarget(a)!!
        b.addLink(1, 2, byteArrayOf(1), byteArrayOf(1, 2, 3), hollowNodes, false)

        var count = 0
        var l: OsmLink? = a.firstlink
        while (l != null) {
            count++
            l = l.getNext(a)
        }
        assertEquals(1, count)
        assertEquals(1, a.firstlink!!.descriptionBitmap!!.size)
        assertEquals(3, a.firstlink!!.geometry!!.size)
    }

    @Test
    fun forwardRecordCompletesReverseBornHollowProxyInstance() {
        // same merge-order regression, but the reverse record merges into a
        // proxy that already exists in the hollow map (an OsmLink is created
        // rather than technical inheritance) — the forward record must still
        // complete that instance rather than fork a duplicate.
        val hollowNodes = OsmNodesMap()
        val target = OsmNode(Position(3, 4)).apply { setHollow() }
        hollowNodes.put(target)
        val a = OsmNode(Position(1, 2))

        a.addLink(3, 4, byteArrayOf(9, 9), null, hollowNodes, true)
        target.addLink(1, 2, byteArrayOf(1), byteArrayOf(1, 2, 3), hollowNodes, false)

        var count = 0
        var l: OsmLink? = a.firstlink
        while (l != null) {
            count++
            l = l.getNext(a)
        }
        assertEquals(1, count)
        assertEquals(3, a.firstlink!!.geometry!!.size)
    }

    @Test
    fun coordinateAccessorsTrackPositionChanges() {
        val node = OsmNode(1, 2)

        assertEquals(1, node.longitude)
        assertEquals(2, node.latitude)
        assertEquals(Position(1, 2).id, node.idFromPos)

        node.position = Position(3, 4)

        assertEquals(3, node.longitude)
        assertEquals(4, node.latitude)
        assertEquals(Position(3, 4).id, node.idFromPos)
    }

    @Test
    fun positionGetterReturnsCachedPositionView() {
        val node = OsmNode(1, 2)

        val first = node.position
        val second = node.position

        assertEquals(Position(1, 2), first)
        assertSame(first, second)

        node.position = Position(3, 4)

        assertEquals(Position(3, 4), node.position)
    }

    @Test
    fun positionSetterKeepsBasePositionAltitudeFree() {
        val node = OsmNode()

        node.position = Position(3, 4, 9)
        node.altitude = 12

        assertEquals(Position(3, 4), node.position)
        assertEquals(Position(3, 4, 12), node.positionWithAltitude())
    }
}
