package com.danemadsen.atlas.graph

import com.danemadsen.atlas.pmtiles.PmtilesReader
import com.danemadsen.atlas.pmtiles.TileBounds
import com.danemadsen.atlas.beerouter.codec.DataBuffers
import com.danemadsen.atlas.beerouter.map.PhysicalFile
import com.danemadsen.atlas.beerouter.map.generator.NodeCutter
import com.danemadsen.atlas.beerouter.map.generator.NodeFilter
import com.danemadsen.atlas.beerouter.map.generator.PosUnifier
import com.danemadsen.atlas.beerouter.map.generator.WayCutter
import com.danemadsen.atlas.beerouter.map.generator.WayCutter5
import com.danemadsen.atlas.beerouter.map.generator.WayLinker
import java.io.File

/**
 * Drives one 5-degree-bucket `.rd5` build from a PMTiles archive:
 *
 *   PmtilesCutter   (NEW)          -> 45-degree .ntl/.wtl phase-1 tiles
 *   WayCutter5      (vendored)     -> 5-degree .wt5/.n5d + bordernids
 *   PosUnifier      (vendored)     -> position-uniquified .u5d + bordernodes
 *   WayLinker       (vendored)     -> staging/<bucket>.rd5 -> segmentsDir
 *
 * Relations and turn restrictions do not exist in MVT, so the vendored
 * RelationMerger/RestrictionCutter5 stages are simply not wired in — with no
 * relations/restrictions present they are provable no-ops. SRTM elevation
 * is likewise absent, so nodes carry UNSET_ELEVATION (routing works, the
 * ascend/descent figures read zero).
 *
 * A build scans exactly the bucket's bounding box: MVT features are clipped
 * per tile, so tiles intersecting the bucket already carry the geometry that
 * crosses its border — a scan margin would be redundant. Boundary tiles do
 * spill nodes into adjacent buckets, and `WayLinker` would then emit thin
 * partial `.rd5` files for them; this class links into a staging directory
 * inside the workDir and publishes only the target bucket's file, so a
 * rebuild can never truncate an already-built neighbor.
 *
 * `WayLinker` swallows exceptions inside its worker thread, so a mid-link
 * failure kills the thread silently and can leave a partial file behind
 * (the master/slave overlap is disabled here — one metro node map at a
 * time is all a device heap holds). The published `.rd5` is therefore
 * validated end-to-end with the runtime's own
 * [PhysicalFile.checkFileIntegrity] before it is moved into place — a
 * build either publishes a readable graph or throws.
 */
class GraphPipeline(private val workDir: File) {

    /** What one bucket build produced, for tests and progress reporting. */
    public data class BuildResult(
        public val bucketName: String,
        /** The published `.rd5`, or null for an empty (e.g. all-ocean) bucket. */
        public val rd5File: File?,
        public val nodeCount: Int,
        public val wayBucketCount: Int,
    ) {
        /** True when the bucket contained no routable ways at all. */
        public val isEmpty: Boolean
            get() = rd5File == null
    }

