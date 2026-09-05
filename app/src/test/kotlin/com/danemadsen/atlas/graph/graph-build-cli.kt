package com.danemadsen.atlas.graph

import com.danemadsen.atlas.pmtiles.PmtilesReader
import java.io.File
import java.util.zip.ZipOutputStream

/**
 * Dev/CI CLI: build 5° buckets from an arbitrary PMTiles archive under an
 * arbitrary heap, outside the JUnit harness (whose 8g maxHeap is there so
 * the *diagnostics* never OOM — useless for proving a device heap suffices).
 *
 * Two modes:
 *  - single bucket: `args = [<archive>, <lon,lat>, <out_dir>]` — the device
 *    heap-proof harness this file has always been;
 *  - ALL buckets (`<lon,lat>` = "all"): enumerates every 5° bucket the
 *    archive's bbox touches and builds each one — this is the CI path that
 *    mints a country's prebuilt routing data (the app's
 *    `adoptPrebuiltSegments` installs it and routing works immediately,
 *    no on-device build). The all-mode ends by writing `manifest.json`
 *    INTO the out dir, so the dir itself is what CI uploads: upload-artifact
 *    zips it, and the artifact zip is directly adoptable. A 4th arg
 *    additionally mints the same layout as a local ZIP.
 *
 * Run via `./gradlew :app:graphBuildCli -Parchive=<pmtiles>
 * -Pbucket=<lon,lat>|all -Pout=<dir> [-Pzip=<file>] [-Pheap=576m]`; the
 * task caps the forked JVM's heap exactly like the device's largeHeap cap,
 * so a green default-heap run means the on-device build fits. An object
 * with a @JvmStatic main (not a top-level fun main) so the Gradle task can
 * name the class without the kebab-case filename's facade-class mangling.
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

    /** Marker file for a bucket whose scan found no routable ways. */
    private const val EMPTY_SUFFIX = ".empty"

    @JvmStatic
    fun main(args: Array<String>) {
        val archive = File(args[0])
        val bucket_spec = args[1]
        val out_dir = File(args[2]).apply { mkdirs() }
        val zip_path = args.getOrNull(3)?.let { File(it).apply { parentFile?.mkdirs() } }
        val profile_dir = listOf(
            // user.dir is the module dir (app/), same as the unit tests.
            File("src/main/kotlin/com/danemadsen/atlas/beerouter/profiles2"),
            File("misc/profiles2"),
        ).firstOrNull(File::isDirectory)
            ?: error("could not find profiles2 (user.dir=${System.getProperty("user.dir")})")

        val work_dir = File(System.getProperty("java.io.tmpdir"), "graph-build-cli").apply {
            deleteRecursively()
            mkdirs()
        }
        // Everything already in out_dir (rd5s, .empty markers) belongs to
        // ONE archive; bind the dir to this archive before building so a
        // resume over a changed archive can't mint a mixed-archive ZIP.
        val fingerprint = archiveFingerprintOf(archive)
        ensureOutDirProvenance(out_dir, fingerprint)
        // Phase-level live-set profile: every linker probe prints the heap
        // after a forced GC, so the numbers show what each phase actually
        // retains rather than the churn it produced. The CI log doubles as
        // the per-country heap audit.
        com.danemadsen.atlas.beerouter.map.generator.WayLinker.onPhase = { phase ->
            println("PHASE $phase live=${liveHeapM()}M")
        }

        if (bucket_spec == "all") {
            buildAllBuckets(archive, out_dir, work_dir, profile_dir)
            // The all-mode walked the WHOLE bbox, so its empty list is
            // complete: leave the dir itself adoptable (CI uploads the dir
            // as the routing artifact — upload-artifact zips it, and a
            // minted ZIP would only nest a zip inside that zip, which the
            // app's picker can't read). -Pzip still mints one for local use.
            writeRoutingManifest(out_dir, fingerprint)
            println("SEGMENTS RESULT: ${out_dir.path} (${out_dir.listFiles()!!.size} files)")
        } else {
            val (lon_min, lat_min) = bucket_spec.split(",").map { it.trim().toInt() }
            buildOneBucket(archive, lon_min, lat_min, out_dir, work_dir, profile_dir)
        }

        if (zip_path != null) {
            writeRoutingZip(out_dir, zip_path, fingerprint)
            println("ZIP RESULT: ${zip_path.path} (${zip_path.length()} bytes)")
        }
        work_dir.deleteRecursively()
    }

    /**
     * The out dir's provenance marker: the all-mode's resume skip and
     * [writeRoutingZip] trust whatever .rd5/.empty files are already
     * there, and the ZIP stamps THIS run's fingerprint over them. Without
     * the marker, building from archive A into a dir and then resuming
     * with archive B would silently mint a mixed-archive ZIP that passes
     * its own fingerprint gate on the device. The first build records the
     * archive; any later run against a different archive refuses.
     */
    private fun ensureOutDirProvenance(out_dir: File, fingerprint: String) {
        val marker = File(out_dir, OUT_FINGERPRINT_FILE)
        val previous = runCatching { marker.readText().trim() }.getOrNull()
        when {
            previous == fingerprint -> return
            previous != null -> error(
                "the segments dir ${out_dir.name} was built from a different " +
                    "map archive — use a fresh -Pout or delete it",
            )
            // A dir with files but no marker predates the marker; its
            // archive is unknown, so refuse rather than guess.
            out_dir.listFiles().orEmpty().any { it.isFile } -> error(
                "the segments dir ${out_dir.name} was built by an older run " +
                    "and its archive is unknown — use a fresh -Pout or delete it",
            )
            else -> marker.writeText(fingerprint)
        }
    }

    private fun buildOneBucket(
        archive: File,
        lon_min: Int,
        lat_min: Int,
        out_dir: File,
        work_dir: File,
        profile_dir: File,
    ) {
        val name = GraphPipeline.bucketName(lon_min, lat_min)
        println(
            "building bucket $name from ${archive.name} (${archive.length()} bytes) " +
                "into ${out_dir.absolutePath}",
        )
        val start = System.currentTimeMillis()
        printBuildResult(buildBucketInto(archive, out_dir, work_dir, profile_dir, lon_min, lat_min), start)
    }

    /**
     * One bucket, exactly like the device's GraphBuildManager builds it: a
     * FRESH pipeline over a FRESH per-bucket work dir, deleted after — the
     * all-buckets mode runs for hours over dozens of buckets, and a reused
     * pipeline would carry one bucket's caches into the next's heap.
     */
    private fun buildBucketInto(
        archive: File,
        out_dir: File,
        work_dir: File,
        profile_dir: File,
        lon_min: Int,
        lat_min: Int,
    ): GraphPipeline.BuildResult =
        PmtilesReader(archive.absolutePath).use { reader ->
            val bucket_work = File(work_dir, "build-${GraphPipeline.bucketName(lon_min, lat_min)}")
            try {
                GraphPipeline(bucket_work).buildBucket(
                    reader = reader,
                    lookupFile = File(profile_dir, "lookups.dat"),
                    profileAllFile = File(profile_dir, "all.brf"),
                    bucketLonMin = lon_min,
                    bucketLatMin = lat_min,
                    segmentsDir = out_dir,
                )
            } finally {
                bucket_work.deleteRecursively()
            }
        }

    /**
     * The CI mode: every bucket in the archive's bbox, in scan order, with
     * a resume marker per bucket (`<name>.rd5` or `<name>.empty`) so a
     * retry over a PERSISTENT out dir — a local run or a self-hosted
     * runner — pays only for the buckets it never reached; a hosted CI
     * retry starts from an empty workspace. The all-mode is hours for a
     * country-sized archive. The dir is archive-bound (see
     * [ensureOutDirProvenance]), so a resume always continues the SAME
     * archive's buckets.
     */
    private fun buildAllBuckets(archive: File, out_dir: File, work_dir: File, profile_dir: File) {
        PmtilesReader(archive.absolutePath).use { reader ->
            val header = reader.header
            val buckets = ArrayList<Pair<Int, Int>>()
            var lon = GraphPipeline.bucketLonMinFor(header.minLon)
            while (lon <= header.maxLon) {
                var lat = GraphPipeline.bucketLatMinFor(header.minLat)
                while (lat <= header.maxLat) {
                    buckets.add(lon to lat)
                    lat += GraphPipeline.BUCKET_DEGREES
                }
                lon += GraphPipeline.BUCKET_DEGREES
            }
            println(
                "building all ${buckets.size} buckets in ${archive.name}'s bbox " +
                    "(lon ${header.minLon}..${header.maxLon}, lat ${header.minLat}..${header.maxLat}) " +
                    "into ${out_dir.absolutePath}",
            )
            for ((index, corner) in buckets.withIndex()) {
                val name = GraphPipeline.bucketName(corner.first, corner.second)
                if (File(out_dir, "$name${RD5_SUFFIX}").isFile || File(out_dir, "$name$EMPTY_SUFFIX").isFile) {
                    println("[$index/${buckets.size}] $name: already built, skipping")
                    continue
                }
                val start = System.currentTimeMillis()
                val result = buildBucketInto(archive, out_dir, work_dir, profile_dir, corner.first, corner.second)
                if (result.isEmpty) File(out_dir, "$name$EMPTY_SUFFIX").writeText("")
                printBuildResult(result, start, prefix = "[${index + 1}/${buckets.size}] ")
            }
        }
    }

    private fun printBuildResult(
        result: GraphPipeline.BuildResult,
        start: Long,
        prefix: String = "",
    ) {
        val elapsed = (System.currentTimeMillis() - start) / 1000.0
        val max_m = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
        println(
            "${prefix}BUILD RESULT: ${result.bucketName} rd5=${result.rd5File?.name} " +
                "nodes=${result.nodeCount} wayBuckets=${result.wayBucketCount} " +
                "in ${"%.1f".format(elapsed)}s; heap now ${liveHeapM()}M/${max_m}M",
        )
    }

    /**
     * The manifest `adoptPrebuiltSegments` gates on, written next to the
     * segments: an all-mode out dir IS the adoptable artifact (the empty
     * list is complete — the all-mode walked the whole bbox). The empty
     * list comes from the `.empty` markers, exactly as [writeRoutingZip]
     * renders it. internal: exercised directly by the dir-manifest test.
     */
    internal fun writeRoutingManifest(outDir: File, fingerprint: String) {
        File(outDir, MANIFEST_FILE).writeText(
            renderRoutingManifest(fingerprint, emptyBucketsIn(outDir)),
        )
    }

    private fun emptyBucketsIn(outDir: File): List<String> =
        outDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(EMPTY_SUFFIX) }
            ?.map { it.name.removeSuffix(EMPTY_SUFFIX) }
            ?.sorted()
            ?: emptyList()

    /**
     * The routing ZIP `adoptPrebuiltSegments` installs: every `.rd5` in
     * [outDir] at the root, the `lookups.dat` the segments were built
     * against, and the manifest (archive fingerprint + the buckets that
     * scanned empty, from the `.empty` markers) that lets the app both
     * refuse a ZIP minted from a different archive and skip rescanning
     * ocean buckets. internal: exercised directly by the ZIP-layout test.
     */
    internal fun writeRoutingZip(outDir: File, zipFile: File, fingerprint: String) {
        val rd5s = outDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(RD5_SUFFIX) }
            ?.sortedBy { it.name }
            ?: error("no segments in $outDir")
        val lookups = File(outDir, LOOKUPS_FILE)
        check(lookups.isFile) { "no $LOOKUPS_FILE in $outDir — the ZIP must carry it" }
        val manifest_text = renderRoutingManifest(fingerprint, emptyBucketsIn(outDir))
        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            for (rd5 in rd5s) {
                zip.putNextEntry(java.util.zip.ZipEntry(rd5.name))
                rd5.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.putNextEntry(java.util.zip.ZipEntry(LOOKUPS_FILE))
            lookups.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry(MANIFEST_FILE))
            manifest_text.toByteArray().let { zip.write(it) }
            zip.closeEntry()
        }
    }

    private const val RD5_SUFFIX = ".rd5"
    private const val LOOKUPS_FILE = "lookups.dat"
    private const val MANIFEST_FILE = "manifest.json"
    private const val OUT_FINGERPRINT_FILE = "build-fingerprint.txt"
}