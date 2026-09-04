package com.danemadsen.atlas.pmtiles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HilbertTileIdTest {

    @Test
    fun specReferenceVectors() {
        // The table from the PMTiles v3 spec.
        assertEquals(0L, HilbertTileId.tileId(0, 0, 0))
        assertEquals(1L, HilbertTileId.tileId(1, 0, 0))
        assertEquals(2L, HilbertTileId.tileId(1, 0, 1))
        assertEquals(3L, HilbertTileId.tileId(1, 1, 1))
        assertEquals(4L, HilbertTileId.tileId(1, 1, 0))
        assertEquals(5L, HilbertTileId.tileId(2, 0, 0))
        assertEquals(19_078_479L, HilbertTileId.tileId(12, 3423, 1763))
    }

    @Test
    fun roundTripThroughZooms() {
        for (z in 0..14) {
            // Spot-check the corners plus a few interior tiles per zoom.
            val max = (1 shl z) - 1
            val candidates = listOf(0 to 0, max to 0, 0 to max, max to max, max / 3 to max / 2)
            for ((x, y) in candidates) {
                val id = HilbertTileId.tileId(z, x, y)
                val (rz, rx, ry) = HilbertTileId.zxyFromTileId(id)
                assertEquals(Triple(z, x, y), Triple(rz, rx, ry), "z=$z x=$x y=$y")
            }
        }
    }

    @Test
    fun everyZoomsIdsFormAContiguousRange() {
        // Ids must be contiguous per zoom: walking ids sequentially must stay
        // inside each zoom's range and round-trip back to (z, x, y).
        var expectedZ = 0
        for (id in 0L until HilbertTileId.tileId(6, 0, 0)) {
            val (z, _, _) = HilbertTileId.zxyFromTileId(id)
            assertTrue(z >= expectedZ, "id $id jumped back to zoom $z after $expectedZ")
            if (z > expectedZ) {
                // The first id of a zoom is the first tile of its curve.
                assertEquals(HilbertTileId.tileId(z, 0, 0), id)
                expectedZ = z
            }
        }
    }
}