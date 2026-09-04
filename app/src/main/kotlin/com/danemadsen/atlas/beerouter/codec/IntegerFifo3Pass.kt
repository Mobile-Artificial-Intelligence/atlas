package com.danemadsen.atlas.beerouter.codec

/**
 * Special integer fifo suitable for 3-pass encoding
 */
public class IntegerFifo3Pass(capacity: Int) {
    private var a: IntArray
    private var size = 0
    private var pos = 0

    private var pass = 0

    init {
        a = IntArray(maxOf(capacity, MIN_CAPACITY))
    }

    /**
     * Starts a new encoding pass and resets the reading pointer
     * from the stats collected in pass2 and writes that to the given context
     */
    public fun init() {
        pass++
        pos = 0
    }

    /**
     * writes to the fifo in pass2
     *
     * @throws OutOfMemoryError if the underlying array cannot be resized
     */
    public fun add(value: Int) {
        if (pass == 2) {
            if (size == a.size) {
                a = a.copyOf(size * 2)
            }
            a[size++] = value
        }
    }

    public val next: Int
        /**
         * reads from the fifo in pass3 (in pass1/2 returns just 1)
         */
        get() = if (pass == 3) get(pos++) else 1

    private fun get(idx: Int): Int {
        if (idx >= size) throw IndexOutOfBoundsException("list size=$size idx=$idx")
        return a[idx]
    }

    private companion object {
        private const val MIN_CAPACITY = 4
    }
}