    /**
     * Builds the 5x5-degree bucket whose southwest corner is
     * ([bucketLonMin], [bucketLatMin]) degrees (both multiples of 5), using
     * the exact [lookupFile]/[profileAllFile] the routing runtime will use.
     * [segmentsDir] must exist, must live OUTSIDE [workDir] (the workDir is
     * wiped per build), and receives `<bucket>.rd5` plus the `lookups.dat`
     * copy the engine needs next to the segments.
     */
    public fun buildBucket(
        reader: PmtilesReader,
        lookupFile: File,
        profileAllFile: File,
        bucketLonMin: Int,
        bucketLatMin: Int,
        segmentsDir: File,
        onTileScanned: ((zoom: Int, x: Int, y: Int, bytes: ByteArray) -> Unit)? = null,
        onSubProgress: ((label: String, fraction: Float?) -> Unit)? = null,
    ): BuildResult {
        require(bucketLonMin % BUCKET_DEGREES == 0 && bucketLatMin % BUCKET_DEGREES == 0) {
            "bucket corners must be multiples of $BUCKET_DEGREES degrees"
        }
        val bucket_name = bucketName(bucketLonMin, bucketLatMin)
        require(
            !segmentsDir.canonicalFile.toPath().startsWith(workDir.canonicalFile.toPath()),
        ) {
            "segmentsDir must live outside the workDir: $segmentsDir vs $workDir"
        }
        segmentsDir.mkdirs()

        // Always start from an empty workDir: intermediates left behind by
        // an aborted run must never be re-consumed by a retry.
        workDir.deleteRecursively()
        workDir.mkdirs()

        val nodes45 = dir("nodes45")
        val ways45 = dir("ways45")
        val nodes55 = dir("nodes55")
        val ways55 = dir("ways55")
        val unodes55 = dir("unodes55")
        val staging = dir("segments")

        val bordernids = File(workDir, "bordernids.dat")
        val bordernodes = File(workDir, "bordernodes.dat")

        // ---- phase 1: PMTiles -> .ntl/.wtl ----
        // Scoped to its own function: the cutter phase holds its whole
        // working set in memory (fragments with full node chains, the
        // segment index), and the WayLinker stage later needs every byte of
        // a device's heap for its own node maps — the phase-1 objects must
        // be unreachable by then, not just unused.
        val scan = scanPhase(
            reader = reader,
            lookupFile = lookupFile,
            profileAllFile = profileAllFile,
            nodes45 = nodes45,
            ways45 = ways45,
            bucketLonMin = bucketLonMin,
            bucketLatMin = bucketLatMin,
            zoom = scanZoom(reader),
            onTileScanned = onTileScanned,
            onSubProgress = onSubProgress,
        )

        if (scan.featuresAccepted == 0) {
            // Nothing routable in this window (e.g. an all-ocean bucket):
            // not an error, just nothing to publish.
            workDir.deleteRecursively()
            return BuildResult(bucket_name, null, scan.nodeCount, 0)
        }

        // ---- phase 2: 45-degree tiles -> 5-degree buckets ----
        onSubProgress?.invoke(REBUCKETING_LABEL, null)
        WayCutter5().apply {
            this.nodeFilter = requireNotNull(scan.nodeFilter) {
                "scan phase always provides the node filter"
            }
            // The scan assigned dense nids 1..nodeCount, so the cutter5's
            // per-file index can be an array instead of a hashed map (see
            // the LOCAL PATCH note in the vendored WayCutter5).
            expectedNodeCount = scan.nodeCount
            nodeCutter = NodeCutter().also { it.init(nodes55) }
        }.process(nodes45, ways45, ways55, bordernids)
        // The filter's node bitmap is the last phase-1 holdover; drop the
        // reference before the memory-hungry linker runs.
        scan.nodeFilter = null

        // ---- phase 3: position dedup + (no) elevation ----
        onSubProgress?.invoke(UNIFYING_LABEL, null)
        PosUnifier().process(
            nodeTilesIn = nodes55,
            nodeTilesOut = unodes55,
            bordernidsinfile = bordernids,
            bordernodesoutfile = bordernodes,
            srtmdir = "", // no SRTM data on device: UNSET_ELEVATION nodes
            srtmfallbackdir = null,
        )

        // ---- phase 4: link into the staging dir ----
        onSubProgress?.invoke(LINKING_LABEL, null)
        WayLinker().process(
            nodeTilesIn = unodes55,
            wayTilesIn = ways55,
            borderFileIn = bordernodes,
            restrictionsFileIn = File(workDir, "unused-restrictions.dat"),
            lookupFile = lookupFile,
            profileFile = profileAllFile,
            dataTilesOut = staging,
            dataTilesSuffix = RD5_SUFFIX.removePrefix("."),
            // One metro node map at a time: a 5° bucket with ~3M nodes does
            // not fit in a device's largeHeap twice (see the vendored
            // LOCAL PATCH note on WayLinker.process).
            disableSlave = true,
        )

        val staged_rd5 = File(staging, "$bucket_name$RD5_SUFFIX")
        onSubProgress?.invoke(PUBLISHING_LABEL, null)
        require(staged_rd5.isFile) { "WayLinker produced no $bucket_name$RD5_SUFFIX" }
        validateRd5(staged_rd5)

        // Publish: only this bucket's file leaves the workDir. The thin
        // partial files WayLinker writes for adjacent buckets stay behind
        // and die with the workDir — publishing one would truncate the
        // neighbor's real graph the next time it is (re)built.
        val rd5 = File(segmentsDir, "$bucket_name$RD5_SUFFIX")
        staged_rd5.copyTo(rd5, overwrite = true)

        // The engine compares the rd5-embedded lookup versions against the
        // lookups.dat sitting next to the segments on every open.
        lookupFile.copyTo(File(segmentsDir, LOOKUPS_FILE), overwrite = true)

        workDir.deleteRecursively()
        return BuildResult(bucket_name, rd5, scan.nodeCount, 1)
    }

    /** Phase-1 outputs: small scalars plus the node filter phase 2 needs. */
    private class ScanPhaseResult(
        val nodeCount: Int,
        val featuresAccepted: Int,
        /** Nulled by [buildBucket] once phase 2 has consumed it. */
        var nodeFilter: NodeFilter?,
    )

