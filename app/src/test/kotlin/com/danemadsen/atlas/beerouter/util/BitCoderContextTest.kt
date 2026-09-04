package com.danemadsen.atlas.beerouter.util

import kotlin.test.Test
import kotlin.test.assertEquals

class BitCoderContextTest {
    @Test
    fun varBitsEncodeDecodeTest() {
        val ab = ByteArray(581969)
        var ctx = BitCoderContext(ab)
        for (i in 0..<31) {
            ctx.encodeVarBits((1 shl i) + 3)
        }
        for (i in 0..<100000 step 13) {
            ctx.encodeVarBits(i)
        }
        ctx.closeAndGetEncodedLength()
        ctx = BitCoderContext(ab)

        for (i in 0..<31) {
            val value = ctx.decodeVarBits()
            val expected = (1 shl i) + 3
            assertEquals(expected, value, "value mismatch value=$value expected=$expected")
        }
        for (i in 0..<100000 step 13) {
            val value = ctx.decodeVarBits()
            assertEquals(i, value, "value mismatch i=$i v=$value")
        }
    }

    @Test
    fun boundedEncodeDecodeTest() {
        val ab = ByteArray(581969)
        var ctx = BitCoderContext(ab)
        for (max in 1..<1000) {
            for (value in 0..max) {
                ctx.encodeBounded(max, value)
            }
        }
        ctx.closeAndGetEncodedLength()

        ctx = BitCoderContext(ab)

        for (max in 1..<1000) {
            for (value in 0..max) {
                val decoded = ctx.decodeBounded(max)
                assertEquals(value, decoded, "mismatch at max=$max")
            }
        }
    }
}
