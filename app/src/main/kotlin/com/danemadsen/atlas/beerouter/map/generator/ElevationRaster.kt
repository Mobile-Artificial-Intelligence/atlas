package com.danemadsen.atlas.beerouter.map.generator

import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.geo.encodeAltitudeMeters
import com.danemadsen.atlas.beerouter.util.ReducedMedianFilter
import kotlin.math.cos

public class ElevationRaster {
    public var ncols: Int = 0
    public var nrows: Int = 0
    public var halfcol: Boolean = false
    public var xllcorner: Double = 0.0
    public var yllcorner: Double = 0.0
    public var cellsize: Double = 0.0
    public lateinit var eval_array: ShortArray
    public var noDataValue: Short = 0
    public var usingWeights: Boolean = false
    private var missingData: Boolean = false
    private val rmf: ReducedMedianFilter = ReducedMedianFilter(256)

    public fun getElevation(position: Position): Short {
        val lon = position.longitudeDegree
        val lat = position.latitudeDegree
        if (usingWeights) {
            return getElevationFromShiftWeights(lon, lat)
        }
        val dcol = (lon - xllcorner) / cellsize - 0.5
        val drow = (lat - yllcorner) / cellsize - 0.5
        var row = drow.toInt().coerceIn(0, nrows - 2)
        var col = dcol.toInt().coerceIn(0, ncols - 2)
        val wrow = drow - row
        val wcol = dcol - col
        missingData = false
        val eval = (1.0 - wrow) * (1.0 - wcol) * get(row, col) +
                wrow * (1.0 - wcol) * get(row + 1, col) +
                (1.0 - wrow) * wcol * get(row, col + 1) +
                wrow * wcol * get(row + 1, col + 1)
        return if (missingData) Short.MIN_VALUE else encodeAltitudeMeters(eval)
    }

    private fun get(r: Int, c: Int): Short {
        val e = eval_array[(nrows - 1 - r) * ncols + c]
        if (e == Short.MIN_VALUE) missingData = true
        return e
    }

    private fun getElevationFromShiftWeights(lon: Double, lat: Double): Short {
        var alat = if (lat < 0.0) -lat else lat
        alat /= 5.0
        val latIdx = alat.toInt()
        val wlat = alat - latIdx
        val dcol = (lon - xllcorner) / cellsize
        val drow = (lat - yllcorner) / cellsize
        val row = drow.toInt()
        val col = dcol.toInt()
        val dgx = (dcol - col) * gridSteps
        val dgy = (drow - row) * gridSteps
        val gx = dgx.toInt()
        val gy = dgy.toInt()
        val wx = dgx - gx
        val wy = dgy - gy
        val w00 = (1.0 - wx) * (1.0 - wy)
        val w01 = (1.0 - wx) * wy
        val w10 = wx * (1.0 - wy)
        val w11 = wx * wy
        val w0 = getWeights(latIdx)
        val w1 = getWeights(latIdx + 1)
        missingData = false
        val m0 = w00 * getElevation(w0[gx][gy], row, col) +
                w01 * getElevation(w0[gx][gy + 1], row, col) +
                w10 * getElevation(w0[gx + 1][gy], row, col) +
                w11 * getElevation(w0[gx + 1][gy + 1], row, col)
        val m1 = w00 * getElevation(w1[gx][gy], row, col) +
                w01 * getElevation(w1[gx][gy + 1], row, col) +
                w10 * getElevation(w1[gx + 1][gy], row, col) +
                w11 * getElevation(w1[gx + 1][gy + 1], row, col)
        if (missingData) return Short.MIN_VALUE
        return encodeAltitudeMeters(((1.0 - wlat) * m0 + wlat * m1) / 2.0)
    }

    private fun getElevation(weights: Weights, row: Int, col: Int): Double {
        if (missingData) return 0.0
        val mx = weights.nx / 2
        val my = weights.ny / 2
        rmf.reset()
        for (ix in 0 until weights.nx) {
            for (iy in 0 until weights.ny) {
                rmf.addSample(weights.getWeight(ix, iy), get(row + iy - my, col + ix - mx).toInt())
            }
        }
        return if (missingData) 0.0 else rmf.edgeReducedMedian(filterCenterFraction)
    }

    override fun toString(): String =
        "$ncols,$nrows,$halfcol,$xllcorner,$yllcorner,$cellsize,$noDataValue,$usingWeights"

    private class Weights(public val nx: Int, public val ny: Int) {
        private val weights: DoubleArray = DoubleArray(nx * ny)
        private var total: Long = 0
        internal fun inc(ix: Int, iy: Int) {
            weights[iy * nx + ix] += 1.0
            total++
        }

        internal fun normalize(verbose: Boolean) {
            for (iy in 0 until ny) {
                val sb = if (verbose) StringBuilder() else null
                for (ix in 0 until nx) {
                    weights[iy * nx + ix] /= total
                    if (sb != null) {
                        val iweight = (1000 * weights[iy * nx + ix] + 0.5).toInt()
                        val sval = "     $iweight"
                        sb.append(sval.substring(sval.length - 4))
                    }
                }
                if (sb != null) {
                    println(sb)
                    println()
                }
            }
        }

        internal fun getWeight(ix: Int, iy: Int): Double = weights[iy * nx + ix]
    }

    public companion object {
        private var gridSteps: Int = 10
        private val allShiftWeights: Array<Array<Array<Weights>>?> = arrayOfNulls(17)
        private var filterCenterFraction: Double = 0.2
        private var filterDiscRadius: Double = 4.999

        init {
            System.getProperty("filterDiscRadius")?.takeIf { it.isNotEmpty() }?.let {
                filterDiscRadius = it.toInt().toDouble()
            }
            System.getProperty("filterCenterFraction")?.takeIf { it.isNotEmpty() }?.let {
                filterCenterFraction = it.toInt() / 100.0
            }
        }

        private fun getWeights(latIndex: Int): Array<Array<Weights>> {
            val idx = if (latIndex < 16) latIndex else 16
            var res = allShiftWeights[idx]
            if (res == null) {
                res = buildWeights(idx)
                allShiftWeights[idx] = res
            }
            return requireNotNull(res)
        }

        private fun buildWeights(latIndex: Int): Array<Array<Weights>> {
            val coslat = cos(latIndex * 5.0 / 57.3)
            val ry = filterDiscRadius
            val rx = ry / coslat
            val nx = rx.toInt() * 2 + 3
            val ny = ry.toInt() * 2 + 3
            val mx = nx / 2
            val my = ny / 2
            return Array(gridSteps + 1) { gx ->
                Array(gridSteps + 1) { gy ->
                    val x0 = mx + gx.toDouble() / gridSteps
                    val y0 = my + gy.toDouble() / gridSteps
                    val weights = Weights(nx, ny)
                    val sampleStep = 0.001
                    var x = -1.0 + sampleStep / 2.0
                    while (x < 1.0) {
                        val mx2 = 1.0 - x * x
                        val xIdx = (x0 + x * rx).toInt()
                        var y = -1.0 + sampleStep / 2.0
                        while (y < 1.0) {
                            if (y * y <= mx2) {
                                weights.inc(xIdx, (y0 + y * ry).toInt())
                            }
                            y += sampleStep
                        }
                        x += sampleStep
                    }
                    weights.normalize(false)
                    weights
                }
            }
        }
    }
}
