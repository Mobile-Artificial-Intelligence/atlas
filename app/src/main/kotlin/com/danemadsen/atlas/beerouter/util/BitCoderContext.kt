package com.danemadsen.atlas.beerouter.util


public open class BitCoderContext(private var ab: ByteArray) {
    private var idxMax: Int = ab.size - 1
    private var idx = -1
    private var bits = 0 // bits left in buffer
    private var b = 0 // buffer word

    public fun reset(ab: ByteArray) {
        this.ab = ab
        idxMax = ab.size - 1
        reset()
    }

    public fun reset() {
        idx = -1
        bits = 0
        b = 0
    }

    /**
     * encode a distance with a variable bit length
     * (poor mans huffman tree)
     * `1 -> 0`
     * `01 -> 1` + following 1-bit word ( 1..2 )
     * `001 -> 3` + following 2-bit word ( 3..6 )
     * `0001 -> 7` + following 3-bit word ( 7..14 ) etc.
     *
     * @throws IndexOutOfBoundsException if the buffer is too small
     * @see .decodeVarBits
     */
    public fun encodeVarBits2(value: Int) {
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

    /**
     * @throws IndexOutOfBoundsException if the buffer is too small
     */
    public fun encodeVarBits(value: Int) {
        if ((value and 0xfff) == value) {
            flushBuffer()
            b = b or (vcValues[value] shl bits)
            bits += vcLength[value]
        } else {
            encodeVarBits2(value) // slow fallback for large values
        }
    }

    /**
     * @throws IndexOutOfBoundsException if reading beyond the buffer
     * @see .encodeVarBits
     */
    public fun decodeVarBits2(): Int {
        var range = 0
        while (!decodeBit()) {
            range = 2 * range + 1
        }
        return range + decodeBounded(range)
    }

    /**
     * @throws IndexOutOfBoundsException if reading beyond the buffer
     */
    public fun decodeVarBits(): Int {
        fillBuffer()
        val b12 = b and 0xfff
        val len: Int = vlLength[b12]
        if (len <= 12) {
            b = b ushr len
            bits -= len
            return vlValues[b12] // full value lookup
        }
        if (len <= 23) { // // only length lookup
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

    /**
     * @throws IndexOutOfBoundsException if the buffer is too small
     */
    public fun encodeBit(value: Boolean) {
        if (bits > 31) {
            ab[++idx] = (b and 0xff).toByte()
            b = b ushr 8
            bits -= 8
        }
        if (value) {
            b = b or (1 shl bits)
        }
        bits++
    }

    /**
     * @throws IndexOutOfBoundsException if reading beyond the buffer
     */
    public fun decodeBit(): Boolean {
        if (bits == 0) {
            bits = 8
            b = ab[++idx].toInt() and 0xff
        }
        val value = ((b and 1) != 0)
        b = b ushr 1
        bits--
        return value
    }

    /**
     * encode an integer in the range 0..max (inclusive).
     * For max = 2^n-1, this just encodes n bits, but in general
     * this is variable length encoding, with the shorter codes
     * for the central value range
     *
     * @throws IndexOutOfBoundsException if the buffer is too small
     */
    public fun encodeBounded(max: Int, value: Int) {
        var max = max
        var im = 1 // integer mask
        while (im <= max) {
            if ((value and im) != 0) {
                encodeBit(true)
                max -= im
            } else {
                encodeBit(false)
            }
            im = im shl 1
        }
    }

    /**
     * decode an integer in the range 0..max (inclusive).
     *
     * @throws IndexOutOfBoundsException if reading beyond the buffer
     * @see .encodeBounded
     */
    public fun decodeBounded(max: Int): Int {
        var value = 0
        var im = 1 // integer mask
        while ((value or im) <= max) {
            if (bits == 0) {
                bits = 8
                b = ab[++idx].toInt() and 0xff
            }
            if ((b and 1) != 0) value = value or im
            b = b ushr 1
            bits--
            im = im shl 1
        }
        return value
    }

    /**
     * @throws IndexOutOfBoundsException if reading beyond the buffer
     */
    public fun decodeBits(count: Int): Int {
        fillBuffer()
        val mask = -0x1 ushr (32 - count)
        val value = b and mask
        b = b ushr count
        bits -= count
        return value
    }

    /**
     * @throws IndexOutOfBoundsException if reading beyond the buffer
     */
    public fun decodeBitsReverse(count: Int): Int {
        var count = count
        fillBuffer()
        var value = 0
        while (count > 8) {
            value = (value shl 8) or reverseByte[b and 0xff]
            b = b shr 8
            count -= 8
            bits -= 8
            fillBuffer()
        }
        value = (value shl count) or (reverseByte[b and 0xff] shr (8 - count))
        bits -= count
        b = b shr count
        return value
    }

    private fun fillBuffer() {
        while (bits < 24) {
            if (idx++ < idxMax) {
                b = b or ((ab[idx].toInt() and 0xff) shl bits)
            }
            bits += 8
        }
    }

    private fun flushBuffer() {
        while (bits > 7) {
            ab[++idx] = (b and 0xff).toByte()
            b = b ushr 8
            bits -= 8
        }
    }

    /**
     * flushes and closes the (write-mode) context
     *
     * @throws IndexOutOfBoundsException if the buffer is too small
     * @return the encoded length in bytes
     */
    public fun closeAndGetEncodedLength(): Int {
        flushBuffer()
        if (bits > 0) {
            ab[++idx] = (b and 0xff).toByte()
        }
        return idx + 1
    }

    public val writingBitPosition: Int
        /**
         * @return the encoded length in bits
         */
        get() = (idx shl 3) + 8 + bits

    public var readingBitPosition: Int
        get() = (idx shl 3) + 8 - bits
        /**
         * @throws IndexOutOfBoundsException if the bit position is out of the buffer bounds
         */
        set(pos) {
            idx = pos ushr 3
            bits = (idx shl 3) + 8 - pos
            b = ab[idx].toInt() and 0xff
            b = b ushr (8 - bits)
        }

    public companion object {
        public val vlValues: IntArray = IntArray(4096)
        public val vlLength: IntArray = IntArray(4096)

        private val vcValues = IntArray(4096)
        private val vcLength = IntArray(4096)

        private val reverseByte = IntArray(256)

        private val bm2bits = IntArray(256)

        init {
            // fill varbits lookup table

            val bc = BitCoderContext(ByteArray(4))
            for (i in 0..4095) {
                bc.reset()
                bc.bits = 14
                bc.b = 0x1000 + i

                val b0 = bc.readingBitPosition
                vlValues[i] = bc.decodeVarBits2()
                vlLength[i] = bc.readingBitPosition - b0
            }
            for (i in 0..4095) {
                bc.reset()
                val b0 = bc.writingBitPosition
                bc.encodeVarBits2(i)
                vcValues[i] = bc.b
                vcLength[i] = bc.writingBitPosition - b0
            }
            for (i in 0..1023) {
                bc.reset()
                bc.bits = 14
                bc.b = 0x1000 + i

                val b0 = bc.readingBitPosition
                vlValues[i] = bc.decodeVarBits2()
                vlLength[i] = bc.readingBitPosition - b0
            }
            for (b in 0..255) {
                var r = 0
                for (i in 0..7) {
                    if ((b and (1 shl i)) != 0) r = r or (1 shl (7 - i))
                }
                reverseByte[b] = r
            }
            for (b in 0..7) {
                bm2bits[1 shl b] = b
            }
        }
    }
}
