package com.danemadsen.atlas.beerouter.codec

import com.danemadsen.atlas.beerouter.util.BitCoderContext

public class StatCoderContext(ab: ByteArray) : BitCoderContext(ab) {
    private var lastbitpos: Long = 0


    /**
     * assign the de-/encoded bits since the last call assignBits to the given
     * name. Used for encoding statistics
     *
     * @see .getBitReport
     */
    public fun assignBits(name: String?) {
        val bitpos = writingBitPosition.toLong()
        var stats: LongArray? = statsPerName[name]
        if (stats == null) {
            stats = LongArray(2)
            statsPerName[name] = stats
        }
        stats[0] += bitpos - lastbitpos
        stats[1] += 1
        lastbitpos = bitpos
    }

    /**
     * encode an unsigned integer with some of of least significant bits
     * considered noisy
     *
     * @see .decodeNoisyNumber
     * @throws IllegalArgumentException if value is negative
     */
    public fun encodeNoisyNumber(value: Int, noisybits: Int) {
        var value = value
        require(value >= 0) { "encodeVarBits expects positive value" }
        if (noisybits > 0) {
            val mask = -0x1 ushr (32 - noisybits)
            encodeBounded(mask, value and mask)
            value = value shr noisybits
        }
        encodeVarBits(value)
    }

    /**
     * decode an unsigned integer with some of of least significant bits
     * considered noisy
     *
     * @see .encodeNoisyNumber
     * @throws IllegalStateException if decoding fails due to buffer underflow
     */
    public fun decodeNoisyNumber(noisybits: Int): Int {
        val value = decodeBits(noisybits)
        return value or (decodeVarBits() shl noisybits)
    }

    /**
     * encode a signed integer with some of of least significant bits considered
     * noisy
     *
     * @see .decodeNoisyDiff
     * @throws IllegalStateException if encoding fails due to buffer overflow
     */
    public fun encodeNoisyDiff(value: Int, noisybits: Int) {
        var value = value
        if (noisybits > 0) {
            value += 1 shl (noisybits - 1)
            val mask = -0x1 ushr (32 - noisybits)
            encodeBounded(mask, value and mask)
            value = value shr noisybits
        }
        encodeVarBits(if (value < 0) -value else value)
        if (value != 0) {
            encodeBit(value < 0)
        }
    }

    /**
     * decode a signed integer with some of of least significant bits considered
     * noisy
     *
     * @see .encodeNoisyDiff
     * @throws IllegalStateException if decoding fails due to buffer underflow
     */
    public fun decodeNoisyDiff(noisybits: Int): Int {
        var value = 0
        if (noisybits > 0) {
            value = decodeBits(noisybits) - (1 shl (noisybits - 1))
        }
        var val2 = decodeVarBits() shl noisybits
        if (val2 != 0) {
            if (decodeBit()) {
                val2 = -val2
            }
        }
        return value + val2
    }

    /**
     * encode a signed integer with the typical range and median taken from the
     * predicted value
     *
     * @see .decodePredictedValue
     * @throws IllegalArgumentException if internal encoding produces invalid values
     */
    public fun encodePredictedValue(value: Int, predictor: Int) {
        var p = if (predictor < 0) -predictor else predictor
        var noisybits = 0

        while (p > 2) {
            noisybits++
            p = p shr 1
        }
        encodeNoisyDiff(value - predictor, noisybits)
    }

    /**
     * decode a signed integer with the typical range and median taken from the
     * predicted value
     *
     * @see .encodePredictedValue
     * @throws IllegalStateException if decoding fails due to buffer underflow
     */
    public fun decodePredictedValue(predictor: Int): Int {
        var p = if (predictor < 0) -predictor else predictor
        var noisybits = 0
        while (p > 1023) {
            noisybits++
            p = p shr 1
        }
        return predictor + decodeNoisyDiff(noisybits + noisyBits[p])
    }

    /**
     * encode an integer-array making use of the fact that it is sorted.
     *
     * @throws IllegalStateException if encoding fails due to buffer overflow
     */
    public fun encodeSortedArray(values: IntArray, offset: Int, subsize: Int, nextbit: Int, mask: Int) {
        var nextbit = nextbit
        var mask = mask
        if (subsize == 1) { // last-choice shortcut
            while (nextbit != 0) {
                encodeBit((values[offset] and nextbit) != 0)
                nextbit = nextbit shr 1
            }
        }
        if (nextbit == 0) {
            return
        }

        val data = mask and values[offset]
        mask = mask or nextbit

        // count 0-bit-fraction
        var i = offset
        val end = subsize + offset
        while (i < end) {
            if ((values[i] and mask) != data) {
                break
            }
            i++
        }
        val size1 = i - offset
        val size2 = subsize - size1

        encodeBounded(subsize, size1)
        if (size1 > 0) {
            encodeSortedArray(values, offset, size1, nextbit shr 1, mask)
        }
        if (size2 > 0) {
            encodeSortedArray(values, i, size2, nextbit shr 1, mask)
        }
    }

    /**
     * Decode an integer-array making use of the fact that it is sorted.
     *
     * @throws IllegalStateException if decoding fails due to buffer underflow
     */
    public fun decodeSortedArray(
        values: IntArray,
        offset: Int,
        subsize: Int,
        nextbitpos: Int,
        value: Int
    ) {
        var offset = offset
        var subsize = subsize
        var value = value
        if (subsize == 1) { // last-choice shortcut
            if (nextbitpos >= 0) {
                value = value or decodeBitsReverse(nextbitpos + 1)
            }
            values[offset] = value
            return
        }
        if (nextbitpos < 0) {
            while (subsize-- > 0) {
                values[offset++] = value
            }
            return
        }

        val size1 = decodeBounded(subsize)
        val size2 = subsize - size1

        if (size1 > 0) {
            decodeSortedArray(values, offset, size1, nextbitpos - 1, value)
        }
        if (size2 > 0) {
            decodeSortedArray(
                values,
                offset + size1,
                size2,
                nextbitpos - 1,
                value or (1 shl nextbitpos)
            )
        }
    }

    public companion object {
        private val statsPerName: MutableMap<String?, LongArray> = mutableMapOf()
        private val noisyBits = IntArray(1024)

        init {
            // noisybits lookup
            for (i in 0..1023) {
                var p = i
                var noisybits = 0
                while (p > 2) {
                    noisybits++
                    p = p shr 1
                }
                noisyBits[i] = noisybits
            }
        }


        public val bitReport: String
            get() {
                if (statsPerName.isEmpty()) return "<empty bit report>"
                return buildString {
                    for ((name, stats) in statsPerName) {
                        append("$name count=${stats[1]} bits=${stats[0]}\n")
                    }
                }.also { statsPerName.clear() }
            }
    }
}
