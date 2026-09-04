/**
 * Container for a turn restriction
 *
 * @author ab
 */
package com.danemadsen.atlas.beerouter.map

import com.danemadsen.atlas.beerouter.geo.Position

public data class TurnRestriction(
    public val isPositive: Boolean = false,
    public val exceptions: Short = 0,
    public val fromId: Long = 0,
    public val toId: Long = 0,
    public var next: TurnRestriction? = null
) {
    public enum class RestrictionException(public val bit: Int) {
        BICYCLES(1),
        MOTORCARS(2)
    }

    private fun hasException(exception: RestrictionException): Boolean =
        (exceptions.toInt() and exception.bit) != 0

    public fun exceptBikes(): Boolean = hasException(RestrictionException.BICYCLES)

    public fun exceptMotorcars(): Boolean = hasException(RestrictionException.MOTORCARS)

    override fun toString(): String {
        return "pos=$isPositive fromId=$fromId toId=$toId"
    }

    public companion object {
        public fun isTurnForbidden(
            first: TurnRestriction?,
            fromId: Long,
            toId: Long,
            bikeMode: Boolean,
            carMode: Boolean
        ): Boolean {
            var hasAnyPositive = false
            var hasPositive = false
            var hasNegative = false
            var restriction = first

            while (restriction != null) {
                if ((restriction.exceptBikes() && bikeMode) ||
                    (restriction.exceptMotorcars() && carMode)
                ) {
                    restriction = restriction.next
                    continue
                }

                if (restriction.fromId == fromId) {
                    if (restriction.isPositive) {
                        hasAnyPositive = true
                    }

                    if (restriction.toId == toId) {
                        if (restriction.isPositive) {
                            hasPositive = true
                        } else {
                            hasNegative = true
                        }
                    }
                }

                restriction = restriction.next
            }

            return !hasPositive && (hasAnyPositive || hasNegative)
        }

        public fun create(
            isPositive: Boolean,
            exceptions: Short,
            from: Position,
            to: Position
        ): TurnRestriction = create(
            isPositive = isPositive,
            exceptions = exceptions,
            fromLongitude = from.longitude,
            fromLatitude = from.latitude,
            toLongitude = to.longitude,
            toLatitude = to.latitude,
        )

        public fun create(
            isPositive: Boolean,
            exceptions: Short,
            fromLongitude: Int,
            fromLatitude: Int,
            toLongitude: Int,
            toLatitude: Int,
        ): TurnRestriction = TurnRestriction(
            isPositive = isPositive,
            exceptions = exceptions,
            fromId = Position.computeId(fromLongitude, fromLatitude),
            toId = Position.computeId(toLongitude, toLatitude)
        )
    }
}
