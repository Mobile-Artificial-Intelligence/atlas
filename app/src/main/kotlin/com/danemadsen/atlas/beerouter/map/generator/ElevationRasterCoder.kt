package com.danemadsen.atlas.beerouter.map.generator

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

public class ElevationRasterCoder {
    public fun encodeRaster(raster: ElevationRaster, os: OutputStream) {
        val dos = DataOutputStream(os)
        dos.writeInt(raster.ncols)
        dos.writeInt(raster.nrows)
        dos.writeBoolean(raster.halfcol)
        dos.writeDouble(raster.xllcorner)
        dos.writeDouble(raster.yllcorner)
        dos.writeDouble(raster.cellsize)
        dos.writeShort(raster.noDataValue.toInt())
        encodeRasterData(raster, os)
    }

    public fun decodeRaster(`is`: InputStream): ElevationRaster {
        val dis = DataInputStream(`is`)
        return ElevationRaster().also { raster ->
            raster.ncols = dis.readInt()
            raster.nrows = dis.readInt()
            raster.halfcol = dis.readBoolean()
            raster.xllcorner = dis.readDouble()
            raster.yllcorner = dis.readDouble()
            raster.cellsize = dis.readDouble()
            raster.noDataValue = dis.readShort()
            raster.eval_array = ShortArray(raster.ncols * raster.nrows)
            decodeRasterData(raster, `is`)
            raster.usingWeights = false
        }
    }

    private fun encodeRasterData(raster: ElevationRaster, os: OutputStream) {
        val mco = MixCoderDataOutputStream(os)
        val colstep = if (raster.halfcol) 2 else 1
        for (row in 0 until raster.nrows) {
            var lastval = Short.MIN_VALUE
            var col = 0
            while (col < raster.ncols) {
                var value = raster.eval_array[row * raster.ncols + col]
                if (value.toInt() == -32766) {
                    value = lastval
                } else {
                    lastval = value
                }
                val code =
                    if (value == Short.MIN_VALUE) -1 else if (value < 0) value - 1 else value.toInt()
                mco.writeMixed(code)
                col += colstep
            }
        }
        mco.flush()
    }

    private fun decodeRasterData(raster: ElevationRaster, `is`: InputStream) {
        val mci = MixCoderDataInputStream(`is`)
        val colstep = if (raster.halfcol) 2 else 1
        for (row in 0 until raster.nrows) {
            var col = 0
            while (col < raster.ncols) {
                val code = mci.readMixed()
                var v30 =
                    if (code == -1) Short.MIN_VALUE.toInt() else if (code < 0) code + 1 else code
                if (raster.usingWeights && v30 > -32766) {
                    v30 *= 2
                }
                raster.eval_array[row * raster.ncols + col] = v30.toShort()
                col += colstep
            }
            if (raster.halfcol) {
                col = 1
                while (col < raster.ncols - 1) {
                    val l = raster.eval_array[row * raster.ncols + col - 1].toInt()
                    val r = raster.eval_array[row * raster.ncols + col + 1].toInt()
                    raster.eval_array[row * raster.ncols + col] =
                        if (l > -32766 && r > -32766) ((l + r) / 2).toShort() else Short.MIN_VALUE
                    col += colstep
                }
            }
        }
    }
}
