package com.danemadsen.atlas.pmtiles

/**
 * TileId <-> (z, x, y) mapping per PMTiles v3: a cumulative position on the
 * Hilbert curves of each zoom, where the ids of zoom z start after all ids of
 * the zooms below it:
 *
 *   acc(z) = 4^0 + 4^1 + ... + 4^(z-1)
 *   tileId(z, x, y) = acc(z) + hilbertIndex(x, y, 2^z)
 *
 * Verified against the spec's reference vectors (z12/x3423/y1763 -> 19078479).
 */
object HilbertTileId {

    fun tileId(z: Int, x: Int, y: Int): Long {
        require(z in 0..31) { "zoom must be 0..31, was $z" }
        if (z == 0) return 0L
        var acc = 0L
        for (tz in 0 until z) {
            acc = acc or (1L shl (2 * tz))
        }
        return acc + xy2d(x, y, 1 shl z)
    }

    fun zxyFromTileId(tileId: Long): Triple<Int, Int, Int> {
        var acc = 0L
        var z = 0
        while (z <= 31) {
            val numTiles = 1L shl (2 * z)
            if (acc + numTiles > tileId) {
                val (x, y) = d2xy(tileId - acc, 1 shl z)
                return Triple(z, x, y)
            }
            acc += numTiles
            z++
        }
        throw IllegalArgumentException("tile id out of range: $tileId")
    }

    /** Standard Hilbert curve index (Wikipedia convention), matching the
     *  PMTiles id order: z1 (0,0)->(0,1)->(1,1)->(1,0). */
    private fun xy2d(x_in: Int, y_in: Int, n: Int): Long {
        var x = x_in
        var y = y_in
        var d = 0L
        var s = n / 2
        while (s > 0) {
            val rx = if ((x and s) != 0) 1 else 0
            val ry = if ((y and s) != 0) 1 else 0
            d += s.toLong() * s * ((3 * rx) xor ry)
            if (ry == 0) {
                if (rx == 1) {
                    x = n - 1 - x
                    y = n - 1 - y
                }
                val tmp = x
                x = y
                y = tmp
            }
            s /= 2
        }
        return d
    }

    private fun d2xy(d_in: Long, n: Int): Pair<Int, Int> {
        var t = d_in
        var x = 0
        var y = 0
        var s = 1
        while (s < n) {
            val rx = (t shr 1).toInt() and 1
            val ry = (t.toInt() xor rx) and 1
            if (ry == 0) {
                if (rx == 1) {
                    x = s - 1 - x
                    y = s - 1 - y
                }
                val tmp = x
                x = y
                y = tmp
            }
            x += s * rx
            y += s * ry
            t = t shr 2
            s *= 2
        }
        return x to y
    }
}