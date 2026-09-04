package com.danemadsen.atlas.graph

import com.danemadsen.atlas.graph.FileMapSource
import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.router.OsmNodeNamed
import com.danemadsen.atlas.beerouter.router.RoutingContext
import com.danemadsen.atlas.beerouter.router.RoutingEngine
import java.io.File
import kotlin.math.roundToInt

/**
 * Dev-only CLI: route through a built `.rd5` segments directory with the
 * STOCK engine, proving the artifact the device's :graph service produces is
 * actually routable end-to-end (the M5 acceptance route).
 *
 * Run via `./gradlew :app:graphRouteCli -Psegments=<dir>
 * -Pfrom=<lon>,<lat> -Pto=<lon>,<lat> -Pprofile=<name>` where <name> picks a
 * profile from misc/profiles2 (car-vario by default).
 */
object GraphRouteCli {
    @JvmStatic
    fun main(args: Array<String>) {
        val segments = File(args[0])
        val (from_lon, from_lat) = args[1].split(",").map { it.trim().toDouble() }
        val (to_lon, to_lat) = args[2].split(",").map { it.trim().toDouble() }
        val profile_name = args[3]
        val profile_dir = listOf(
            // user.dir is the module dir (lib/graph), same as the unit tests.
            File("src/main/kotlin/com/danemadsen/atlas/beerouter/profiles2"),
            File("misc/profiles2"),
        ).firstOrNull(File::isDirectory)
            ?: error("could not find misc/profiles2 (user.dir=${System.getProperty("user.dir")})")
        val profile_file = File(profile_dir, "$profile_name.brf")
        val lookup_file = File(segments, "lookups.dat")
        require(profile_file.isFile) { "no profile $profile_name.brf in ${profile_dir.absolutePath}" }
        require(lookup_file.isFile) { "no lookups.dat in ${segments.absolutePath}" }

        val context = RoutingContext(
            profileContent = profile_file.readText(),
            lookupContent = lookup_file.readText(),
            mapSource = FileMapSource(segments),
            generateTurns = true,
        )
        val start = System.currentTimeMillis()
        val track = kotlinx.coroutines.runBlocking {
            RoutingEngine(context).doRouting(
                listOf(
                    OsmNodeNamed(Position.fromDegrees(from_lon, from_lat)).apply { name = "from" },
                    OsmNodeNamed(Position.fromDegrees(to_lon, to_lat)).apply { name = "to" },
                ),
            )
        }
        val elapsed = (System.currentTimeMillis() - start) / 1000.0
        if (track == null) {
            println("ROUTE FAILED: no track (missing bucket, unreachable, or bad profile)")
            kotlin.system.exitProcess(1)
        }
        val km = track.distance / 1000.0
        val minutes = (track.totalSeconds / 60.0).roundToInt()
        println(
            "ROUTE OK: ${"%.1f".format(km)}km, ~${minutes}min, ascend=${track.ascend}m, " +
                "nodes=${track.nodes.size}, voiceHints=${track.voiceHints.size} " +
                "in ${"%.1f".format(elapsed)}s",
        )
        track.voiceHints.take(6).forEachIndexed { i, hint ->
            println("  hint[$i]: ${hint.command} @ ${"%.0f".format(hint.distanceToNext)}m")
        }
    }
}