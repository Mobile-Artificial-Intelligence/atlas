/**
 * Encoder for fast-compact encoding of number sequences
 *
 * @author ab
 */
package com.danemadsen.atlas.beerouter.util

public class MixCoderDataOutputStream {
    private var buf = ByteArray(256)
    private var bufPos = 0

    private var lastValue = 0
    private var lastLastValue = 0
    private var repCount = 0
    private var diffshift = 0

    private var bm = 1 // byte mask (write mode)
    private var b = 0

    public fun writeMixed(v: Int) {
        if (v != lastValue && repCount > 0) {
            var d = lastValue - lastLastValue
            lastLastValue = lastValue

            encodeBit(d < 0)
            if (d < 0) d = -d
            encodeVarBits(d - diffshift)
            encodeVarBits(repCount - 1)

            diffshift = 1
            repCount = 0
        }
        lastValue = v
        repCount++
    }

    public fun toByteArray(): ByteArray {
        val v = lastValue
        writeMixed(v + 1)
        lastValue = v
        repCount = 0
        if (bm > 1) writeByte(b)
        return buf.copyOf(bufPos)
    }

    private fun writeByte(v: Int) {
        if (bufPos >= buf.size) buf = buf.copyOf(buf.size * 2)
        buf[bufPos++] = v.toByte()
    }

    public fun encodeBit(value: Boolean) {
        if (bm == 0x100) {
            writeByte(b)
            bm = 1
            b = 0
        }
        if (value) b = b or bm
        bm = bm shl 1
    }

    public fun encodeVarBits(value: Int) {
        var value = value
        var range = 0
        while (value > range) {
            encodeBit(false)
            value -= range + 1
            range = 2 * range + 1
        }
        encodeBit(true)
        encodeBounded(range, value)
    }

    public fun encodeBounded(max: Int, value: Int) {
        var max = max
        var im = 1 // integer mask
        while (im <= max) {
            if (bm == 0x100) {
                writeByte(b)
                bm = 1
                b = 0
            }
            if ((value and im) != 0) {
                b = b or bm
                max -= im
            }
            bm = bm shl 1
            im = im shl 1
        }
    }
}
