package com.danemadsen.atlas.beerouter.router

import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.map.OsmLink
import com.danemadsen.atlas.beerouter.map.OsmNode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression guard for the 5°-bucket border crash ("Required value was
 * null."): a desc-less link takes the beeline branch of
 * OsmPath.addAddionalPenalty, which stock left with a null
 * originPosition — and KinematicPrePath.initPrePath requires it for every
 * candidate link, so the first expansion of the beeline path crashed
 * kinematic profiles (car-vario).
 */
class BorderBeelineRegressionTest {
    @Test
    fun beelineSectionCarriesSourcePositionAsOriginPosition() {
        ensureTestSegmentFile()
        val originNode = OsmNode(Position(1, 2))
        val targetNode = OsmNode(Position(3, 4))
        val origin = KinematicPath().apply {
            init(OsmLink(null, originNode))
        }
        val child = KinematicPath()
        val rc = routingContextFromFiles(profilePath("car-vario.brf"), requireNotNull(testSegmentFile.parent))

        child.init(origin, OsmLink(originNode, targetNode), null, false, rc)

        // a beeline runs straight from source to target, so the position
        // before its end is the source node — same semantics the described
        // section loop assigns at its endpoint
        assertEquals(Position(1, 2), child.originPosition)
    }
}