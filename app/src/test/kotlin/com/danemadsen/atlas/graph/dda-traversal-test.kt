package com.danemadsen.atlas.graph

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Correctness of the exact-integer DDA grid traversal
 * ([PmtilesCutter.forEachSegmentCell]) the stitch segment index is built
 * on. A missed cell means a missed junction repair, which silently breaks
 * routing — so the core property asserted here is COVERAGE: every point of
 * the segment lies in a visited cell (exactly-sampled, no tolerance).
 *
 * The float version this replaced could also walk an axis to infinity
 * after stepping past its end cell (measured: one runaway segment OOMed
 * the whole build at 4 GB), so every case asserts the visited set stays
 * within the end cells' span.
 */
class DdaTraversalTest {

    private val lon_mask = 0xFFFFFFFFL

    private fun pack(x: Long, y: Long): Long = (x shl 32) or (y and lon_mask)

    private fun visited(x0: Long, y0: Long, x1: Long, y1: Long, grid: Long): Set<Long> {
        val out = HashSet<Long>()
        PmtilesCutter().forEachSegmentCell(pack(x0, y0), pack(x1, y1), grid) { out.add(it) }
        return out
    }

    private fun cellOf(x: Long, y: Long, grid: Long): Long =
        (x / grid shl 32) or (y / grid)

    /**
     * The full property battery over one segment: every exactly-on-segment
     * sampled point's cell is visited; no visited cell falls outside the
     * end cells' span; the set grows at most one entry per boundary
     * crossing.
     */
    private fun assertTraversalCorrect(x0: Long, y0: Long, x1: Long, y1: Long, grid: Long) {
        val visited = visited(x0, y0, x1, y1, grid)
        val sx = x1 - x0
        val sy = y1 - y0
        val steps = 512
        for (k in 0..steps) {
            val px = x0 + sx * k / steps
            val py = y0 + sy * k / steps
            // Integer sampling floors the exact rational point, so only
            // samples that land exactly on the line are asserted.
            if ((px - x0) * sy == (py - y0) * sx) {
                assertTrue(
                    cellOf(px, py, grid) in visited,
                    "point ($px,$py) k=$k of ($x0,$y0)->($x1,$y1) grid=$grid " +
                        "lies in unvisited cell; |visited|=${visited.size}",
                )
            }
        }
        val cxa = x0 / grid
        val cxb = x1 / grid
        val cya = y0 / grid
        val cyb = y1 / grid
        for (cell in visited) {
            val cxi = cell shr 32
            val cyi = cell and lon_mask
            assertTrue(
                cxi >= minOf(cxa, cxb) && cxi <= maxOf(cxa, cxb) &&
                    cyi >= minOf(cya, cyb) && cyi <= maxOf(cya, cyb),
                "visited cell ($cxi,$cyi) outside end-cell span of " +
                    "($x0,$y0)->($x1,$y1) grid=$grid",
            )
        }
        assertTrue(
            visited.size <= abs(cxb - cxa) + abs(cyb - cya) + 1,
            "visited ${visited.size} cells for ($x0,$y0)->($x1,$y1) grid=$grid, " +
                "more than crossings+1",
        )
    }

    @Test
    fun covers_degenerate_and_single_cell_segments() {
        assertTraversalCorrect(12345L, 6789L, 12345L, 6789L, 5000L)
        assertTraversalCorrect(12345L, 6789L, 12346L, 6790L, 5000L)
        // Both endpoints on the same cell corner.
        assertTraversalCorrect(10_000L, 20_000L, 10_000L, 20_000L, 5000L)
        assertEquals(setOf(cellOf(12345L, 6789L, 5000L)), visited(12345L, 6789L, 12345L, 6789L, 5000L))
    }

    @Test
    fun covers_axis_aligned_walks() {
        // Pure x walk across several cells.
        assertTraversalCorrect(1_234L, 6_789L, 1_234L + 5 * 5000L + 4321L, 6_789L, 5000L)
        // Pure y walk.
        assertTraversalCorrect(6_789L, 1_234L, 6_789L, 1_234L + 7 * 5000L + 1L, 5000L)
        // Walks landing exactly on a cell boundary.
        assertTraversalCorrect(1_234L, 6_789L, 25_000L, 6_789L, 5000L)
        assertTraversalCorrect(1_234L, 6_789L, 1_234L, 25_000L, 5000L)
    }

    @Test
    fun covers_exact_corner_diagonal_walks() {
        // Step exactly one cell per axis: every sample point sits on a cell
        // corner — the tie-handling (take x first) must still leave the
        // corner's floor cell visited.
        assertTraversalCorrect(5_000L, 5_000L, 5_000L + 20 * 5000L, 5_000L + 20 * 5000L, 5000L)
        assertTraversalCorrect(5_000L, 5_000L, 5_000L + 20 * 5000L, 5_000L + 20 * 5000L, 5000L)
        // Anti-diagonal.
        assertTraversalCorrect(5_000L, 105_000L, 105_000L, 5_000L, 5000L)
        // Diagonal with an offset that makes crossings land exactly on
        // corners only at the last step.
        assertTraversalCorrect(1_111L, 2_222L, 1_111L + 33 * 5000L, 2_222L + 33 * 5000L, 5000L)
        // 2:1 slope — every x boundary crossed, every second y boundary.
        assertTraversalCorrect(0L, 0L, 40_000L, 20_000L, 5000L)
        assertTraversalCorrect(0L, 0L, 20_000L, 40_000L, 5000L)
    }

    @Test
    fun covers_random_segments_across_grids() {
        val grids = longArrayOf(5000L, 50_000L, 10L)
        for (seed in 0 until 300) {
            val random = Random(seed)
            val grid = grids[seed % grids.size]
            val span = grid * 200
            val x0 = random.nextLong(span)
            val y0 = random.nextLong(span)
            val x1 = random.nextLong(span)
            val y1 = random.nextLong(span)
            assertTraversalCorrect(x0, y0, x1, y1, grid)
            // Every so often snap an endpoint exactly onto a cell boundary.
            if (seed % 7 == 0) {
                assertTraversalCorrect(x0, y0, x1 - x1 % grid, y1 - y1 % grid, grid)
            }
        }
    }

    @Test
    fun stays_bounded_on_world_spanning_extremes() {
        // Worst legal extent: full longitude and latitude spans at the
        // production grid. 108k crossings must terminate with at most one
        // visited cell per crossing — the float version ran away here.
        assertTraversalCorrect(0L, 0L, 360_000_000L, 180_000_000L, 5000L)
        assertTraversalCorrect(360_000_000L, 180_000_000L, 0L, 0L, 5000L)
        assertTraversalCorrect(0L, 180_000_000L, 360_000_000L, 0L, 5000L)
        // Degenerate extremes: zero-length at the far corner.
        assertTraversalCorrect(360_000_000L, 180_000_000L, 360_000_000L, 180_000_000L, 5000L)
    }
}