package com.danemadsen.atlas.beerouter.map.generator

import org.openstreetmap.osmosis.osmbinary.Osmformat
import java.util.Date

public class BPbfFieldDecoder(primitiveBlock: Osmformat.PrimitiveBlock) {
    private val strings: Array<String> = Array(primitiveBlock.stringtable.sCount) { idx ->
        primitiveBlock.stringtable.getS(idx).toStringUtf8()
    }
    private val coordGranularity: Int = primitiveBlock.granularity
    private val coordLatitudeOffset: Long = primitiveBlock.latOffset
    private val coordLongitudeOffset: Long = primitiveBlock.lonOffset
    private val dateGranularity: Int = primitiveBlock.dateGranularity

    public fun decodeLatitude(rawLatitude: Long): Double =
        COORDINATE_SCALING_FACTOR * (coordLatitudeOffset + coordGranularity * rawLatitude)

    public fun decodeLongitude(rawLongitude: Long): Double =
        COORDINATE_SCALING_FACTOR * (coordLongitudeOffset + coordGranularity * rawLongitude)

    public fun decodeTimestamp(rawTimestamp: Long): Date = Date(dateGranularity * rawTimestamp)

    public fun decodeString(rawString: Int): String = strings[rawString]

    private companion object {
        private const val COORDINATE_SCALING_FACTOR: Double = 0.000000001
    }
}
