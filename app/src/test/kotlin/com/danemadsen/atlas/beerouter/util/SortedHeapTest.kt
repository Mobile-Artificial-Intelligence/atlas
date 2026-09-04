package com.danemadsen.atlas.beerouter.util

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SortedHeapTest {
    @Test
    fun sortedHeapTest1() {
        val sortedHeap = SortedHeap<String>()
        for (i in 0..<100000) {
            var value = Random.nextInt(1_000_000)
            sortedHeap.add(value, value.toString())
            value = Random.nextInt(1_000_000)
            sortedHeap.add(value, value.toString())
            sortedHeap.popLowestKeyValue()
        }

        var count = 0
        var lastValue = 0
        while (true) {
            val valueString = sortedHeap.popLowestKeyValue() ?: break
            count++
            val value = valueString.toInt()
            assertTrue(value >= lastValue, "sorting test")
            lastValue = value
        }

        assertEquals(100000, count, "total count test")
    }

    @Test
    fun sortedHeapTest2() {
        val sortedHeap = SortedHeap<String>()
        for (i in 0..<100000) {
            sortedHeap.add(i, i.toString())
        }

        var count = 0
        var expected = 0
        while (true) {
            val valueString = sortedHeap.popLowestKeyValue() ?: break
            count++
            val value = valueString.toInt()
            assertEquals(expected, value, "sequence test")
            expected++
        }

        assertEquals(100000, count, "total count test")
    }
}
