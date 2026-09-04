package com.danemadsen.atlas.graph

import com.danemadsen.atlas.pmtiles.PmtilesReader
import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.map.generator.OsmFastCutter
import com.danemadsen.atlas.beerouter.map.generator.PosUnifier
import com.danemadsen.atlas.beerouter.map.generator.WayLinker
import com.danemadsen.atlas.beerouter.router.OsmNodeNamed
import com.danemadsen.atlas.beerouter.router.OsmTrack
import com.danemadsen.atlas.beerouter.router.RoutingContext
import com.danemadsen.atlas.beerouter.router.RoutingEngine
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M5c differential acceptance: the PMTiles graph pipeline must produce a
 * graph that routes essentially like the vendored PBF pipeline over the same
 * Melbourne rectangle.
 *
 *   reference: melbourne.pbf  -> OsmFastCutter -> PosUnifier -> WayLinker
 *   ours:      melbourne.pmtiles -> PmtilesCutter -> (same vendored tail)
 *
 * Both mint a BRouter `.rd5` for bucket E140_S40; both must route
 * Melbourne CBD -> Geelong on the car profile with distances in the same
 * band (MVT z14 clipping and the minor->residential merge make exact
 * equality impossible — the band catches structural breakage, e.g. failed
 * relinking at tile borders).
 *
 * Runs only when both dev-machine fixtures exist; otherwise skips.
 */
class GraphPipelineDifferentialTest {

    // Flinders Street Station -> Geelong railway station, ~55 km crow-fly,
    // both inside the fixture rectangle and bucket E140_S40.
    private val start_lon = 144.9669
    private val start_lat = -37.8183
    private val end_lon = 144.3680
    private val end_lat = -38.1493

    @Test
    fun pmtilesGraphRoutesLikeThePbfGraph() {
        val archive = File("src/test/fixtures/melbourne.pmtiles")
        val pbf = File("src/test/fixtures/melbourne.pbf")
        if (!archive.isFile || !pbf.isFile) {
            println("skipping: fixtures missing (${archive.isFile} / ${pbf.isFile})")
            return
        }
        val profile_dir = findProfileDir()
        val lookup_file = File(profile_dir, "lookups.dat")
        val profile_all = File(profile_dir, "all.brf")
        val car_profile = File(profile_dir, "car-vario.brf").readText()

        // ---- ours: PMTiles -> rd5 ----
        val our_segments = createTempDirectory("atlas-pmtiles-graph-").toFile()
        val our_result = GraphPipeline(
            createTempDirectory("atlas-pmtiles-work-").toFile(),
        ).buildBucket(
            reader = PmtilesReader(archive.absolutePath),
            lookupFile = lookup_file,
            profileAllFile = profile_all,
            bucketLonMin = 140,
            bucketLatMin = -40,
            segmentsDir = our_segments,
        )
        assertTrue(our_result.nodeCount > 50_000, "only ${our_result.nodeCount} nodes synthesized")

        // ---- reference: PBF -> rd5 (the vendored pipeline, unmodified) ----
        val ref_segments = mintReferenceSegments(pbf, lookup_file, profile_all)

        // ---- route on both, compare ----
        val our_track = routeBetween(
            our_segments, car_profile, lookup_file,
            start_lon, start_lat, end_lon, end_lat,
        )
        val ref_track = routeBetween(
            ref_segments, car_profile, lookup_file,
            start_lon, start_lat, end_lon, end_lat,
        )

        assertNotNull(our_track, "PMTiles graph could not route CBD -> Geelong")
        assertNotNull(ref_track, "PBF reference graph could not route CBD -> Geelong")

        assertTrue(
            our_track.distance in 55_000..95_000,
            "PMTiles car distance ${our_track.distance}m implausible",
        )
        assertTrue(
            ref_track.distance in 55_000..95_000,
            "PBF car distance ${ref_track.distance}m implausible",
        )
        val delta_pct = Math.abs(our_track.distance - ref_track.distance) * 100.0 /
            ref_track.distance
        assertTrue(
            delta_pct <= 15.0,
            "car distances diverged: pmtiles ${our_track.distance}m vs pbf " +
                "${ref_track.distance}m ($delta_pct%)",
        )
        assertTrue(
            our_track.voiceHints.list.isNotEmpty(),
            "no turn instructions on the PMTiles route",
        )
        println(
            "differential: pmtiles ${our_track.distance}m/${our_track.totalSeconds}s " +
                "vs pbf ${ref_track.distance}m/${ref_track.totalSeconds}s " +
                "(${our_result.nodeCount} synthesized nodes)",
        )

        // ---- foot profile on the same PMTiles graph: a short CBD walk ----
        val foot_track = routeBetween(
            our_segments,
            File(profile_dir, "hiking-mountain.brf").readText(),
            lookup_file,
            // Flinders Street Station -> State Library through the Hoddle Grid
            144.9669, -37.8183, 144.9631, -37.8100,
        )
        assertNotNull(foot_track, "foot profile could not route inside the CBD")
        assertTrue(foot_track.distance in 200..1_500, "foot distance ${foot_track.distance}m")
    }

    private fun routeBetween(
        segments: File,
        profile_content: String,
        lookup_file: File,
        from_lon: Double,
        from_lat: Double,
        to_lon: Double,
        to_lat: Double,
    ): OsmTrack? {
        val context = RoutingContext(
            profileContent = profile_content,
            lookupContent = lookup_file.readText(),
            mapSource = FileMapSource(segments),
            generateTurns = true,
        )
        return kotlinx.coroutines.runBlocking {
            RoutingEngine(context).doRouting(
                listOf(
                    OsmNodeNamed(Position.fromDegrees(from_lon, from_lat)).apply { name = "from" },
                    OsmNodeNamed(Position.fromDegrees(to_lon, to_lat)).apply { name = "to" },
                ),
            )
        }
    }

    // ---- reference minting: the vendored PBF pipeline, verbatim ----

    private fun mintReferenceSegments(
        pbf: File,
        lookup_file: File,
        profile_all: File,
    ): File {
        val tmpdir = createTempDirectory("atlas-ref-").toFile()
        val nodes = File(tmpdir, "nodes45").apply { mkdirs() }
        val ways = File(tmpdir, "ways45").apply { mkdirs() }
        val nodes55 = File(tmpdir, "nodes55").apply { mkdirs() }
        val ways55 = File(tmpdir, "ways55").apply { mkdirs() }
        val border_file = File(tmpdir, "bordernids.dat")
        val rel_file = File(tmpdir, "cycleways.dat")
        val res_file = File(tmpdir, "restrictions.dat")

        OsmFastCutter.doCut(
            lookup_file, nodes, ways, nodes55, ways55, border_file, rel_file, res_file,
            profile_all, profile_all, profile_all, pbf, null,
        )

        val unodes55 = File(tmpdir, "unodes55").apply { mkdirs() }
        val bordernodes = File(tmpdir, "bordernodes.dat")
        PosUnifier().process(nodes55, unodes55, border_file, bordernodes, "", null)

        val segments = File(tmpdir, "segments").apply { mkdirs() }
        WayLinker().process(
            unodes55, ways55, bordernodes, res_file, lookup_file, profile_all,
            segments, "rd5",
        )
        return segments
    }

    private fun findProfileDir(): File {
        val candidates = listOf(
            File(System.getProperty("user.dir"), "src/main/kotlin/com/danemadsen/atlas/beerouter/profiles2"),
            File(System.getProperty("user.dir"), "misc/profiles2"),
        )
        return candidates.firstOrNull(File::isDirectory)
            ?: error("could not find misc/profiles2 from ${System.getProperty("user.dir")}")
    }
}