    /**
     * Phase 1 in its own scope so its objects (the cutter's fragment list
     * and segment index, the WayCutter's per-node tile map) become
     * unreachable the moment this returns — on a device the linker phase
     * needs that heap back.
     */
    private fun scanPhase(
        reader: PmtilesReader,
        lookupFile: File,
        profileAllFile: File,
        nodes45: File,
        ways45: File,
        bucketLonMin: Int,
        bucketLatMin: Int,
        zoom: Int,
        onTileScanned: ((Int, Int, Int, ByteArray) -> Unit)?,
        onSubProgress: ((String, Float?) -> Unit)?,
    ): ScanPhaseResult {
        val nodeFilter = NodeFilter().also { it.init() }
        val wayCutter = WayCutter().also { it.init(ways45) }
        val cutter = PmtilesCutter().also {
            it.wayCutter = wayCutter
            it.nodeFilter = nodeFilter
            it.onTileScanned = onTileScanned
            it.onSubProgress = onSubProgress
        }
        cutter.process(
            reader = reader,
            lookupContent = lookupFile.readText(),
            profileContent = profileAllFile.readText(),
            nodeDir = nodes45,
            scanBounds = TileBounds(
                west = bucketLonMin.toDouble(),
                south = bucketLatMin.toDouble(),
                east = bucketLonMin + BUCKET_DEGREES.toDouble(),
                north = bucketLatMin + BUCKET_DEGREES.toDouble(),
            ),
            zoom = zoom,
        )
        wayCutter.finish()
        val result = ScanPhaseResult(cutter.nodeCount, cutter.featuresAccepted, nodeFilter)
        cutter.release()
        return result
    }

    /**
     * Reads the staged `.rd5` back through the runtime's checksum-verifying
     * reader. `WayLinker`'s worker threads swallow their own exceptions, so
     * a partial file is indistinguishable from success unless it is opened.
     */
    private fun validateRd5(rd5: File) {
        val error = runCatching {
            PhysicalFile.checkFileIntegrity(rd5.name, FileMapSource(rd5.parentFile))
        }.getOrElse { "integrity check threw: ${it.message}" }
        require(error == null) {
            "built ${rd5.name} failed its integrity check: $error"
        }
    }

    /**
     * The zoom to scan: the archive's detail maximum, capped at the OMT
     * profile's 14 (beyond which no more routing-relevant detail appears).
     */
    private fun scanZoom(reader: PmtilesReader): Int =
        minOf(reader.header.maxZoom, MAX_SCAN_ZOOM)

    private fun dir(name: String): File =
        File(workDir, name).apply { mkdirs() }

    public companion object {
        public const val BUCKET_DEGREES: Int = 5
        public const val MAX_SCAN_ZOOM: Int = 14

        /**
         * The bucket name for a WGS84 position, matching the runtime's
         * `PoiFinder.rd5FileName` (southwest corner, e.g. "E140_S40"): the
         * floor happens in the offset-positive integer space (0..360 /
         * 0..180), because truncating division on negative degrees would
         * round toward zero (-122.4 -> W120 instead of W125).
         */
        public fun bucketNameFor(lon: Double, lat: Double): String =
            bucketName(bucketLonMinFor(lon), bucketLatMinFor(lat))

        /** The WGS84 southwest-corner longitude of [lon]'s bucket. */
        public fun bucketLonMinFor(lon: Double): Int =
            (Math.floor(lon).toInt() + LON_OFFSET).floorDiv(BUCKET_DEGREES) *
                BUCKET_DEGREES - LON_OFFSET

        /** The WGS84 southwest-corner latitude of [lat]'s bucket. */
        public fun bucketLatMinFor(lat: Double): Int =
            (Math.floor(lat).toInt() + LAT_OFFSET).floorDiv(BUCKET_DEGREES) *
                BUCKET_DEGREES - LAT_OFFSET

        public fun bucketName(lonMin: Int, latMin: Int): String {
            val slon = if (lonMin < 0) "W${-lonMin}" else "E$lonMin"
            val slat = if (latMin < 0) "S${-latMin}" else "N$latMin"
            return "${slon}_${slat}"
        }

        private const val LON_OFFSET = 180
        private const val LAT_OFFSET = 90
        private const val RD5_SUFFIX = ".rd5"
        private const val LOOKUPS_FILE = "lookups.dat"

        // Sub-progress phase labels for the vendored stages (the cutter
        // emits its own from inside scanPhase). User-facing: they surface
        // through the service's status file, notification and banner.
        private const val REBUCKETING_LABEL = "Rebucketing ways"
        private const val UNIFYING_LABEL = "Unifying node positions"
        private const val LINKING_LABEL = "Linking the road graph"
        private const val PUBLISHING_LABEL = "Publishing routing segment"
    }
}