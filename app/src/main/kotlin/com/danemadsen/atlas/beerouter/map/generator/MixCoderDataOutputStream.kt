package com.danemadsen.atlas.beerouter.map.generator

import java.io.DataOutputStream
import java.io.OutputStream

public class MixCoderDataOutputStream(os: OutputStream) : DataOutputStream(os) {
    private var lastValue: Int = 0
    private var lastLastValue: Int = 0
    private var repCount: Int = 0
    private var diffshift: Int = 0
    private var bm: Int = 1
    private var b: Int = 0

    public fun writeMixed(v: Int) {
        if (v != lastValue && repCount > 0) {
            var d = lastValue - lastLastValue
            lastLastValue = lastValue

            encodeBit(d < 0)
            if (d < 0) d = -d
            encodeVarBits(d - diffshift)
            encodeVarBits(repCount - 1)

            if (d < 100) diffs[d]++
            if (repCount < 100) counts[repCount]++

            diffshift = 1
            repCount = 0
        }
        lastValue = v
        repCount++
    }

    override fun flush() {
        val v = lastValue
        writeMixed(v + 1)
        lastValue = v
        repCount = 0
        if (bm > 1) {
            writeByte(b.toByte().toInt())
        }
        super.flush()
    }

    public fun encodeBit(value: Boolean) {
        if (bm == 0x100) {
            writeByte(b.toByte().toInt())
            bm = 1
            b = 0
        }
        if (value) {
            b = b or bm
        }
        bm = bm shl 1
    }

    public fun encodeVarBits(value: Int) {
        var current = value
        var range = 0
        while (current > range) {
            encodeBit(false)
            current -= range + 1
            range = 2 * range + 1
        }
        encodeBit(true)
        encodeBounded(range, current)
    }

    public fun encodeBounded(max: Int, value: Int) {
        var localMax = max
        var im = 1
        while (im <= localMax) {
            if (bm == 0x100) {
                writeByte(b.toByte().toInt())
                bm = 1
                b = 0
            }
            if ((value and im) != 0) {
                b = b or bm
                localMax -= im
            }
            bm = bm shl 1
            im = im shl 1
        }
    }

    public companion object {
        public val diffs: IntArray = IntArray(100)
        public val counts: IntArray = IntArray(100)

        public fun stats() {
            for (i in 1 until 100) {
                println("diff[$i] = ${diffs[i]}")
            }
            for (i in 1 until 100) {
                println("counts[$i] = ${counts[i]}")
            }
        }
    }
}
