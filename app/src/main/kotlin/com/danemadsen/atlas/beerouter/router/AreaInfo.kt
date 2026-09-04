package com.danemadsen.atlas.beerouter.router

import com.danemadsen.atlas.beerouter.expressions.BExpressionContext
import kotlin.math.abs

public class AreaInfo(public val direction: Int) {
    public enum class ResultType {
        ELEV50,
        GREEN,
        RIVER
    }

    public var numForest: Int = -1
        internal set
    public var numRiver: Int = -1
        internal set

    public var polygon: OsmNogoPolygon? = null
        internal set

    public var ways: Int = 0
        internal set
    public var greenWays: Int = 0
        internal set
    public var riverWays: Int = 0
        internal set
    public var elevStart: Double = 0.0
        internal set
    public var elev50: Int = 0
        internal set

    private fun percentage(part: Int, total: Int): Int =
        if (total == 0) 0 else (part * 100.0 / total).toInt()

    public fun checkAreaInfo(expctxWay: BExpressionContext, elev: Double, ab: ByteArray) {
        ways++

        if (abs(elevStart - elev) < 50) {
            elev50++
        }

        val lookupData = requireNotNull(expctxWay.createNewLookupData())
        expctxWay.decode(lookupData, false, ab)

        if (numForest >= 0 && lookupData[numForest] > 1) {
            greenWays++
        }

        if (numRiver >= 0 && lookupData[numRiver] > 1) {
            riverWays++
        }
    }

    public val elev50Weight: Int
        get() = percentage(elev50, ways)

    public val green: Int
        get() = percentage(greenWays, ways)

    public val river: Int
        get() = percentage(riverWays, ways)

    override fun toString(): String = buildString {
        append("Area $direction $elevStart m ways $ways")
        if (ways > 0) {
            append("\nArea ways <50m  $elev50 $elev50Weight%")
            append("\nArea ways green $greenWays $green%")
            append("\nArea ways river $riverWays $river%")
        }
    }
}
