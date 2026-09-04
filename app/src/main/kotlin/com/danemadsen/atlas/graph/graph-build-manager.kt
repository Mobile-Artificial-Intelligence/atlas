package com.danemadsen.atlas.graph

import com.danemadsen.atlas.pmtiles.PmtilesReader
import com.danemadsen.atlas.beerouter.map.PhysicalFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Schedules and records 5-degree-bucket `.rd5` builds for one PMTiles
 * archive, on top of [GraphPipeline].
 *
 * State lives in `build-state.json` inside [segmentsDir]:
 * the archive's fingerprint (content hash of its 127-byte PMTiles header
 * plus file length) and, per bucket, whether it was built and how many
 * nodes it holds. An empty (all-ocean) bucket is recorded as built with
 * `rd5 = null`, so it is not rescanned on every location update.
 *
 * A DIFFERENT archive in the same segmentsDir invalidates everything: the
 * graph on disk describes geometry the new archive no longer has, so the
 * whole directory is wiped and the state starts over.
 *
 * Cancellation is cooperative at bucket boundaries only — one bucket build
 * is a multi-minute CPU phase that cannot be interrupted mid-way; cancel()
 * stops the run after the current bucket finishes and publishes.
 */
class GraphBuildManager(
    private val archiveFile: File,
    private val segmentsDir: File,
    private val workRoot: File,
    /** Where `lookups.dat` and `all.brf` live; defaults to the archive's dir. */
    private val assetsDir: File? = null,
) {

    /** What a prebuilt-segments ZIP installed, for progress/UI reporting. */
    public data class Adoption(
        public val buckets: List<String>,
        /** True when the ZIP carried a `lookups.dat` and it was installed. */
        public val replacedLookups: Boolean,
        /**
         * Buckets the ZIP's manifest declared empty (all-ocean/no roads):
         * no `.rd5` exists for them, but recording them built is the whole
         * point — otherwise the on-device build rescans every ocean cell
         * in the country for nothing. Empty unless a manifest authenticated.
         */
        public val emptyBuckets: List<String> = emptyList(),
    )

    /** One bucket's recorded build state. */
    public data class BucketState(
        /** "built" is the only terminal state; failures are simply absent. */
        public val state: String,
        /** Null for an empty (no routable ways) bucket. */
        public val rd5: String? = null,
        public val nodeCount: Int = 0,
    )

    // internal: the state-file round-trip (render -> parse -> render, with
    // an empty bucket's rd5 = null surviving intact) is tested directly.
    internal data class BuildState(
        var archiveFingerprint: String = "",
        val buckets: MutableMap<String, BucketState> = mutableMapOf(),
    )

    /** Live progress of an [ensureBucketsFor]/[buildAll] run. */
    public data class Progress(
        public val bucket: String,
        public val built: Int,
        public val total: Int,
        public val building: Boolean, // false once this bucket finished
        /**
         * The current step WITHIN the bucket build ("Stitching road
         * junctions", ...), or null at bucket boundaries and before the
         * first step reports.
         */
        public val label: String? = null,
        /**
         * 0..1 through the WHOLE bucket (composed from the step's phase
         * index and its own fraction by the forwarder in [ensure]) — never
         * null while a step reports, so the bar is monotonic mid-bucket.
         */
        public val fraction: Float? = null,
    )

    private val stateFile: File get() = File(segmentsDir, STATE_FILE)
    private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)

    /** True between ensure() entry and exit; cancel() only arms while it holds. */
    @Volatile private var runActive = false

    /** Buckets recorded as built for the current archive. */
    public fun builtBuckets(): Set<String> = readState().buckets.keys.toSet()

    /** A snapshot copy of the durable per-bucket records (never a live map). */
    public fun bucketStates(): Map<String, BucketState> = readState().buckets.toMap()

    /** One bucket's recorded state, or null when it was never built. */
    public fun bucketState(bucket: String): BucketState? = readState().buckets[bucket]

    /**
     * True when the archive fingerprint on record no longer matches —
     * the caller should expect [wipeIfArchiveChanged] to reset everything.
     */
    public fun archiveChanged(): Boolean {
        if (!stateFile.isFile) return segmentsDir.listFiles()?.isNotEmpty() == true
        return readState().archiveFingerprint != fingerprint()
    }

    /**
     * Wipes the segmentsDir when the archive changed. Returns true if a
     * wipe happened (everything must be rebuilt).
     */
    public fun wipeIfArchiveChanged(): Boolean {
        if (!archiveChanged()) return false
        segmentsDir.listFiles()?.forEach { it.deleteRecursively() }
        writeState(BuildState(archiveFingerprint = fingerprint()))
        return true
    }

    /**
     * Ensures the buckets covering ([lon], [lat]) and any already-recorded
     * neighbors are built, building what is missing. Returns the buckets
     * that are now available for routing. [onTileScanned] rides every tile
     * the build scan visits (the search index's deep pass).
     */
    public suspend fun ensureBucketsFor(
        lon: Double,
        lat: Double,
        onTileScanned: ((zoom: Int, x: Int, y: Int, bytes: ByteArray) -> Unit)? = null,
        onProgress: (Progress) -> Unit = {},
    ): Set<String> {
        val buckets = listOf(GraphPipeline.bucketNameFor(lon, lat))
        return ensure(buckets, onTileScanned, onProgress)
    }

    /**
     * Builds every bucket the archive's bounding box touches ("prepare
     * all" in Settings). Ocean-only buckets are cheap and record as empty.
     */
    public suspend fun buildAll(
        onTileScanned: ((zoom: Int, x: Int, y: Int, bytes: ByteArray) -> Unit)? = null,
        onProgress: (Progress) -> Unit = {},
    ): Set<String> {
        PmtilesReader(archiveFile.absolutePath).use { reader ->
            val header = reader.header
            val buckets = HashSet<String>()
            var lon = GraphPipeline.bucketLonMinFor(header.minLon)
            while (lon <= header.maxLon) {
                var lat = GraphPipeline.bucketLatMinFor(header.minLat)
                while (lat <= header.maxLat) {
                    buckets.add(GraphPipeline.bucketName(lon, lat))
                    lat += GraphPipeline.BUCKET_DEGREES
                }
                lon += GraphPipeline.BUCKET_DEGREES
            }
            return ensure(buckets.toList(), onTileScanned, onProgress)
        }
    }

    /**
     * Stops after the bucket currently building finishes and publishes.
     * Only arms when a run is active: an armed flag between runs would
     * silently swallow the next build. (The service's own ACTION_CANCEL
     * guard against stale banners complements this.)
     */
    public fun cancel() {
        if (runActive) cancelled.set(true)
    }

    private suspend fun ensure(
        bucketNames: List<String>,
        onTileScanned: ((zoom: Int, x: Int, y: Int, bytes: ByteArray) -> Unit)? = null,
        onProgress: (Progress) -> Unit = {},
    ): Set<String> {
        runActive = true
        try {
            return coroutineScope {
                var state = readState()
                if (state.archiveFingerprint != fingerprint()) {
                    wipeIfArchiveChanged()
                    state = readState() // the wipe reset the buckets; re-read
                }
                val todo = bucketNames.filter { it !in state.buckets }
                // Same log channel the build phases use: the rebuild/no-op
                // decision is the pipeline's most consequential step.
                System.out.println(
                    "ensure: requested=$bucketNames built=${state.buckets.keys} todo=$todo",
                )
                val total = todo.size
                var built = 0
                for (bucket in todo) {
                    if (cancelled.get()) break
                    onProgress(Progress(bucket, built, total, building = true))
                    val result = buildOne(
                        bucket,
                        onTileScanned = onTileScanned,
                        onSubProgress = { label, phase, fraction ->
                            // Forwarded from the build thread: the sub-steps
                            // inside one bucket are minutes each, and the
                            // bucket-level ticks alone read as a stuck bar.
                            // Composed as (phase + step fraction) / PHASE_COUNT
                            // the bar only moves forward within a bucket —
                            // and is never null, so the UI can always draw a
                            // determinate bar mid-bucket.
                            val bucket_fraction =
                                (phase + (fraction ?: 0f)) / GraphPipeline.PHASE_COUNT
                            onProgress(
                                Progress(bucket, built, total, building = true, label, bucket_fraction),
                            )
                        },
                    )
                    built++
                    val fresh = readState() // re-read: a concurrent run may have written
                    fresh.buckets[bucket] = BucketState(
                        state = "built",
                        rd5 = result?.rd5File?.name,
                        nodeCount = result?.nodeCount ?: 0,
                    )
                    writeState(fresh)
                    onProgress(Progress(bucket, built, total, building = false))
                }
                readState().buckets.keys.toSet()
            }
        } finally {
            runActive = false
            cancelled.set(false)
        }
    }

    /**
     * Runs one bucket build on the default dispatcher. The vendored phases
     * are blocking CPU work, so the calling service should keep its own
     * progress reporting off this dispatcher.
     */
    private suspend fun buildOne(
        bucketName: String,
        onTileScanned: ((zoom: Int, x: Int, y: Int, bytes: ByteArray) -> Unit)? = null,
        onSubProgress: ((label: String, phase: Int, fraction: Float?) -> Unit)? = null,
    ): GraphPipeline.BuildResult? =
        withContext(Dispatchers.Default) {
            val (lonMin, latMin) = parseBucketName(bucketName)
            val workDir = File(workRoot, "build-$bucketName")
            try {
                PmtilesReader(archiveFile.absolutePath).use { reader ->
                    GraphPipeline(workDir).buildBucket(
                        reader = reader,
                        lookupFile = lookupFile(),
                        profileAllFile = profileAllFile(),
                        bucketLonMin = lonMin,
                        bucketLatMin = latMin,
                        segmentsDir = segmentsDir,
                        onTileScanned = onTileScanned,
                        onSubProgress = onSubProgress,
                    )
                }
            } finally {
                workDir.deleteRecursively()
            }
        }

    /**
     * Installs a user-supplied prebuilt routing-data ZIP: `<bucket>.rd5`
     * segments (plus an optional `lookups.dat`). This is the production
     * path — the ~30-minute on-device bucket build is the fallback for
     * users who supply no routing data.
     *
     * Order of operations is the safety story: every segment is extracted
     * to a scratch dir and integrity-checked with the runtime's own reader
     * BEFORE anything live is touched, so a corrupt or truncated ZIP fails
     * without leaving half-installed state. Only then are stale segments
     * from a previous archive wiped and the new files moved in with their
     * build-state records.
     *
     * The ZIP is arbitrary user-picked content, so extraction is bounded
     * against decompression bombs as it streams: `lookups.dat` is read
     * into memory with a hard byte cap (this runs on the UI-process
     * heap), rd5 entries stream to the scratch dir under a per-segment
     * cap, a free-space floor stops a disk-filling ZIP with a clean
     * message, and the segment count is bounded during extraction, not
     * only after it.
     *
     * A ZIP-declared `lookups.dat` that differs from the app's own asset
     * is rejected outright: segments built against different lookup
     * versions cannot be mixed with ones this app builds, and the engine
     * only discovers the mismatch at route time (an opaque "lookup"
     * failure far from the cause).
     *
     * Live-state safety on the copy: the build-state records for the
     * adopted buckets are dropped BEFORE the copies and re-added only
     * after every file has landed via a temp-file + atomic rename — a
     * torn copy (ENOSPC, process death) then leaves the bucket simply
     * "not built", so the next ensure()/route self-heals by rebuilding
     * it instead of a stale "built" record pointing at a truncated .rd5.
     */
    public suspend fun adoptPrebuiltSegments(zip: InputStream): Adoption =
        withContext(Dispatchers.IO) {
            val scratch = File(workRoot, ADOPT_SCRATCH_DIR)
            scratch.deleteRecursively()
            scratch.mkdirs()
            try {
                val segments = LinkedHashMap<String, File>()
                var zip_lookups: ByteArray? = null
                var zip_manifest: String? = null
                ZipInputStream(BufferedInputStream(zip)).use { input ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val name = entry.name.substringAfterLast('/')
                        when {
                            name == LOOKUPS_FILE -> {
                                // Not readBytes(): a crafted ZIP can carry a
                                // few-MB entry that decompresses to GBs
                                // (deflate ratio ~1032:1), and this read
                                // lands on the UI-process heap — the app's
                                // own lookups.dat is ~28 KB.
                                val out = java.io.ByteArrayOutputStream()
                                copyBounded(input, out, MAX_LOOKUPS_BYTES, LOOKUPS_FILE)
                                zip_lookups = out.toByteArray()
                            }
                            name == MANIFEST_FILE -> {
                                // Bounded for the same bomb reason; the
                                // real manifest is a fingerprint plus at
                                // most a few thousand bucket names (~100 KB).
                                val out = java.io.ByteArrayOutputStream()
                                copyBounded(input, out, MAX_MANIFEST_BYTES, MANIFEST_FILE)
                                zip_manifest = out.toString(Charsets.UTF_8)
                            }
                            RD5_ENTRY_RE.matchEntire(name) != null -> {
                                val bucket = name.removeSuffix(RD5_SUFFIX)
                                // parseBucketName throws on garbage, which is
                                // the point: a ZIP of random files must not
                                // install as routing data.
                                val (lon, lat) = parseBucketName(bucket)
                                val degrees = GraphPipeline.BUCKET_DEGREES
                                require(
                                    lon % degrees == 0 && lat % degrees == 0 &&
                                        lon in -180..175 && lat in -90..85,
                                ) { "$bucket is not a 5-degree bucket corner" }
                                // Bound the count while extracting, not
                                // after: a ZIP of millions of bogus-but-valid
                                // segment names must not stream to disk
                                // before the gate rejects it.
                                require(segments.size < MAX_ADOPT_BUCKETS) {
                                    "the routing data file holds more than " +
                                        "$MAX_ADOPT_BUCKETS segments — wrong file?"
                                }
                                // The same bound as the per-segment cap, but
                                // as a free-space floor: an extraction that
                                // would fill the partition fails here with an
                                // actionable message, not an opaque ENOSPC.
                                require(scratch.usableSpace > MIN_FREE_DISK_BYTES) {
                                    "not enough free storage to install the routing data"
                                }
                                val out = File(scratch, name)
                                out.outputStream().use { output ->
                                    copyBounded(input, output, MAX_SEGMENT_BYTES, "segment $bucket")
                                }
                                segments[bucket] = out
                            }
                            // Anything else (readme, checksums) is ignored.
                        }
                    }
                }
                require(segments.isNotEmpty() || zip_manifest != null) {
                    "the routing data file contains no Atlas .rd5 segments"
                }
                // The manifest gate — the reason a CI-built ZIP cannot mix
                // archives. Each daily run rebuilds a country's pmtiles
                // from a fresh extract, so a ZIP from a different day
                // describes roads the imported archive no longer has; the
                // engine would only discover that at route time. The
                // fingerprint check turns it into an actionable refusal.
                // (A hand-made ZIP without a manifest keeps the old
                // trust-the-user behavior.)
                var empty_from_manifest = emptyList<String>()
                if (zip_manifest != null) {
                    // (The !! is required: the extraction loop's lambda
                    // captured zip_manifest, so no smart cast applies.)
                    val (zip_fingerprint, empty_names) = parseRoutingManifest(zip_manifest!!)
                    require(zip_fingerprint == fingerprint()) {
                        "this routing data was built from a different map " +
                            "archive — use the routing ZIP from the same " +
                            "download as your map archive"
                    }
                    require(empty_names.size <= MAX_ADOPT_BUCKETS) {
                        "the routing data's manifest lists more than " +
                            "$MAX_ADOPT_BUCKETS buckets — wrong file?"
                    }
                    for (bucket in empty_names) {
                        // The same validation a .rd5 entry gets: garbage
                        // names must fail with the actionable message.
                        val (lon, lat) = parseBucketName(bucket)
                        require(
                            lon % GraphPipeline.BUCKET_DEGREES == 0 &&
                                lat % GraphPipeline.BUCKET_DEGREES == 0 &&
                                lon in -180..175 && lat in -90..85,
                        ) { "$bucket is not a 5-degree bucket corner" }
                    }
                    require(segments.keys.none { it in empty_names }) {
                        "the routing data's manifest lists a bucket that " +
                            "also carries a segment: wrong file?"
                    }
                    empty_from_manifest = empty_names
                }
                if (zip_lookups != null) {
                    require(zip_lookups.contentEquals(lookupFile().readBytes())) {
                        "the routing data was built with a different Atlas profile " +
                            "version — get one matching this app version"
                    }
                }
                // Validate every segment before mutating anything live: an
                // unreadable graph must not replace a working install.
                val scratch_source = FileMapSource(scratch)
                for ((bucket, file) in segments) {
                    val error = runCatching {
                        PhysicalFile.checkFileIntegrity(file.name, scratch_source)
                    }.getOrElse { "integrity check threw: ${it.message}" }
                    require(error == null) {
                        "segment $bucket is not a usable routing graph: $error"
                    }
                }

                wipeIfArchiveChanged()
                segmentsDir.mkdirs()
                // Drop the adopted buckets' records BEFORE copying: if the
                // copy tears (ENOSPC, process death) the bucket must read as
                // "not built", so ensure()/route self-heals by rebuilding it —
                // a stale "built" record over a truncated .rd5 fails only at
                // route time and nothing ever repairs it.
                val fresh = readState()
                for (bucket in segments.keys) fresh.buckets.remove(bucket)
                writeState(fresh)
                // Every live file lands via temp + atomic rename: a torn
                // copy must never be readable as a complete segment.
                for ((bucket, file) in segments) {
                    val live = File(segmentsDir, file.name)
                    val tmp = File(segmentsDir, "${file.name}$TMP_SUFFIX")
                    file.copyTo(tmp, overwrite = true)
                    if (!tmp.renameTo(live)) {
                        live.delete()
                        check(tmp.renameTo(live)) { "could not install segment $bucket" }
                    }
                }
                if (zip_lookups != null) {
                    val live = File(segmentsDir, LOOKUPS_FILE)
                    val tmp = File(segmentsDir, "$LOOKUPS_FILE$TMP_SUFFIX")
                    tmp.writeBytes(zip_lookups)
                    if (!tmp.renameTo(live)) {
                        live.delete()
                        check(tmp.renameTo(live)) { "could not install $LOOKUPS_FILE" }
                    }
                }
                val committed = readState()
                for (bucket in segments.keys) {
                    committed.buckets[bucket] = BucketState(
                        state = "built",
                        rd5 = "$bucket$RD5_SUFFIX",
                        nodeCount = 0, // informational; not parsed from rd5
                    )
                }
                // The manifest's empty buckets: recorded built with
                // rd5 = null, exactly like a bucket the on-device build
                // scanned and found empty — so no ensure() ever rescans
                // them. Only reached when the manifest's fingerprint
                // matched the imported archive above.
                for (bucket in empty_from_manifest) {
                    committed.buckets[bucket] = BucketState(
                        state = "built",
                        rd5 = null,
                        nodeCount = 0,
                    )
                }
                writeState(committed)
                Adoption(
                    buckets = segments.keys.toList(),
                    replacedLookups = zip_lookups != null,
                    emptyBuckets = empty_from_manifest,
                )
            } finally {
                scratch.deleteRecursively()
            }
        }

    // ---- state persistence (hand-rolled: the file holds only safe-ASCII
    // identifiers — bucket names, hex fingerprints, .rd5 file names — so no
    // escaping surface exists) ----

    private fun readState(): BuildState {
        if (!stateFile.isFile) return BuildState(archiveFingerprint = fingerprint())
        return runCatching { parseState(stateFile.readText()) }
            .getOrElse {
                // A swallowed parse failure reads as "nothing built yet" —
                // which rebuilds every bucket forever; say it happened.
                System.out.println("readState: parse failed: $it")
                BuildState(archiveFingerprint = fingerprint())
            }
    }

    /** Atomic: a torn write must never read back as "built". */
    private fun writeState(state: BuildState) {
        segmentsDir.mkdirs()
        val tmp = File(segmentsDir, "$STATE_FILE.tmp")
        tmp.writeText(renderState(state))
        if (!tmp.renameTo(stateFile)) {
            stateFile.delete()
            check(tmp.renameTo(stateFile)) { "could not persist $STATE_FILE" }
        }
    }

    // internal: exercised directly by the state round-trip test.
    internal fun renderState(state: BuildState): String = buildString {
        append("{\"archiveFingerprint\":\"").append(state.archiveFingerprint)
        append("\",\"buckets\":{")
        var first = true
        for ((name, bucket) in state.buckets) {
            if (!first) append(',')
            first = false
            append('"').append(name).append("\":{\"state\":\"")
            append(bucket.state)
            append("\",\"rd5\":")
            append(bucket.rd5?.let { "\"$it\"" } ?: "null")
            append(",\"nodeCount\":").append(bucket.nodeCount)
            append('}')
        }
        append("}}\n")
    }

    // internal: exercised directly by the state round-trip test.
    internal fun parseState(text: String): BuildState {
        val state = BuildState()
        val fingerprint = Regex("\"archiveFingerprint\":\"([0-9a-f]+)\"").find(text)
        state.archiveFingerprint = fingerprint?.groupValues?.get(1) ?: ""
        // Braces must be matched via character classes, not \\{ escapes:
        // the host tests run on OpenJDK's regex, which accepts \\{ as a
        // literal, but Android's java.util.regex is ICU-based and reads the
        // escape as an unclosed {n,m} interval — PatternSyntaxException at
        // Regex construction, silently swallowed by readState, and every
        // cold start rebuilt buckets the state file already recorded as
        // built. [{] and [}] are literal atoms in both engines.
        val bucket_re = Regex(
            "\"([EWN S0-9_]+)\":[{]\"state\":\"(\\w+)\"," +
                "\"rd5\":(null|\"[^\"]+\"),\"nodeCount\":(\\d+)[}]",
        )
        for (match in bucket_re.findAll(text)) {
            // The literal (unquoted) `null` is the documented empty-bucket
            // record: it must parse to Kotlin null, not to the string
            // "null" — the string would round-trip as "rd5":"null" and
            // permanently break the invariant.
            val token = match.groupValues[3]
            val rd5 = if (token == "null") null else token.removeSurrounding("\"").ifEmpty { null }
            state.buckets[match.groupValues[1]] = BucketState(
                state = match.groupValues[2],
                rd5 = rd5,
                nodeCount = match.groupValues[4].toInt(),
            )
        }
        return state
    }

    /** SHA-256 over the PMTiles header bytes plus the file length. */
    private fun fingerprint(): String = archiveFingerprintOf(archiveFile)

    private fun lookupFile(): File =
        assetFile(LOOKUPS_FILE) ?: error("missing asset $LOOKUPS_FILE")

    private fun profileAllFile(): File =
        assetFile(PROFILE_ALL) ?: error("missing asset $PROFILE_ALL")

    /**
     * Build assets (the reference `all.brf` and `lookups.dat`): in the
     * [assetsDir] when given, else next to the archive. The app extracts
     * them from APK assets at import time.
     */
    private fun assetFile(name: String): File? {
        val dir = assetsDir ?: archiveFile.parentFile
        File(dir, name).let { if (it.isFile) return it }
        return File(dir, "profiles/$name").takeIf { it.isFile }
    }

    private companion object {
        const val STATE_FILE = "build-state.json"
        const val LOOKUPS_FILE = "lookups.dat"
        const val PROFILE_ALL = "all.brf"
        const val RD5_SUFFIX = ".rd5"
        const val MANIFEST_FILE = "manifest.json"
        const val TMP_SUFFIX = ".tmp"
        const val ADOPT_SCRATCH_DIR = "adopt-scratch"
        const val MAX_ADOPT_BUCKETS = 2_592 // 72*36: every 5-degree bucket on Earth

        // Extraction bounds against decompression bombs (deflate expands
        // up to ~1032:1, so an unbounded read is a memory/disk-fill bomb
        // in a ZIP of a few MB). The real assets sit far below: lookups
        // is ~28 KB, the manifest is a fingerprint plus at most a few
        // thousand bucket names, the densest 5° rd5 segment is hundreds
        // of MB.
        const val MAX_LOOKUPS_BYTES = 1L shl 20 // 1 MB
        const val MAX_MANIFEST_BYTES = 4L shl 20 // 4 MB
        const val MAX_SEGMENT_BYTES = 2_000_000_000L
        const val MIN_FREE_DISK_BYTES = 256L shl 20 // leave 256 MB free

        // A legal routing-data ZIP entry: exactly "<bucket>.rd5" at the
        // root (any deeper path is rejected — no traversal into subdirs).
        val RD5_ENTRY_RE = Regex("^[EW]\\d+_[NS]\\d+\\.rd5$")

        fun parseBucketName(name: String): Pair<Int, Int> {
            val match = Regex("^([EW])(\\d+)_([NS])(\\d+)$").matchEntire(name)
                ?: error("bad bucket name: $name")
            // toIntOrNull keeps the error user-presentable: toInt() on a
            // huge digit run would leak "For input string: ..." into a
            // toast instead of the actionable bucket-name message.
            fun number(digits: String): Int =
                digits.toIntOrNull() ?: error("bad bucket name: $name")
            val lon = number(match.groupValues[2]) * if (match.groupValues[1] == "W") -1 else 1
            val lat = number(match.groupValues[4]) * if (match.groupValues[3] == "S") -1 else 1
            return lon to lat
        }

    }
}

