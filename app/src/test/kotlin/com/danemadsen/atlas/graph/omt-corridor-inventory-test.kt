package com.danemadsen.atlas.graph

import com.danemadsen.atlas.pmtiles.PmtilesReader
import com.danemadsen.atlas.pmtiles.TileBounds
import com.danemadsen.atlas.pmtiles.mvt.MvtGeomType
import com.danemadsen.atlas.pmtiles.mvt.MvtTile
import com.danemadsen.atlas.beerouter.expressions.BExpressionContextWay
import com.danemadsen.atlas.beerouter.expressions.BExpressionMetaData
import java.io.File
import kotlin.test.Test

/**
 * Dev-machine diagnostic (not an assertion test): the corridor ladder in
 * [OmtPipelineDiagnosticTest] localized the car-graph break to the Little
 * River rung ("target island detected" at 144.53,-37.98, legs north and
 * south of it route fine). This dumps every transportation linestring in
 * that window WITH its synthesized routing tags AND the car profile's
 * costfactor for both directions — the raw-ways union-find connects, so
 * the break must be in class synthesis or profile acceptance.
 */
class OmtCorridorInventoryTest {

    // The Little River rung of the corridor ladder, wide enough to hold
    // the Princes Fwy/Hwy plus the town grid and farm connectors.
    private val west = 144.44
    private val east = 144.64
    private val south = -38.07
    private val north = -37.92

    /**
     * Focused dump of the car-graph seam the diagnostic localized: OURS
     * splits the car network into a metro component and a corridor component
     * whose only near-touch is at (144.527, -37.875), where two carriageways
     * of the divided Geelong Rd face each other with a ~104m fragment gap
     * and no feature (routable or not) covering it. This prints the RAW
     * archive features there — ids, all properties, full geometry — to see
     * whether the gap is in the source tiles or introduced downstream.
     */
    @Test
    fun dumpSeamBox() {
        val archive = File("src/test/fixtures/melbourne.pmtiles")
        if (!archive.isFile) return
        PmtilesReader(archive.absolutePath).use { reader ->
            val zoom = minOf(reader.header.maxZoom, GraphPipeline.MAX_SCAN_ZOOM)
            reader.forEachTileInBounds(
                zoom,
                TileBounds(144.50, -37.89, 144.55, -37.86),
            ) { z, x, y, bytes ->
                val tile = MvtTile.decode(bytes)
                val layer = tile.layer("transportation") ?: return@forEachTileInBounds
                for (feature in layer.features) {
                    if (feature.geomType != MvtGeomType.LINESTRING) continue
                    val props = layer.properties(feature)
                    val paths = layer.pathsLonLat(feature, z, x, y)
                    if (paths.isEmpty()) continue
                    val tags = TransportationTagSynthesis.synthesizeTags(props)
                    val chain = paths.joinToString(" | ") { path ->
                        path.joinToString(" ") { "%.5f,%.5f".format(it.lon, it.lat) }
                    }
                    println(
                        "SEAM z=$z/$x/$y id=${feature.id} tags=${tags ?: "SYNTH-DROP"} " +
                            "props=${props.entries.joinToString { (k, v) -> "$k=$v" }} " +
                            "paths=${paths.size}: $chain",
                    )
                }
            }
        }
    }

