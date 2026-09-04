package com.danemadsen.atlas.beerouter.codec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LinkedListContainerTest {
    @Test
    fun linkedListTest1() {
        val nlists = 553
        val llc = LinkedListContainer(nlists, null)

        for (ln in 0..<nlists) {
            for (i in 0..<10) {
                llc.addDataElement(ln, ln * i)
            }
        }

        for (i in 0..<10) {
            for (ln in 0..<nlists) {
                llc.addDataElement(ln, ln * i)
            }
        }

        for (ln in 0..<nlists) {
            val count = llc.initList(ln)
            assertEquals(20, count, "list size test")

            for (i in 19 downTo 0) {
                val data = llc.dataElement
                assertEquals(ln * (i % 10), data, "data value test")
            }
        }

        assertFailsWith<IllegalArgumentException>("no more elements expected") {
            llc.dataElement
        }
    }
}
