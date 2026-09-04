package com.danemadsen.atlas.graph

import com.danemadsen.atlas.pmtiles.PmtilesReader
import com.danemadsen.atlas.pmtiles.TileBounds
import com.danemadsen.atlas.pmtiles.mvt.MvtGeomType
import com.danemadsen.atlas.pmtiles.mvt.MvtTile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M5b acceptance against real decoded tiles: every `class` value actually
 * present in the melbourne fixture's transportation layer must be either
 * mapped to a highway value or explicitly dropped by the synthesis — a
 * future OMT schema change fails here instead of silently dropping roads.
 *
 * Runs only when the dev-machine fixture exists (minted by
 * `planetiler --bounds=144.2,-38.4,145.4,-37.4`); CI machines without it
 * skip silently.
 */
class OmtTransportationCensusTest {

    @Test
    fun everyTransportationClassIsHandled() {
        val archive = fixtureArchive() ?: return

        val classes = sortedMapOf<String, Int>()
        val subclasses_by_class = sortedMapOf<String, MutableSet<String>>()
        val brunnel_values = mutableSetOf<String>()
        var oneway_positive = 0
        var oneway_negative = 0
        var routable_features = 0

        PmtilesReader(archive.absolutePath).use { reader ->
            val zoom = minOf(reader.header.maxZoom, GraphPipeline.MAX_SCAN_ZOOM)
            // The whole fixture bbox, not a metro core: oneway=-1 is rare
            // (Planetiler normalizes most reversed one-ways to oneway=1 with
            // flipped geometry; only 4 survive fixture-wide) and several sit
            // outside any Werribee-centered window.
            val bounds = TileBounds(
                west = 144.2, south = -38.4, east = 145.4, north = -37.4,
            )
            reader.forEachTileInBounds(zoom, bounds) { z, x, y, bytes ->
                val tile = MvtTile.decode(bytes)
                val layer = tile.layer("transportation") ?: return@forEachTileInBounds
                for (feature in layer.features) {
                    if (feature.geomType != MvtGeomType.LINESTRING) continue
                    val props = layer.properties(feature)
                    val class_name = props["class"] as? String ?: continue
                    classes.merge(class_name, 1, Int::plus)
                    (props["subclass"] as? String)?.let { subclass ->
                        subclasses_by_class
                            .getOrPut(class_name) { sortedSetOf() }
                            .add(subclass)
                    }
                    (props["brunnel"] as? String)?.let { brunnel_values.add(it) }
                    // The decoder hands small varints back as Int; the old
                    // 1L-only match silently counted zero.
                    when (props["oneway"]) {
                        1, 1L -> oneway_positive++
                        -1, -1L -> oneway_negative++
                    }
                    if (TransportationTagSynthesis.synthesizeTags(props) != null) {
                        routable_features++
                    }
                }
            }
        }

        // ---- the actual assertions ----
        for (class_name in classes.keys) {
            assertTrue(
                TransportationTagSynthesis.isKnownClass(class_name),
                "unhandled OMT transportation class '$class_name' " +
                    "(${classes[class_name]} features) — extend TransportationTagSynthesis",
            )
        }
        // A metro core must have real road coverage in every routable class.
        for (expected in listOf(
            "motorway", "trunk", "primary", "secondary", "tertiary", "minor",
            "service", "path", "track",
        )) {
            assertTrue(
                expected in classes,
                "expected class '$expected' among ${classes.keys} — fixture or decode problem",
            )
        }
        // Non-routable classes are expected to be present in the data (and
        // explicitly dropped) — Melbourne has rail and ferry lines. Planetiler
        // emits class "rail"; older OMT used major_rail/minor_rail.
        assertTrue(
            "rail" in classes || "major_rail" in classes || "minor_rail" in classes,
            "no rail in fixture?",
        )
        assertTrue(routable_features > 500, "only $routable_features routable features")

        // oneway must survive into tags in both signs. -1 is rare in OMT
        // (Planetiler normalizes most reversed one-ways to 1 + flipped
        // geometry) but does occur — 4 features fixture-wide at mint time.
        assertTrue(oneway_positive > 10, "only $oneway_positive oneway=1 features")
        assertTrue(oneway_negative > 0, "no oneway=-1 features in fixture")
        for (value in brunnel_values) {
            assertTrue(value in setOf("bridge", "tunnel", "ford"), "unexpected brunnel '$value'")
        }
    }

    @Test
    fun melbourneCensusSpotChecksSynthesisOutput() {
        val archive = fixtureArchive() ?: return

        var saw_footway = false
        var saw_bridge_minor = false
        var saw_surface_carried = false
        var saw_access_carried = false

        PmtilesReader(archive.absolutePath).use { reader ->
            val zoom = minOf(reader.header.maxZoom, GraphPipeline.MAX_SCAN_ZOOM)
            val bounds = TileBounds(west = 144.55, south = -38.05, east = 144.75, north = -37.9)
            reader.forEachTileInBounds(zoom, bounds) { z, x, y, bytes ->
                val tile = MvtTile.decode(bytes)
                val layer = tile.layer("transportation") ?: return@forEachTileInBounds
                for (feature in layer.features) {
                    if (feature.geomType != MvtGeomType.LINESTRING) continue
                    val props = layer.properties(feature)
                    val tags = TransportationTagSynthesis.synthesizeTags(props) ?: continue
                    if (tags["highway"] == "footway") saw_footway = true
                    if (tags["highway"] == "residential" && tags["bridge"] == "yes") {
                        saw_bridge_minor = true
                    }
                    // Planetiler carries surface/access on tagged features —
                    // the profiles evaluate both, so they must survive.
                    if (tags["surface"] != null) saw_surface_carried = true
                    if (tags["access"] != null) saw_access_carried = true
                }
            }
        }

        assertTrue(saw_footway, "no footway synthesized from path+footway")
        assertTrue(saw_bridge_minor, "no bridged minor road carried bridge=yes")
        assertTrue(saw_surface_carried, "no surface tag carried across")
        assertTrue(saw_access_carried, "no access tag carried across")
    }

    /** The dev-machine fixture; absent on machines without it. */
    private fun fixtureArchive(): java.io.File? {
        val file = java.io.File("src/test/fixtures/melbourne.pmtiles")
        if (!file.isFile) {
            println("skipping: no fixture at ${file.absolutePath}")
            return null
        }
        return file
    }
}