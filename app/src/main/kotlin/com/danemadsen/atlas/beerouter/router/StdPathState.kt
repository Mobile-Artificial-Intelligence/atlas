package com.danemadsen.atlas.beerouter.router

internal data class StdPathState(
    var ehbd: Int = 0,
    var ehbu: Int = 0,
    var totalTime: Double = 0.0,
    var totalEnergy: Double = 0.0,
    var elevationBuffer: Float = 0f,
    var uphillcostdiv: Int = 0,
    var downhillcostdiv: Int = 0,
)

internal fun StdPathState.copyFrom(other: StdPathState) {
    ehbd = other.ehbd
    ehbu = other.ehbu
    totalTime = other.totalTime
    totalEnergy = other.totalEnergy
    elevationBuffer = other.elevationBuffer
    uphillcostdiv = other.uphillcostdiv
    downhillcostdiv = other.downhillcostdiv
}

internal fun StdPathState.elevationCorrection(): Int =
    (if (downhillcostdiv > 0) ehbd / downhillcostdiv else 0) +
        (if (uphillcostdiv > 0) ehbu / uphillcostdiv else 0)

internal fun StdPathState.isDefinitelyWorseThan(cost: Int, otherCost: Int, other: StdPathState): Boolean {
    var correctedOtherCost = otherCost
    if (other.downhillcostdiv > 0) {
        val delta = other.ehbd / other.downhillcostdiv - (if (downhillcostdiv > 0) ehbd / downhillcostdiv else 0)
        if (delta > 0) correctedOtherCost += delta
    }
    if (other.uphillcostdiv > 0) {
        val delta = other.ehbu / other.uphillcostdiv - (if (uphillcostdiv > 0) ehbu / uphillcostdiv else 0)
        if (delta > 0) correctedOtherCost += delta
    }
    return cost > correctedOtherCost
}
