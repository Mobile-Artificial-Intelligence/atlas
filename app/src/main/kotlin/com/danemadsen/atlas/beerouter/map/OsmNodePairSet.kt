/**
 * Set holding pairs of osm nodes
 *
 * @author ab
 */
package com.danemadsen.atlas.beerouter.map

import androidx.collection.MutableLongObjectMap


public class OsmNodePairSet(maxTempNodeCount: Int) {
    private val n1a: LongArray
    private val n2a: LongArray
    private var tempNodes = 0
    public var maxTmpNodes: Int = 0
        private set
    private var npairs = 0
    public var freezeCount: Int = 0
        private set

    private val pairMap: MutableLongObjectMap<MutableSet<Long>> = MutableLongObjectMap()

    init {
        this.maxTmpNodes = maxTempNodeCount
        n1a = LongArray(this.maxTmpNodes)
        n2a = LongArray(this.maxTmpNodes)
    }

    public fun addTempPair(n1: Long, n2: Long) {
        if (tempNodes < this.maxTmpNodes) {
            n1a[tempNodes] = n1
            n2a[tempNodes] = n2
            tempNodes++
        }
    }

    public fun freezeTempPairs() {
        this.freezeCount++
        for (i in 0..<tempNodes) {
            addPair(n1a[i], n2a[i])
        }
        tempNodes = 0
    }

    public fun clearTempPairs() {
        tempNodes = 0
    }

    private fun addPair(n1: Long, n2: Long) {
        npairs++

        val targets = pairMap[n1] ?: mutableSetOf<Long>().also { pairMap[n1] = it }
        targets.add(n2)
    }

    public val size: Int
        get() = npairs

    public fun hasPair(n1: Long, n2: Long): Boolean {
        return containsPair(n1, n2) || containsPair(n2, n1)
    }

    private fun containsPair(n1: Long, n2: Long): Boolean = pairMap[n1]?.contains(n2) == true
}
