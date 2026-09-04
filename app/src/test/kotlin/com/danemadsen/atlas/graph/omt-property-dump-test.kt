package com.danemadsen.atlas.graph

import com.danemadsen.atlas.pmtiles.PmtilesReader
import com.danemadsen.atlas.pmtiles.TileBounds
import com.danemadsen.atlas.pmtiles.mvt.MvtGeomType
import com.danemadsen.atlas.pmtiles.mvt.MvtTile
import kotlin.test.Test

/**
 * Dev-machine diagnostic (not an assertion test): dumps the distinct
 * property sets the melbourne fixture's transportation layer actually
 * carries, so synthesis decisions rest on observed data, not schema memory.
 */
class OmtPropertyDumpTest {

    @Test
    fun dumpTransportationProperties() {
        val archive = java.io.File("src/test/fixtures/melbourne.pmtiles")
        if (!archive.isFile) return

        // class/subclass -> sample property map (first seen)
        val seen = LinkedHashMap<String, MutableMap<String, String>>()
        val layers = sortedSetOf<String>()
        var tiles = 0

        PmtilesReader(archive.absolutePath).use { reader ->
            val zoom = minOf(reader.header.maxZoom, GraphPipeline.MAX_SCAN_ZOOM)
            val bounds = TileBounds(west = 144.55, south = -38.05, east = 144.75, north = -37.9)
            reader.forEachTileInBounds(zoom, bounds) { z, x, y, bytes ->
                val tile = MvtTile.decode(bytes)
                tiles++
                for (name in tile.layers.keys) layers.add(name)
                val layer = tile.layer("transportation") ?: return@forEachTileInBounds
                for (feature in layer.features) {
                    if (feature.geomType != MvtGeomType.LINESTRING) continue
                    val props = layer.properties(feature)
                    val class_name = props["class"] as? String ?: continue
                    val key = "$class_name/${props["subclass"] ?: "-"}"
                    if (seen.containsKey(key)) continue
                    val sample = mutableMapOf<String, String>()
                    for ((k, v) in props) sample[k] = "$v"
                    seen[key] = sample
                }
            }
        }

        println("=== layers in z14 tiles ($tiles tiles): $layers")
        for ((key, sample) in seen) {
            println("transportation $key -> $sample")
        }
    }
}