package com.danemadsen.atlas.beerouter.util

/**
 * Memory efficient and lightning fast heap to get the lowest-key value of a set of key-object pairs
 *
 * @author ab
 */
public class SortedHeap<V> {
    public var size: Int = 0
        private set
    public var peakSize: Int = 0
        private set

    private var first: SortedBin = SortedBin(4, this)
    private var second: SortedBin = SortedBin(4, this)
    private var firstNonEmpty: SortedBin? = null

    /**
     * @return the lowest key value, or null if none
     */
    public fun popLowestKeyValue(): V? {
        val bin = firstNonEmpty ?: return null

        size--
        val minBin = bin.minBin
        @Suppress("UNCHECKED_CAST")
        return minBin.dropLowest() as V?
    }

    /**
     * Add a key value pair to the heap
     *
     * @param key   the key to insert
     * @param value the value to insert object
     */
    public fun add(key: Int, value: V?) {
        size++

        // Both bins full?
        if (first.lp == 0 && second.lp == 0) {
            sortUp()
        }

        when {
            first.lp > 0 -> {
                first.add4(key, value)
                if (firstNonEmpty != first) {
                    first.nextNonEmpty = firstNonEmpty
                    firstNonEmpty = first
                }
            }

            else -> { // second bin not full
                second.add4(key, value)
                if (first.nextNonEmpty != second) {
                    second.nextNonEmpty = first.nextNonEmpty
                    first.nextNonEmpty = second
                }
            }
        }
    }

    public fun clear() {
        size = 0
        peakSize = 0
        first = SortedBin(4, this)
        second = SortedBin(4, this)
        firstNonEmpty = null
    }

    private fun sortUp() {
        peakSize = maxOf(peakSize, size)

        // Determine the first array big enough to take them all
        var cnt = 8 // value count of first 2 bins is always 8
        var tbin = second // target bin
        var lastNonEmpty = second

        do {
            tbin = tbin.next()
            val nentries = tbin.binsize - tbin.lp
            if (nentries > 0) {
                cnt += nentries
                lastNonEmpty = tbin
            }
        } while (cnt > tbin.binsize)

        val alT = tbin.al
        val vlaT = tbin.vla
        var tp = tbin.binsize - cnt // target pointer

        // Unlink any higher, non-empty arrays
        val otherNonEmpty = lastNonEmpty.nextNonEmpty
        lastNonEmpty.nextNonEmpty = null

        // Now merge the content of these non-empty bins into the target bin
        while (firstNonEmpty != null) {
            // Copy current minimum to target array
            val minBin = requireNotNull(firstNonEmpty).minBin
            alT[tp] = minBin.lv
            vlaT[tp++] = minBin.dropLowest()
        }

        tp = tbin.binsize - cnt
        tbin.lp = tp // new target low pointer
        tbin.lv = tbin.al[tp]
        tbin.nextNonEmpty = otherNonEmpty
        firstNonEmpty = tbin
    }

    override fun toString(): String = "SortedHeap(size=$size, peakSize=$peakSize)"

    private class SortedBin(
        val binsize: Int,
        val parent: SortedHeap<*>
    ) {
        var next: SortedBin? = null
        var nextNonEmpty: SortedBin? = null
        val al: IntArray = IntArray(binsize) // key array
        val vla: Array<Any?> = arrayOfNulls(binsize) // value array
        var lv: Int = 0 // low value
        var lp: Int = binsize // low pointer

        fun next(): SortedBin = next ?: SortedBin(binsize shl 1, parent).also { next = it }

        fun dropLowest(): Any? {
            val lpOld = lp

            if (++lp == binsize) {
                unlink()
            } else {
                lv = al[lp]
            }

            return vla[lpOld].also { vla[lpOld] = null }
        }

        fun unlink() {
            var neBin = parent.firstNonEmpty

            if (neBin == this) {
                parent.firstNonEmpty = nextNonEmpty
                return
            }

            while (neBin != null) {
                val next = neBin.nextNonEmpty
                if (next == this) {
                    neBin.nextNonEmpty = nextNonEmpty
                    return
                }
                neBin = next
            }
        }

        fun add(key: Int, value: Any?) {
            var p = lp

            while (true) {
                if (p == binsize || key < al[p]) {
                    al[p - 1] = key
                    vla[p - 1] = value
                    lv = al[--lp]
                    return
                }
                al[p - 1] = al[p]
                vla[p - 1] = vla[p]
                p++
            }
        }

        // Unrolled version of above for binsize = 4
        fun add4(key: Int, value: Any?) {
            var p = lp--

            if (p == 4 || key < al[p]) {
                al[p - 1] = key
                lv = al[p - 1]
                vla[p - 1] = value
                return
            }
            al[p - 1] = al[p]
            lv = al[p - 1]
            vla[p - 1] = vla[p]
            p++

            if (p == 4 || key < al[p]) {
                al[p - 1] = key
                vla[p - 1] = value
                return
            }
            al[p - 1] = al[p]
            vla[p - 1] = vla[p]
            p++

            if (p == 4 || key < al[p]) {
                al[p - 1] = key
                vla[p - 1] = value
                return
            }
            al[p - 1] = al[p]
            vla[p - 1] = vla[p]

            al[p] = key
            vla[p] = value
        }

        val minBin: SortedBin
            get() {
                var minBin = this
                var current = this.nextNonEmpty

                while (current != null) {
                    if (current.lv < minBin.lv) {
                        minBin = current
                    }
                    current = current.nextNonEmpty
                }

                return minBin
            }
    }
}
