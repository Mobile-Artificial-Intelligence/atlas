package com.danemadsen.atlas.graph

import com.danemadsen.atlas.pmtiles.PmtilesReader
import java.io.File

/**
 * Dev-only CLI: build one 5° bucket from an arbitrary PMTiles archive under
 * an arbitrary heap, outside the JUnit harness (whose 8g maxHeap is there so
 * the *diagnostics* never OOM — useless for proving a device heap suffices).
 *
 * Run via `./gradlew :app:graphBuildCli -Parchive=<pmtiles>
 * -Pbucket=<lonMin>,<latMin> -Pout=<dir> [-Pheap=576m]`; the task caps the
 * forked JVM's heap exactly like the device's largeHeap cap, so a green run
 * here means the on-device build fits. An object with a @JvmStatic main (not
 * a top-level fun main) so the Gradle task can name the class without the
 * kebab-case filename's facade-class mangling.
 */
object GraphBuildCli {
    /** Heap snapshot after a forced GC — the live set, not the churn. */
    private fun liveHeapM(): Long {
        val runtime = Runtime.getRuntime()
        return try {
            System.gc()
            (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
        } catch (_: Throwable) {
            (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val archive = File(args[0])
        val lon_min = args[1].toInt()
        val lat_min = args[2].toInt()
        val out_dir = File(args[3]).apply { mkdirs() }
        val profile_dir = listOf(
            // user.dir is the module dir (lib/graph), same as the unit tests.
            File("src/main/kotlin/com/danemadsen/atlas/beerouter/profiles2"),
            File("misc/profiles2"),
        ).firstOrNull(File::isDirectory)
            ?: error("could not find misc/profiles2 (user.dir=${System.getProperty("user.dir")})")

        println(
            "building bucket ${GraphPipeline.bucketName(lon_min, lat_min)} from " +
                "${archive.name} (${archive.length()} bytes) into ${out_dir.absolutePath}",
        )

        val work_dir = File(System.getProperty("java.io.tmpdir"), "graph-build-cli").apply {
            deleteRecursively()
            mkdirs()
        }
        // Phase-level live-set profile: every linker probe prints the heap
        // after a forced GC, so the numbers show what each phase actually
        // retains rather than the churn it produced.
        com.danemadsen.atlas.beerouter.map.generator.WayLinker.onPhase = { phase ->
            println("PHASE $phase live=${liveHeapM()}M")
        }
        val start = System.currentTimeMillis()
        PmtilesReader(archive.absolutePath).use { reader ->
            val result = GraphPipeline(work_dir).buildBucket(
                reader = reader,
                lookupFile = File(profile_dir, "lookups.dat"),
                profileAllFile = File(profile_dir, "all.brf"),
                bucketLonMin = lon_min,
                bucketLatMin = lat_min,
                segmentsDir = out_dir,
            )
            val elapsed = (System.currentTimeMillis() - start) / 1000.0
            val max_m = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
            println(
                "BUILD RESULT: ${result.bucketName} rd5=${result.rd5File?.name} " +
                    "nodes=${result.nodeCount} wayBuckets=${result.wayBucketCount} " +
                    "in ${"%.1f".format(elapsed)}s; heap now ${liveHeapM()}M/${max_m}M",
            )
        }
        work_dir.deleteRecursively()
    }
}