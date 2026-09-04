package com.danemadsen.atlas.beerouter.util

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class ReducedMedianFilterTest {
    @Test
    fun reducedMedianFilterTest() {
        val filter = ReducedMedianFilter(10)
        filter.reset()
        filter.addSample(.2, 10)
        filter.addSample(.2, 10)
        filter.addSample(.2, 10)
        filter.addSample(.2, 15)
        filter.addSample(.2, 20)

        var median = filter.edgeReducedMedian(0.5)
        assertTrue(doubleEquals(median, 11.5), "median1 mismatch m=$median expected 11.5")

        filter.reset()
        filter.addSample(.2, 10)
        filter.addSample(.2, 10)
        filter.addSample(.2, 10)
        filter.addSample(.2, 10)
        filter.addSample(.2, 20)

        median = filter.edgeReducedMedian(1.0)
        assertTrue(doubleEquals(median, 12.0), "median1 mismatch m=$median expected 12")

        filter.reset()
        filter.addSample(.5, -10)
        filter.addSample(.5, 10)
        median = filter.edgeReducedMedian(0.5)
        assertTrue(doubleEquals(median, 0.0), "median2 mismatch m=$median expected 0")
    }

    private fun doubleEquals(left: Double, right: Double): Boolean = abs(left - right) < 1e-9
}
