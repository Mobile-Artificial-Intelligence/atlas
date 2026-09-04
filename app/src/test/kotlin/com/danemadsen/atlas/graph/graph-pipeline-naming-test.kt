package com.danemadsen.atlas.graph

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `bucketNameFor` must agree with the runtime's `PoiFinder.rd5FileName`
 * (offset-positive floor-mod), especially for negative degrees where
 * truncating division rounds the wrong way.
 */
class GraphPipelineNamingTest {

    @Test
    fun namesMatchRuntimeFloorModSemantics() {
        // Melbourne CBD -> E140_S40 (the differential fixture's bucket)
        assertEquals("E140_S40", GraphPipeline.bucketNameFor(144.9669, -37.8183))
        // Geelong's bucket border area
        assertEquals("E140_S40", GraphPipeline.bucketNameFor(144.368, -38.1493))
        // exactly on a SW corner
        assertEquals("E140_S40", GraphPipeline.bucketNameFor(140.0, -40.0))
        // one E6 north-east of the corner is already the next bucket
        assertEquals("E145_S40", GraphPipeline.bucketNameFor(145.000001, -40.0))
        // just south of -35 is still the S40 bucket (-40..-35)
        assertEquals("E140_S40", GraphPipeline.bucketNameFor(140.0, -35.000001))
        assertEquals("E140_S35", GraphPipeline.bucketNameFor(140.0, -34.999999))
    }

    @Test
    fun negativeDegreesFloorTowardMinusInfinity() {
        // -122.4 (San Francisco): floor is -123, bucket W125 — truncating
        // division would wrongly say W120
        assertEquals("W125_N35", GraphPipeline.bucketNameFor(-122.4, 37.77))
        // -37.81 floors to -38 -> S40, not the S35 a truncation gives
        assertEquals("E140_S40", GraphPipeline.bucketNameFor(140.5, -37.81))
        // multiple-of-5 negative values stay put
        assertEquals("W120_N40", GraphPipeline.bucketNameFor(-120.0, 40.0))
        // far-south negative latitude
        assertEquals("E170_S45", GraphPipeline.bucketNameFor(174.0, -41.3))
    }

    @Test
    fun extremeCoordinatesStayInGrid() {
        assertEquals("E175_N0", GraphPipeline.bucketNameFor(179.9, 0.0))
        assertEquals("W180_S90", GraphPipeline.bucketNameFor(-180.0, -90.0))
        assertEquals("E0_N0", GraphPipeline.bucketNameFor(0.0, 0.0))
    }
}