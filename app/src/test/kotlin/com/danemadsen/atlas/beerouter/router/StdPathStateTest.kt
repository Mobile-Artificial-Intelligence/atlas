package com.danemadsen.atlas.beerouter.router

import kotlin.test.Test
import kotlin.test.assertEquals

class StdPathStateTest {
    @Test
    fun stdPathStateCanRoundTripThroughCompatibilityPath() {
        val source = StdPath()
        source.totalTime = 12.5
        source.totalEnergy = 99.25
        source.importStdState(
            StdPathState(
                ehbd = 1,
                ehbu = 2,
                totalTime = 12.5,
                totalEnergy = 99.25,
                elevationBuffer = 3.5f,
                uphillcostdiv = 4,
                downhillcostdiv = 5,
            )
        )

        val state = source.exportStdState()

        assertEquals(1, state.ehbd)
        assertEquals(2, state.ehbu)
        assertEquals(12.5, state.totalTime)
        assertEquals(99.25, state.totalEnergy)
        assertEquals(3.5f, state.elevationBuffer)
        assertEquals(4, state.uphillcostdiv)
        assertEquals(5, state.downhillcostdiv)
    }

    @Test
    fun stdPathStateComputesElevationCorrectionLikeStdPath() {
        val state = StdPathState(ehbd = 100, ehbu = 200, downhillcostdiv = 10, uphillcostdiv = 20)

        assertEquals(20, state.elevationCorrection())
    }
}
