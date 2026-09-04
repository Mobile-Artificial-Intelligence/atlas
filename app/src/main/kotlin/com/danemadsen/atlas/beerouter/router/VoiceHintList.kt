/**
 * Container for a voice hint
 * (both input- and result data for voice hint processing)
 *
 * @author ab
 */
package com.danemadsen.atlas.beerouter.router

public class VoiceHintList(
    public val list: MutableList<VoiceHint> = mutableListOf()
) : MutableList<VoiceHint> by list {
    public enum class TransportMode(public val value: String, public val locusRouteType: Int) {
        FOOT("foot", 3),
        BIKE("bike", 5),
        CAR("car", 0)
    }

    public var transportMode: TransportMode = TransportMode.BIKE
        private set

    public fun setTransportMode(isCar: Boolean, isBike: Boolean) {
        transportMode = when {
            isCar -> TransportMode.CAR
            isBike -> TransportMode.BIKE
            else -> TransportMode.FOOT
        }
    }

    public fun getTransportMode(): String = transportMode.value

    public val locusRouteType: Int
        get() = transportMode.locusRouteType
}