/**
 * SHA-256 over the archive's 127-byte PMTiles header plus its file length —
 * the identity `build-state.json` and the routing ZIP's manifest key on.
 * Top-level so the CI-side build CLI (test source set) can mint manifests
 * without instantiating a manager.
 */
internal fun archiveFingerprintOf(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val header = ByteArray(ARCHIVE_HEADER_BYTES)
        // readFully semantics: a single InputStream.read may short-read,
        // and a short header would silently hash a shifted window.
        var read = 0
        while (read < ARCHIVE_HEADER_BYTES) {
            val n = input.read(header, read, ARCHIVE_HEADER_BYTES - read)
            if (n < 0) error("archive too small: $file")
            read += n
        }
        digest.update(header, 0, read)
    }
    digest.update(file.length().toString().toByteArray())
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private const val ARCHIVE_HEADER_BYTES = 127

/**
 * The manifest a CI-built routing ZIP carries (see
 * [GraphBuildManager.adoptPrebuiltSegments]): a safe-ASCII, hand-rolled-JSON
 * pair of the archive fingerprint the segments were built from and the
 * buckets that scanned empty. Both fields are load-bearing — the fingerprint
 * stops a ZIP minted from a different daily archive from installing
 * silently-wrong roads, and the empty list saves the on-device build from
 * rescanning every ocean cell.
 *
 * internal: the CI-side build CLI (test source set) mints manifests with
 * [renderRoutingManifest] and tests round-trip the pair.
 */
