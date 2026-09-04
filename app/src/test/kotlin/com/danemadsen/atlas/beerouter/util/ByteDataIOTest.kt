package com.danemadsen.atlas.beerouter.util

import kotlin.test.Test
import kotlin.test.assertEquals

class ByteDataIOTest {
    @Test
    fun varLengthEncodeDecodeTest() {
        val ab = ByteArray(4000)
        val writer = ByteDataWriter(ab)
        for (i in 0..<1000) {
            writer.writeVarLengthUnsigned(i)
        }
        val reader = ByteDataReader(ab)

        for (i in 0..<1000) {
            val value = reader.readVarLengthUnsigned()
            assertEquals(i, value, "value mismatch")
        }
    }
}
