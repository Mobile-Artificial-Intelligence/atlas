package com.danemadsen.atlas.beerouter.util

/**
 * a median filter with additional edge reduction
 */
public class ReducedMedianFilter(size: Int) {
    private var nsamples = 0
    private val weights: DoubleArray = DoubleArray(size)
    private val values: IntArray = IntArray(size)

    public fun reset() {
        nsamples = 0
    }

    public fun addSample(weight: Double, value: Int) {
        if (weight <= 0.0) {
            return
        }

        for (i in 0..<nsamples) {
            if (values[i] == value) {
                weights[i] += weight
                return
            }
        }

        weights[nsamples] = weight
        values[nsamples] = value
        nsamples++
    }

    /**
     * @throws IllegalArgumentException if the total weight is insufficient to remove the requested fraction
     */
    public fun edgeReducedMedian(fraction: Double): Double {
        removeEdgeWeight((1.0 - fraction) / 2.0, true)
        removeEdgeWeight((1.0 - fraction) / 2.0, false)

        var totalWeight = 0.0
        var totalValue = 0.0
        for (i in 0..<nsamples) {
            val w = weights[i]
            totalWeight += w
            totalValue += w * values[i]
        }
        return totalValue / totalWeight
    }


    private fun removeEdgeWeight(initialExcessWeight: Double, high: Boolean) {
        var remainingExcessWeight = initialExcessWeight
        while (remainingExcessWeight > 0.0) {
            // first pass to find minmax value
            var totalWeight = 0.0
            var minmax = 0
            for (i in 0..<nsamples) {
                val w = weights[i]
                if (w > 0.0) {
                    val v = values[i]
                    if (totalWeight == 0.0 || (if (high) v > minmax else v < minmax)) {
                        minmax = v
                    }
                    totalWeight += w
                }
            }

            require(totalWeight >= remainingExcessWeight) { "ups, not enough weight to remove" }

            // second pass to remove
            for (i in 0..<nsamples) {
                if (values[i] == minmax && weights[i] > 0.0) {
                    if (remainingExcessWeight > weights[i]) {
                        remainingExcessWeight -= weights[i]
                        weights[i] = 0.0
                    } else {
                        weights[i] -= remainingExcessWeight
                        remainingExcessWeight = 0.0
                    }
                }
            }
        }
    }
}
