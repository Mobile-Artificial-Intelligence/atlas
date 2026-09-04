package com.danemadsen.atlas.beerouter.map.generator

public class HgtReader(folder: String) {
    init {
        @Suppress("UNUSED_VARIABLE")
        val unused = folder
    }

    public companion object {
        public var NO_ELEVATION: Double = Double.NaN

        public fun getElevationFromHgt(lat: Double, lon: Double): Double {
            throw UnsupportedOperationException("Direct HGT reading is not implemented in this port yet: $lat,$lon")
        }

        public fun getElevationDataFromHgt(lat: Double, lon: Double): ShortArray? {
            throw UnsupportedOperationException("Direct HGT reading is not implemented in this port yet: $lat,$lon")
        }
    }
}
