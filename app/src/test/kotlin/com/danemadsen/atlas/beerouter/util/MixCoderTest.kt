package com.danemadsen.atlas.beerouter.util

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class MixCoderTest {
    @Test
    fun mixEncodeDecodeTest() {
        val rnd = Random(1234)
        val encoder = MixCoderDataOutputStream()

        repeat(1500) { encoder.writeMixed(rnd.nextInt(3800)) }
        repeat(1500) { encoder.writeMixed(rnd.nextInt(35)) }
        repeat(1500) { encoder.writeMixed(0) }
        repeat(1500) { encoder.writeMixed(1000) }

        val bytes = encoder.toByteArray()
        val decoder = MixCoderDataInputStream(bytes)
        val rnd2 = Random(1234)

        repeat(1500) { assertEquals(rnd2.nextInt(3800), decoder.readMixed()) }
        repeat(1500) { assertEquals(rnd2.nextInt(35), decoder.readMixed()) }
        repeat(1500) { assertEquals(0, decoder.readMixed()) }
        repeat(1500) { assertEquals(1000, decoder.readMixed()) }
    }
}
