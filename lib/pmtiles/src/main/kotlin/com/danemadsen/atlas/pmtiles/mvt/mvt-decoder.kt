package com.danemadsen.atlas.pmtiles.mvt

/**
 * Minimal Mapbox Vector Tile decoder — just enough for Atlas: layer/feature
 * iteration, tag lookup and geometry paths. Hand-rolled protobuf parsing, no
 * external dependencies (nothing offline-capable exists in a small AAR that
 * reads MVT, and the format here is ~200 lines).
 */
class MvtTile private constructor(
    val layers: Map<String, MvtLayer>,
) {
    fun layer(name: String): MvtLayer? = layers[name]

    companion object {
        fun decode(bytes: ByteArray): MvtTile {
            val layers = LinkedHashMap<String, MvtLayer>()
            val tile = ProtobufReader(bytes)
            while (tile.hasMore) {
                val tag = tile.readTag()
                // Tile: repeated Layer layer = 3;
                if (tag == ProtobufReader.tagOf(FIELD_LAYER, WIRE_LEN)) {
                    val layer = MvtLayer.decode(tile.readBytes())
                    layers[layer.name] = layer
                } else {
                    tile.skipField(tag)
                }
            }
            return MvtTile(layers)
        }

        private const val FIELD_LAYER = 3
    }
}

class MvtLayer private constructor(
    val name: String,
    val version: Int,
    val extent: Int,
    val keys: List<String>,
    val values: List<Any?>,
    val features: List<MvtFeature>,
) {
    /** Decoded feature properties via the layer's key/value tables. */
    fun properties(feature: MvtFeature): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>(feature.tags.size / 2)
        var i = 0
        while (i + 1 < feature.tags.size) {
            val key = keys.getOrNull(feature.tags[i].toInt())
            val value = values.getOrNull(feature.tags[i + 1].toInt())
            if (key != null) {
                result[key] = value
            }
            i += 2
        }
        return result
    }

    /** Geometry paths of [feature] as tile-local integer coordinates. */
    fun pathsLocal(feature: MvtFeature): List<List<TilePoint>> =
        decodeGeometry(feature.geometryCommands)

    /** Geometry paths of [feature] converted to WGS84 for tile (z, x, y). */
    fun pathsLonLat(
        feature: MvtFeature,
        zoom: Int,
        x: Int,
        y: Int,
    ): List<List<GeoPoint>> =
        pathsLocal(feature).map { path ->
            path.map { point ->
                val (lon, lat) = com.danemadsen.atlas.pmtiles.tilePointToLonLat(
                    zoom, x, y, point.x, point.y, extent,
                )
                GeoPoint(lon, lat)
            }
        }

    companion object {
        private const val FIELD_LAYER_NAME = 1
        private const val FIELD_LAYER_VERSION = 15
        private const val FIELD_LAYER_FEATURES = 2
        private const val FIELD_LAYER_KEYS = 3
        private const val FIELD_LAYER_VALUES = 4
        private const val FIELD_LAYER_EXTENT = 5

        fun decode(bytes: ByteArray): MvtLayer {
            var name = ""
            var version = 0
            var extent = 4096
            val keys = ArrayList<String>()
            val values = ArrayList<Any?>()
            val features = ArrayList<MvtFeature>()

            val reader = ProtobufReader(bytes)
            while (reader.hasMore) {
                when (val tag = reader.readTag()) {
                    ProtobufReader.tagOf(FIELD_LAYER_NAME, WIRE_LEN) ->
                        name = String(reader.readBytes(), Charsets.UTF_8)
                    ProtobufReader.tagOf(FIELD_LAYER_VERSION, WIRE_VARINT) ->
                        version = reader.readVarint().toInt()
                    ProtobufReader.tagOf(FIELD_LAYER_FEATURES, WIRE_LEN) ->
                        features.add(MvtFeature.decode(reader.readBytes()))
                    ProtobufReader.tagOf(FIELD_LAYER_KEYS, WIRE_LEN) ->
                        keys.add(String(reader.readBytes(), Charsets.UTF_8))
                    ProtobufReader.tagOf(FIELD_LAYER_VALUES, WIRE_LEN) ->
                        values.add(decodeValue(reader.readBytes()))
                    ProtobufReader.tagOf(FIELD_LAYER_EXTENT, WIRE_VARINT) ->
                        extent = reader.readVarint().toInt()
                    else -> reader.skipField(tag)
                }
            }
            return MvtLayer(name, version, extent, keys, values, features)
        }

        // Value: oneof string_value=1, float_value=2, double_value=3,
        // int64_value=4, uint64_value=5, sint64_value=6, bool_value=7
        private fun decodeValue(bytes: ByteArray): Any? {
            val reader = ProtobufReader(bytes)
            while (reader.hasMore) {
                return when (val tag = reader.readTag()) {
                    ProtobufReader.tagOf(1, WIRE_LEN) -> String(reader.readBytes(), Charsets.UTF_8)
                    ProtobufReader.tagOf(2, WIRE_FIXED32) -> Float.fromBits(reader.readFixed32().toInt())
                    ProtobufReader.tagOf(3, WIRE_FIXED64) -> Double.fromBits(reader.readFixed64())
                    ProtobufReader.tagOf(4, WIRE_VARINT) -> reader.readVarint()
                    ProtobufReader.tagOf(5, WIRE_VARINT) -> reader.readVarint()
                    ProtobufReader.tagOf(6, WIRE_VARINT) -> zigzagDecode(reader.readVarint().toInt())
                    ProtobufReader.tagOf(7, WIRE_VARINT) -> reader.readVarint() != 0L
                    else -> {
                        reader.skipField(tag)
                        continue
                    }
                }
            }
            return null
        }
    }
}

