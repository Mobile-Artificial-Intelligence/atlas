package com.danemadsen.atlas.beerouter.codec

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class StatCoderContextTest {
    @Test
    fun noisyVarBitsEncodeDecodeTest() {
        val ab = ByteArray(40000)
        var ctx = StatCoderContext(ab)
        for (noisybits in 1..<12) {
            for (i in 0..<1000) {
                ctx.encodeNoisyNumber(i, noisybits)
            }
        }
        ctx.closeAndGetEncodedLength()
        ctx = StatCoderContext(ab)

        for (noisybits in 1..<12) {
            for (i in 0..<1000) {
                val value = ctx.decodeNoisyNumber(noisybits)
                assertEquals(i, value, "value mismatch: noisybits=$noisybits i=$i")
            }
        }
    }

    @Test
    fun noisySignedVarBitsEncodeDecodeTest() {
        val ab = ByteArray(80000)
        var ctx = StatCoderContext(ab)
        for (noisybits in 0..<12) {
            for (i in -1000..<1000) {
                ctx.encodeNoisyDiff(i, noisybits)
            }
        }
        ctx.closeAndGetEncodedLength()
        ctx = StatCoderContext(ab)

        for (noisybits in 0..<12) {
            for (i in -1000..<1000) {
                val value = ctx.decodeNoisyDiff(noisybits)
                assertEquals(i, value, "value mismatch: noisybits=$noisybits i=$i")
            }
        }
    }

    @Test
    fun predictedValueEncodeDecodeTest() {
        val ab = ByteArray(80000)
        var ctx = StatCoderContext(ab)
        for (value in -100..<100 step 5) {
            for (predictor in -200..<200 step 7) {
                ctx.encodePredictedValue(value, predictor)
            }
        }
        ctx.closeAndGetEncodedLength()
        ctx = StatCoderContext(ab)

        for (value in -100..<100 step 5) {
            for (predictor in -200..<200 step 7) {
                val decodedValue = ctx.decodePredictedValue(predictor)
                assertEquals(value, decodedValue, "value mismatch: value=$value predictor=$predictor")
            }
        }
    }

    @Test
    fun sortedArrayEncodeDecodeTest() {
        val size = 1_000_000
        val values = IntArray(size) { Random.nextInt() and 0x0fffffff }
        values[5] = 175_384
        values[8] = 175_384
        values[15] = 275_384
        values[18] = 275_385
        values.sort()

        val ab = ByteArray(3_000_000)
        var ctx = StatCoderContext(ab)
        ctx.encodeSortedArray(values, 0, size, 0x08000000, 0)

        ctx.closeAndGetEncodedLength()
        ctx = StatCoderContext(ab)

        val decodedValues = IntArray(size)
        ctx.decodeSortedArray(decodedValues, 0, size, 27, 0)

        for (i in 0..<size) {
            assertEquals(values[i], decodedValues[i], "mismatch at i=$i")
        }
    }
}
