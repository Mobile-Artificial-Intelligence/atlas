package com.danemadsen.atlas.beerouter.map.generator

import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.map.MapSource
import com.danemadsen.atlas.beerouter.map.RandomAccessReader
import com.danemadsen.atlas.beerouter.router.OsmNodeNamed
import com.danemadsen.atlas.beerouter.router.RoutingContext
import com.danemadsen.atlas.beerouter.router.RoutingEngine
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Atlas M1 acceptance: the vendored generator mints BRouter-format `.rd5`
 * segments from the bundled `dreieich.pbf` fixture, and the vendored engine
 * routes across them using the profiles that ship as app assets
 * (car-vario / fastbike / hiking-mountain).
 */
class EngineRoutingTest {

    // Endpoints on named tertiary roads inside the fixture bbox
    // (49.9935..50.0099, 8.7006..8.7256), ~2.2 km apart as the crow flies.
    private val startLon = 8.698077
    private val startLat = 50.009288 // Hainer Chaussee (west end)
    private val endLon = 8.721041
    private val endLat = 50.002409 // Hainer Weg (east end)

    @Test
    fun routesAcrossDreieichWithCarProfile() {
        routeWithProfile("car-vario.brf") { track ->
            assertTrue(track.distance in 1500..8000, "car distance ${track.distance}m")
            assertTrue(track.nodes.size > 20, "only ${track.nodes.size} track points")
            assertTrue(track.totalSeconds > 60, "car ETA ${track.totalSeconds}s for ${track.distance}m")
            assertTrue(track.voiceHints.list.isNotEmpty(), "expected turn instructions")
        }
    }

    @Test
    fun routesAcrossDreieichWithBikeAndFootProfiles() {
        routeWithProfile("fastbike.brf") { track ->
            assertTrue(track.distance in 1500..9000, "bike distance ${track.distance}m")
            assertTrue(track.totalSeconds > 120, "bike ETA ${track.totalSeconds}s")
        }
        routeWithProfile("hiking-mountain.brf") { track ->
            assertTrue(track.distance in 1500..9000, "foot distance ${track.distance}m")
            assertTrue(track.totalSeconds > 300, "foot ETA ${track.totalSeconds}s")
        }
    }

    @Test
    fun mintedSegmentsFollowRd5Naming() {
        val minted = mintSegments()
        val names = requireNotNull(minted.segments.listFiles()).map { it.name }
        assertTrue(names.isNotEmpty(), "no segments minted")
        assertTrue(names.any { it.endsWith(".rd5") }, "no .rd5 files in $names")
    }

    private fun routeWithProfile(
        profileName: String,
        assertions: (com.danemadsen.atlas.beerouter.router.OsmTrack) -> Unit,
    ) {
        val minted = mintSegments()
        val profileDir = findProfileDir()
        val context = RoutingContext(
            profileContent = File(profileDir, profileName).readText(),
            lookupContent = minted.lookupFile.readText(),
            mapSource = FileMapSource(minted.segments),
            generateTurns = true,
        )
        val track = kotlinx.coroutines.runBlocking {
            RoutingEngine(context).doRouting(
                listOf(
                    OsmNodeNamed(Position.fromDegrees(startLon, startLat)).apply { name = "from" },
                    OsmNodeNamed(Position.fromDegrees(endLon, endLat)).apply { name = "to" },
                ),
            )
        }
        assertNotNull(track, "$profileName could not route across the fixture")
        assertions(track)
    }

    // ---- segment minting (the OsmFastCutter -> PosUnifier -> WayLinker
    //      pipeline Atlas's PmtilesCutter will drive the tail of) ----

    private class MintedSegments(val segments: File, val lookupFile: File)

    private fun mintSegments(): MintedSegments {
        val mapFile = testResourceFile("/dreieich.pbf")
        val workingDir = requireNotNull(mapFile.parentFile) // holds the srtm befs
        val profileDir = findProfileDir()
        val tmpdir = createTempDirectory("atlas-routing-test-").toFile()

        val nodes = File(tmpdir, "nodetiles").apply { mkdir() }
        val ways = File(tmpdir, "waytiles").apply { mkdir() }
        val nodes55 = File(tmpdir, "nodes55").apply { mkdir() }
        val ways55 = File(tmpdir, "waytiles55").apply { mkdir() }
        val lookupFile = File(profileDir, "lookups.dat")
        val relFile = File(tmpdir, "cycleways.dat")
        val resFile = File(tmpdir, "restrictions.dat")
        val borderFile = File(tmpdir, "bordernids.dat")

        OsmFastCutter.doCut(
            lookupFile,
            nodes,
            ways,
            nodes55,
            ways55,
            borderFile,
            relFile,
            resFile,
            File(profileDir, "all.brf"),
            File(profileDir, "trekking.brf"),
            File(profileDir, "softaccess.brf"),
            mapFile,
            null,
        )

        val unodes55 = File(tmpdir, "unodes55").apply { mkdir() }
        val bordernodes = File(tmpdir, "bordernodes.dat")
        PosUnifier().process(
            nodes55,
            unodes55,
            borderFile,
            bordernodes,
            workingDir.absolutePath,
            null,
        )

        val segments = File(tmpdir, "segments").apply { mkdir() }
        WayLinker().process(
            unodes55,
            ways55,
            bordernodes,
            resFile,
            lookupFile,
            File(profileDir, "all.brf"),
            segments,
            "rd5",
        )
        return MintedSegments(segments, lookupFile)
    }

    private fun testResourceFile(path: String): File {
        val resource = javaClass.getResource(path)
        assertNotNull(resource, "missing test resource $path")
        return File(URI(resource.toString()))
    }

    private fun findProfileDir(): File {
        val candidates = listOf(
            File(System.getProperty("user.dir"), "src/main/kotlin/com/danemadsen/atlas/beerouter/profiles2"),
            File(System.getProperty("user.dir"), "misc/profiles2"),
            File(System.getProperty("user.dir"), "../misc/profiles2"),
        )
        return candidates.firstOrNull(File::isDirectory)
            ?: error("could not find profiles2 from ${System.getProperty("user.dir")}")
    }

    private class FileMapSource(private val segmentDir: File) : MapSource {
        override fun exists(fileName: String): Boolean = File(segmentDir, fileName).isFile

        override fun open(fileName: String): RandomAccessReader =
            FileRandomAccessReader(File(segmentDir, fileName))
    }

    private class FileRandomAccessReader(file: File) : RandomAccessReader {
        private val delegate = RandomAccessFile(file, "r")

        override fun seek(position: Long) = delegate.seek(position)

        override fun readFully(buffer: ByteArray, offset: Int, length: Int) =
            delegate.readFully(buffer, offset, length)

        override fun length(): Long = delegate.length()

        override fun close() = delegate.close()
    }
}