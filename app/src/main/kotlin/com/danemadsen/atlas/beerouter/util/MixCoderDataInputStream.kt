/**
 * Decoder for fast-compact encoded number sequences
 *
 * @author ab
 */
package com.danemadsen.atlas.beerouter.util

public class MixCoderDataInputStream(private val buf: ByteArray) {
    private var bufPos = 0
    private var lastValue = 0
    private var repCount = 0
    private var diffshift = 0

    private var bits = 0 // bits left in buffer
    private var b = 0 // buffer word

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
        val value = ((b and 1) != 0)
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
        var im = 1 // integer mask
        while ((value or im) <= max) {
            if (decodeBit()) value = value or im
            im = im shl 1
        }
        return value
    }

    public fun decodeVarBits(): Int {
        fillBuffer()
        val b12 = b and 0xfff
        val len: Int = vlLength[b12]
        if (len <= 12) {
            b = b ushr len
            bits -= len
            return vlValues[b12] // full value lookup
        }
        if (len <= 23) { // only length lookup
            val len2 = len shr 1
            b = b ushr (len2 + 1)
            var mask = -0x1 ushr (32 - len2)
            mask += b and mask
            b = b ushr len2
            bits -= len
            return mask
        }
        if ((b and 0xffffff) != 0) {
            // here we just know len in [25..47]
            // ( fillBuffer guarantees only 24 bits! )
            b = b ushr 12
            val len3: Int = 1 + (vlLength[b and 0xfff] shr 1)
            b = b ushr len3
            val len2 = 11 + len3
            bits -= len2 + 1
            fillBuffer()
            var mask = -0x1 ushr (32 - len2)
            mask += b and mask
            b = b ushr len2
            bits -= len2
            return mask
        }
        return decodeVarBits2() // no chance, use the slow one
    }

    private fun fillBuffer() {
        while (bits < 24) {
            val nextByte = if (bufPos < buf.size) buf[bufPos++].toInt() and 0xff else -1
            if (nextByte != -1) b = b or ((nextByte and 0xff) shl bits)
            bits += 8
        }
    }

    private companion object {
        private val vlValues = BitCoderContext.vlValues
        private val vlLength = BitCoderContext.vlLength
    }
}