    @Test
    fun dumpCorridorInventory() {
        val archive = File("src/test/fixtures/melbourne.pmtiles")
        if (!archive.isFile) return
        val profile_dir = findProfileDir()
        val lookup_text = File(profile_dir, "lookups.dat").readText()
        val profile_text = File(profile_dir, "all.brf").readText()

        val meta = BExpressionMetaData()
        val ctx = BExpressionContextWay(meta)
        meta.readMetaData(lookup_text)
        ctx.parseProfile(profile_text, "global")

        data class Row(
            val highway: String,
            val oneway: String,
            val extra: String,
            val cf_fwd: Float,
            val cf_rev: Float,
            val vertices: Int,
        )

        val rows = ArrayList<Row>()
        var features = 0
        var dropped_synth = 0
        var dropped_profile = 0

        PmtilesReader(archive.absolutePath).use { reader ->
            val zoom = minOf(reader.header.maxZoom, GraphPipeline.MAX_SCAN_ZOOM)
            reader.forEachTileInBounds(zoom, TileBounds(west, south, east, north)) { z, x, y, bytes ->
                val tile = MvtTile.decode(bytes)
                val layer = tile.layer("transportation") ?: return@forEachTileInBounds
                for (feature in layer.features) {
                    if (feature.geomType != MvtGeomType.LINESTRING) continue
                    val props = layer.properties(feature)
                    val paths = layer.pathsLocal(feature)
                    val vertices = paths.sumOf { it.size }
                    if (vertices < 2) continue
                    features++

                    val tags = TransportationTagSynthesis.synthesizeTags(props) ?: run {
                        dropped_synth++
                        val class_name = props["class"] as? String ?: "?"
                        println("SYNTH-DROP class=$class_name subclass=${props["subclass"]} vertices=$vertices")
                        continue
                    }

                    val lookup_data = ctx.createNewLookupData()!!
                    for (key in tags.keys) {
                        ctx.addLookupValue(key, tags.getValue(key).replace(' ', '_'), lookup_data)
                    }
                    val description = ctx.encode(lookup_data)
                    if (description == null) {
                        dropped_profile++
                        println("ENCODE-NULL highway=${tags["highway"]} tags=$tags vertices=$vertices")
                        continue
                    }
                    ctx.evaluate(false, description)
                    val cf_fwd = ctx.costfactor
                    ctx.evaluate(true, description)
                    val cf_rev = ctx.costfactor
                    if (cf_fwd >= 10000f && cf_rev >= 10000f) {
                        dropped_profile++
                        println("UNROUTABLE highway=${tags["highway"]} oneway=${tags["oneway"]} cf=$cf_fwd/$cf_rev vertices=$vertices")
                    }
                    rows += Row(
                        highway = tags["highway"] ?: "?",
                        oneway = tags["oneway"] ?: "-",
                        extra = listOfNotNull(
                            tags["service"]?.let { "service=$it" },
                            tags["bridge"]?.let { "bridge" },
                            tags["tunnel"]?.let { "tunnel" },
                            (props["ref"] as? String)?.let { "ref=$it" },
                            (props["name"] as? String)?.takeIf { it.isNotBlank() }?.let { "name=$it" },
                        ).joinToString(" "),
                        cf_fwd = cf_fwd,
                        cf_rev = cf_rev,
                        vertices = vertices,
                    )
                }
            }
        }

        println("=== corridor box ($west..$east, $south..$north): $features linestrings, " +
            "$dropped_synth synth-dropped, $dropped_profile profile-dropped")
        rows.groupBy { it.highway + "/" + it.oneway }.toSortedMap().forEach { (key, group) ->
            val routable = group.count { it.cf_fwd < 10000f || it.cf_rev < 10000f }
            println(
                "highway/oneway=$key count=${group.size} routable=$routable " +
                    "vertices=${group.sumOf { it.vertices }} " +
                    "cf=${group.first().cf_fwd}/${group.first().cf_rev}",
            )
        }
        println("--- one-way rows (fwd-only or rev-only):")
        for (row in rows) {
            val fwd_ok = row.cf_fwd < 10000f
            val rev_ok = row.cf_rev < 10000f
            if (fwd_ok != rev_ok) {
                println(
                    "  ${row.highway} oneway=${row.oneway} ${row.extra} " +
                        "cf=${row.cf_fwd}/${row.cf_rev} vertices=${row.vertices}",
                )
            }
        }
    }

    private fun findProfileDir(): File {
        val candidates = listOf(
            File(System.getProperty("user.dir"), "src/main/kotlin/com/danemadsen/atlas/beerouter/profiles2"),
            File(System.getProperty("user.dir"), "misc/profiles2"),
        )
        return candidates.firstOrNull(File::isDirectory) ?: error("could not find misc/profiles2")
    }
}