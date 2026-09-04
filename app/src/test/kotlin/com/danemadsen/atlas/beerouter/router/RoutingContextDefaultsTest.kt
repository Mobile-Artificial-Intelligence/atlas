package com.danemadsen.atlas.beerouter.router

import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.map.RoutingMemoryPolicy
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RoutingContextDefaultsTest {
    @BeforeTest
    fun before() {
        ensureTestSegmentFile()
    }

    private fun loadContext(profileName: String): RoutingContext {
        val profile = profilePath(profileName)
        return routingContextFromFiles(profile, requireNotNull(profile.parent))
    }

    @Test
    fun shortestProfileKeepsRoundaboutInstructionsByDefault() {
        val context = loadContext("shortest.brf")

        assertTrue(context.global.footMode)
        assertTrue(context.global.turnInstructionRoundabouts)
    }

    @Test
    fun fastbikeProfileKeepsRoundaboutInstructionsByDefault() {
        val context = loadContext("fastbike.brf")

        assertFalse(context.global.footMode)
        assertTrue(context.global.turnInstructionRoundabouts)
    }

    @Test
    fun turnGenerationCanBeDisabledFromRoutingContext() = runBlocking {
        val waypoints = listOf(
            OsmNodeNamed(Position(9.053596, 48.5203263)).apply { name = "Tuebingen" },
            OsmNodeNamed(Position(9.2043, 48.4914)).apply { name = "Reutlingen" },
            OsmNodeNamed(Position(9.1829, 48.7758)).apply { name = "Stuttgart" }
        )
        val profile = profilePath("fastbike.brf")
        val segmentDir = requireNotNull(testSegmentFile.parent)

        val withTurns = RoutingEngine(routingContextFromFiles(profile, segmentDir)).doRouting(waypoints)
        val withoutTurns = RoutingEngine(
            routingContextFromFiles(profile, segmentDir, generateTurns = false)
        ).doRouting(waypoints)

        assertNotNull(withTurns)
        assertNotNull(withoutTurns)
        assertTrue(withTurns.voiceHints.isNotEmpty())
        assertEquals(0, withoutTurns.voiceHints.size)
    }

    @Test
    fun memoryPolicyCanBeConfiguredAtConstruction() {
        val policy = RoutingMemoryPolicy.withTotalHardLimitMegabytes(256)
        val context = routingContextFromFiles(
            profile = profilePath("fastbike.brf"),
            segmentDir = requireNotNull(testSegmentFile.parent),
            memoryPolicy = policy,
        )

        assertEquals(policy, context.memoryPolicy)
    }

    @Test
    fun profileOverridesCanSetRoutingPassCoefficients() {
        val context = routingContextFromFiles(
            profile = profilePath("fastbike.brf"),
            segmentDir = requireNotNull(testSegmentFile.parent),
            profileOverrides = mapOf(
                "pass1coefficient" to "-1",
                "pass2coefficient" to "2.25",
            ),
        )

        assertEquals(-1.0, context.global.pass1coefficient)
        assertEquals(2.25, context.global.pass2coefficient)
    }
}