internal fun parseRoutingManifest(text: String): Pair<String, List<String>> {
    val fingerprint_match =
        Regex("\"archiveFingerprint\":\"([0-9a-f]+)\"").find(text)
            ?: error("the routing data's manifest has no archive fingerprint")
    val fingerprint = fingerprint_match.groupValues[1]
    require(fingerprint.length == 64) {
        "the routing data's manifest has a malformed archive fingerprint"
    }
    val array_match = Regex("\"empty\":\\[([^]]*)]").find(text)
        ?: error("the routing data's manifest has no empty-bucket list")
    val empty = ArrayList<String>()
    for (match in Regex("\"([^\"]+)\"").findAll(array_match.groupValues[1])) {
        empty.add(match.groupValues[1])
    }
    return fingerprint to empty
}

/** Renders what [parseRoutingManifest] reads — see its doc. */
internal fun renderRoutingManifest(fingerprint: String, emptyBuckets: List<String>): String =
    "{\"format\":1,\"archiveFingerprint\":\"$fingerprint\",\"empty\":" +
        emptyBuckets.joinToString(",", "[", "]") { "\"$it\"" } + "}\n"

/**
 * Copies [input] to [output], refusing after [capBytes] bytes. Used on
 * every branch of the routing-ZIP extraction that consumes attacker-
 * controlled bytes; [what] names the entry in the user-facing message.
 *
 * internal: exercised directly by the extraction-bound tests.
 */
internal fun copyBounded(
    input: InputStream,
    output: java.io.OutputStream,
    capBytes: Long,
    what: String,
): Long {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) return total
        total += n
        require(total <= capBytes) { "$what expands past $capBytes bytes — wrong file?" }
        output.write(buffer, 0, n)
    }
}