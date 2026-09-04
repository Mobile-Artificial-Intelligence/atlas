package com.danemadsen.atlas.beerouter.map.generator

import com.danemadsen.atlas.beerouter.util.BitCoderContext
import java.io.DataInputStream
import java.io.InputStream

public class MixCoderDataInputStream(input: InputStream) : DataInputStream(input) {
    private var lastValue: Int = 0
    private var repCount: Int = 0
    private var diffshift: Int = 0
    private var bits: Int = 0
    private var b: Int = 0

    public fun readMixed(): Int {
        if (repCount == 0) {
            val negative = decodeBit()
            val d = decodeVarBits() + diffshift
            repCount = decodeVarBits() + 1
            lastValue += if (negative) -d else d
            diffshift = 1
        }
        repCount--
        return lastValue
    }

    public fun decodeBit(): Boolean {
        fillBuffer()
        val value = (b and 1) != 0
        b = b ushr 1
        bits--
        return value
    }

    public fun decodeVarBits2(): Int {
        var range = 0
        while (!decodeBit()) {
            range = 2 * range + 1
        }
        return range + decodeBounded(range)
    }

    public fun decodeBounded(max: Int): Int {
        var value = 0
        var im = 1
        while ((value or im) <= max) {
            if (decodeBit()) {
                value = value or im
            }
            im = im shl 1
        }
        return value
    }

    public fun decodeVarBits(): Int {
        fillBuffer()
        val b12 = b and 0xfff
        val len = BitCoderContext.vlLength[b12]
        if (len <= 12) {
            b = b ushr len
            bits -= len
            return BitCoderContext.vlValues[b12]
        }
        if (len <= 23) {
            val len2 = len shr 1
            b = b ushr (len2 + 1)
            var mask = -1 ushr (32 - len2)
            mask += b and mask
            b = b ushr len2
            bits -= len
            return mask
        }
        if ((b and 0xffffff) != 0) {
            b = b ushr 12
            val len3 = 1 + (BitCoderContext.vlLength[b and 0xfff] shr 1)
            b = b ushr len3
            val len2 = 11 + len3
            bits -= len2 + 1
            fillBuffer()
            var mask = -1 ushr (32 - len2)
            mask += b and mask
            b = b ushr len2
            bits -= len2
            return mask
        }
        return decodeVarBits2()
    }

    private fun fillBuffer() {
        while (bits < 24) {
            val nextByte = read()
            if (nextByte != -1) {
                b = b or ((nextByte and 0xff) shl bits)
            }
            bits += 8
        }
    }
}