class MvtFeature private constructor(
    val geomType: MvtGeomType,
    val tags: IntArray,
    val geometryCommands: IntArray,
    val id: Long,
) {
    companion object {
        private const val FIELD_FEATURE_ID = 1
        private const val FIELD_FEATURE_TAGS = 2
        private const val FIELD_FEATURE_TYPE = 3
        private const val FIELD_FEATURE_GEOMETRY = 4

        fun decode(bytes: ByteArray): MvtFeature {
            var geomType = MvtGeomType.UNKNOWN
            var id = 0L
            val tags = ArrayList<Int>()
            val geometry = ArrayList<Int>()

            val reader = ProtobufReader(bytes)
            while (reader.hasMore) {
                when (val tag = reader.readTag()) {
                    ProtobufReader.tagOf(FIELD_FEATURE_ID, WIRE_VARINT) -> id = reader.readVarint()
                    ProtobufReader.tagOf(FIELD_FEATURE_TAGS, WIRE_LEN) -> {
                        val packed = ProtobufReader(reader.readBytes())
                        while (packed.hasMore) {
                            tags.add(packed.readVarint().toInt())
                        }
                    }
                    ProtobufReader.tagOf(FIELD_FEATURE_TYPE, WIRE_VARINT) ->
                        geomType = MvtGeomType.fromCode(reader.readVarint().toInt())
                    ProtobufReader.tagOf(FIELD_FEATURE_GEOMETRY, WIRE_LEN) -> {
                        val packed = ProtobufReader(reader.readBytes())
                        while (packed.hasMore) {
                            geometry.add(packed.readVarint().toInt())
                        }
                    }
                    else -> reader.skipField(tag)
                }
            }
            return MvtFeature(geomType, tags.toIntArray(), geometry.toIntArray(), id)
        }
    }
}

enum class MvtGeomType(val code: Int) {
    UNKNOWN(0),
    POINT(1),
    LINESTRING(2),
    POLYGON(3),
    ;

    companion object {
        fun fromCode(code: Int): MvtGeomType =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("unknown geometry type $code")
    }
}

data class TilePoint(val x: Int, val y: Int)
data class GeoPoint(val lon: Double, val lat: Double)

/**
 * Decode MVT geometry commands (MVT spec 4.3) into tile-local paths.
 * MoveTo=1, LineTo=2, ClosePath=7; parameters are zigzag-encoded deltas.
 */
fun decodeGeometry(commands: IntArray): List<List<TilePoint>> {
    val paths = ArrayList<List<TilePoint>>()
    var current = ArrayList<TilePoint>()
    var cursorX = 0
    var cursorY = 0
    var i = 0
    while (i < commands.size) {
        val command = commands[i]
        val commandId = command and 0x7
        val count = command ushr 3
        when (commandId) {
            CMD_MOVETO -> {
                repeat(count) {
                    cursorX += zigzagDecode(commands[i + 1])
                    cursorY += zigzagDecode(commands[i + 2])
                    i += 2
                    if (current.isNotEmpty()) {
                        paths.add(current)
                        current = ArrayList()
                    }
                    current.add(TilePoint(cursorX, cursorY))
                }
            }
            CMD_LINETO -> {
                repeat(count) {
                    cursorX += zigzagDecode(commands[i + 1])
                    cursorY += zigzagDecode(commands[i + 2])
                    i += 2
                    current.add(TilePoint(cursorX, cursorY))
                }
            }
            CMD_CLOSEPATH -> {
                if (current.isNotEmpty()) {
                    paths.add(current)
                    current = ArrayList()
                }
            }
            else -> throw IllegalArgumentException("unknown geometry command $commandId")
        }
        i++
    }
    if (current.isNotEmpty()) {
        paths.add(current)
    }
    return paths
}

private const val CMD_MOVETO = 1
private const val CMD_LINETO = 2
private const val CMD_CLOSEPATH = 7

private fun zigzagDecode(value: Int): Int = (value ushr 1) xor -(value and 1)

private const val WIRE_VARINT = 0
private const val WIRE_FIXED64 = 1
private const val WIRE_LEN = 2
private const val WIRE_FIXED32 = 5

/** Protobuf wire-format reader — tags, varints, fixed widths, skipping. */
private class ProtobufReader(
    private val buffer: ByteArray,
    private var position: Int = 0,
) {
    val hasMore: Boolean get() = position < buffer.size

    fun readTag(): Int {
        val key = readVarint().toInt()
        return key
    }

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            require(position < buffer.size) { "protobuf buffer underrun" }
            val b = buffer[position++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            require(shift <= 63) { "varint too long" }
        }
    }

    fun readFixed32(): Long {
        var result = 0L
        for (i in 3 downTo 0) {
            result = (result shl 8) or (buffer[position + i].toLong() and 0xFF)
        }
        position += 4
        return result
    }

    fun readFixed64(): Long {
        var result = 0L
        for (i in 7 downTo 0) {
            result = (result shl 8) or (buffer[position + i].toLong() and 0xFF)
        }
        position += 8
        return result
    }

    fun readBytes(): ByteArray {
        val length = readVarint().toInt()
        val bytes = buffer.copyOfRange(position, position + length)
        position += length
        return bytes
    }

    fun skipField(tag: Int) {
        when (tag and 0x7) {
            WIRE_VARINT -> readVarint()
            WIRE_FIXED64 -> position += 8
            WIRE_LEN -> {
                val length = readVarint().toInt()
                position += length
            }
            WIRE_FIXED32 -> position += 4
            else -> throw IllegalArgumentException("unsupported wire type ${tag and 0x7}")
        }
    }

    companion object {
        fun tagOf(field: Int, wireType: Int): Int = (field shl 3) or wireType
    }
}