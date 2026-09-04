package com.danemadsen.atlas.graph

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GraphBuildManager` state semantics: build once, skip when built, wipe
 * on archive change, stop cleanly on cancel. The one real bucket build
 * reuses the melbourne fixture (bucket E140_S40).
 */
class GraphBuildManagerTest {

    @Test
    fun buildsRecordsSkipsAndWipes() = runBlocking {
        val archive = File("src/test/fixtures/melbourne.pmtiles")
        if (!archive.isFile) {
            println("skipping: no fixture")
            return@runBlocking
        }
        val profiles = findProfileDir()
        val root = createTempDirectory("atlas-manager-").toFile()
        val segments = File(root, "segments").apply { mkdirs() }
        val work = File(root, "work")
        val manager = GraphBuildManager(
            archiveFile = archive,
            segmentsDir = segments,
            workRoot = work,
            assetsDir = profiles,
        )

        // -- build on first need, with progress callbacks --
        val progress = ArrayList<String>()
        val built = manager.ensureBucketsFor(144.9669, -37.8183) { p ->
            progress.add("${p.bucket}:${p.building}:${p.built}/${p.total}")
        }
        assertTrue("E140_S40" in built, "expected E140_S40 in $built")
        val rd5 = File(segments, "E140_S40.rd5")
        assertTrue(rd5.isFile, "published rd5 missing")
        assertTrue(rd5.length() > 1_000_000, "suspiciously small rd5")
        assertTrue(progress.any { it.startsWith("E140_S40:true:") })
        assertTrue(progress.any { it.startsWith("E140_S40:false:") })
        assertTrue(File(segments, "lookups.dat").isFile, "lookups copy missing")
        assertFalse(manager.archiveChanged())
        // Captured before the wipe below: the prebuilt-segments adoption
        // at the end of this test reuses this exact rd5.
        val built_rd5_bytes = rd5.readBytes()

        // -- second need: already built, no rebuild, no progress --
        val mtime = rd5.lastModified()
        val again = manager.ensureBucketsFor(144.9, -37.8)
        assertEquals(built, again)
        assertEquals(mtime, rd5.lastModified())
        val rd5s_before = segments.listFiles()!!.count { it.name.endsWith(".rd5") }
        assertEquals(1, rd5s_before)

        // -- a different archive invalidates everything --
        val state_file = File(segments, "build-state.json")
        val corrupted = state_file.readText()
            .replace(Regex("\"archiveFingerprint\":\"[0-9a-f]+\""), "\"archiveFingerprint\":\"deadbeef\"")
        state_file.writeText(corrupted)
        assertTrue(manager.archiveChanged())
        assertTrue(manager.wipeIfArchiveChanged())
        assertFalse(rd5.isFile, "wipe left the stale rd5 behind")
        assertTrue(manager.builtBuckets().isEmpty())
        assertFalse(manager.archiveChanged()) // state now matches again

        // -- cancel between runs is a no-op: an armed flag between runs
        //    would silently swallow the next build. An all-ocean bucket is
        //    the cheap target, and its state record doubles as the
        //    rd5=null empty-bucket invariant check. --
        manager.cancel()
        val ocean = manager.ensureBucketsFor(142.0, -42.0)
        assertTrue("E140_S45" in ocean, "cancel between runs swallowed the build")
        assertTrue(
            state_file.readText().contains(
                "\"E140_S45\":{\"state\":\"built\",\"rd5\":null,\"nodeCount\":0}",
            ),
            "empty bucket not recorded with rd5: null",
        )

        // -- prebuilt routing-data adoption: the production import path.
        //    The E140_S40 rd5 captured above (before the wipe) comes back
        //    through a ZIP and must land as an already-built bucket. The
        //    manifest (the CI ZIP's fingerprint gate) carries a matching
        //    fingerprint plus one empty bucket, which must be recorded
        //    built — that is what stops the location trigger from
        //    re-scanning ocean a CI build already scanned. --
        val zip_bytes = zipOf(
            "E140_S40.rd5" to built_rd5_bytes,
            "lookups.dat" to File(profiles, "lookups.dat").readBytes(),
            "manifest.json" to renderRoutingManifest(
                archiveFingerprintOf(archive),
                emptyBuckets = listOf("E145_S40"),
            ).toByteArray(),
        )
        val adoption = manager.adoptPrebuiltSegments(zip_bytes.inputStream())
        assertEquals(listOf("E140_S40"), adoption.buckets)
        assertEquals(listOf("E145_S40"), adoption.emptyBuckets)
        assertTrue(adoption.replacedLookups)
        assertTrue(File(segments, "E140_S40.rd5").isFile, "adopted rd5 missing")
        assertEquals(
            "E140_S40.rd5",
            manager.bucketState("E140_S40")?.rd5,
            "adopted bucket not recorded as built",
        )
        assertNull(
            manager.bucketState("E145_S40")?.rd5,
            "manifest empty bucket not recorded with rd5: null",
        )
        assertTrue("E145_S40" in manager.builtBuckets(), "manifest empty bucket not recorded built")
        // The ocean bucket's record survived alongside the adoption, and a
        // route-triggering ensure over the adopted area is a no-op — it
        // returns every bucket now recorded built, which includes the
        // manifest's empty one.
        assertNull(manager.bucketState("E140_S45")?.rd5, "adoption clobbered the empty-bucket record")
        assertEquals(
            setOf("E140_S40", "E140_S45", "E145_S40"),
            manager.ensureBucketsFor(144.9, -37.8),
        )

        // -- a hand-made ZIP with NO manifest keeps the documented
        //    trust-the-user path: same segments, no gate, no empties. --
        val no_manifest = manager.adoptPrebuiltSegments(zipOf(
            "E140_S40.rd5" to built_rd5_bytes,
            "lookups.dat" to File(profiles, "lookups.dat").readBytes(),
        ).inputStream())
        assertEquals(listOf("E140_S40"), no_manifest.buckets)
        assertTrue(no_manifest.emptyBuckets.isEmpty(), "manifest-less ZIP invented empty buckets")

        root.deleteRecursively()
    }

    /**
     * Adoption rejection paths — none of these may leave any state or file
     * behind. Garbage segments fail the runtime's integrity check, a
     * mismatched lookups.dat is refused outright (segments built against
     * other lookup versions would only fail at route time), and a ZIP
     * without .rd5 entries is not routing data at all.
     */
    @Test
    fun adoptionRejectsGarbageWithoutSideEffects() {
        runBlocking {
            val root = createTempDirectory("atlas-manager-adopt-").toFile()
            val profiles = findProfileDir()
            val segments = File(root, "segments").apply { mkdirs() }
            // The rejections below never write state, so every assertion's
            // builtBuckets() must fingerprint the archive from scratch —
            // a nonexistent file would throw instead of proving "no state".
            val archive = File(root, "atlas.pmtiles").apply { writeBytes(ByteArray(1024)) }
            val manager = GraphBuildManager(
                archiveFile = archive,
                segmentsDir = segments,
                workRoot = File(root, "work"),
                assetsDir = profiles,
            )
            suspend fun adopt(bytes: ByteArray) =
                runCatching { manager.adoptPrebuiltSegments(bytes.inputStream()) }

            // garbage segment content: integrity check refuses it
            val garbage = adopt(zipOf("E140_S40.rd5" to ByteArray(50_000) { (it % 251).toByte() }))
            assertTrue(garbage.isFailure, "garbage segment accepted")
            assertTrue(segments.listFiles().isNullOrEmpty(), "rejected adoption left files behind")
            assertTrue(manager.builtBuckets().isEmpty(), "rejected adoption left state behind")

            // a corner that is not a multiple of 5 is not a bucket — the
            // message pins THIS gate: the garbage bytes would also fail
            // the later integrity check, so isFailure alone is masked.
            val bad_corner = adopt(zipOf("E142_S40.rd5" to ByteArray(100)))
            assertTrue(bad_corner.isFailure, "non-5-degree corner accepted")
            assertTrue(
                "E142_S40 is not a 5-degree bucket corner" in
                    (bad_corner.exceptionOrNull()?.message ?: ""),
                "corner gate did not produce the actionable message",
            )

            // mismatched lookups.dat: refused before anything is touched
            val mismatched = adopt(zipOf(
                "E140_S40.rd5" to ByteArray(50_000),
                "lookups.dat" to "not the real lookup table".toByteArray(),
            ))
            assertTrue(mismatched.isFailure, "mismatched lookups accepted")
            assertTrue(
                "different Atlas profile" in (mismatched.exceptionOrNull()?.message ?: ""),
                "mismatched-lookups message is not user-actionable",
            )

            // no .rd5 entries at all
            val empty = adopt(zipOf("readme.txt" to "hello".toByteArray()))
            assertTrue(empty.isFailure, "zip without segments accepted")

            root.deleteRecursively()
        }
    }

    /**
     * The manifest gate — the reason a CI-built routing ZIP cannot mix
     * archives. The manifest's fingerprint must match THIS archive (the
     * actionable refusal, not a route-time surprise), its bucket list must
     * be sane, and a manifest-only ZIP (every bucket scanned empty: an
     * island-only archive) is a legitimate install that records its empties
     * without touching any file.
     */
    @Test
    fun adoptionManifestGate() {
        runBlocking {
            val root = createTempDirectory("atlas-manager-manifest-").toFile()
            val profiles = findProfileDir()
            val segments = File(root, "segments").apply { mkdirs() }
            val archive = File(root, "atlas.pmtiles").apply { writeBytes(ByteArray(1024)) }
            val manager = GraphBuildManager(
                archiveFile = archive,
                segmentsDir = segments,
                workRoot = File(root, "work"),
                assetsDir = profiles,
            )
            val fingerprint = archiveFingerprintOf(archive)
            suspend fun adopt(bytes: ByteArray) =
                runCatching { manager.adoptPrebuiltSegments(bytes.inputStream()) }
            fun manifestBytes(fingerprint: String, empty: List<String>) =
                renderRoutingManifest(fingerprint, empty).toByteArray()

            // fingerprint for a different archive: the actionable refusal
            val wrong_archive = adopt(zipOf(
                "E140_S40.rd5" to ByteArray(50_000),
                "manifest.json" to manifestBytes("f".repeat(64), listOf("E145_S40")),
            ))
            assertTrue(wrong_archive.isFailure, "manifest for another archive accepted")
            val wrong_message = wrong_archive.exceptionOrNull()?.message ?: ""
            assertTrue(
                "different map archive" in wrong_message,
                "fingerprint mismatch is not user-actionable: $wrong_message",
            )
            assertTrue(segments.listFiles().isNullOrEmpty(), "refused adoption left files behind")
            assertTrue(manager.builtBuckets().isEmpty(), "refused adoption left state behind")

            // a manifest without a fingerprint, or with a malformed one
            val no_fingerprint = adopt(zipOf(
                "manifest.json" to "{\"format\":1,\"empty\":[]}".toByteArray(),
            ))
            assertTrue(no_fingerprint.isFailure, "manifest without a fingerprint accepted")
            assertTrue(
                "manifest" in (no_fingerprint.exceptionOrNull()?.message ?: ""),
                "missing-fingerprint message is not user-actionable",
            )
            val short_fingerprint = adopt(zipOf(
                "manifest.json" to manifestBytes("deadbeef", emptyList()),
            ))
            assertTrue(short_fingerprint.isFailure, "short manifest fingerprint accepted")

            // an empty bucket that also carries a segment: a broken ZIP
            val both = adopt(zipOf(
                "E140_S40.rd5" to ByteArray(50_000),
                "manifest.json" to manifestBytes(fingerprint, listOf("E140_S40")),
            ))
            assertTrue(both.isFailure, "empty bucket listed alongside its own segment accepted")

            // an empty-bucket name that is not a 5-degree corner
            val bad_corner = adopt(zipOf(
                "manifest.json" to manifestBytes(fingerprint, listOf("E142_S40")),
            ))
            assertTrue(bad_corner.isFailure, "non-5-degree empty bucket accepted")

            // more empties than every 5-degree bucket on Earth (2592):
            // the same wrong-file guard the segment count gets. All names
            // are VALID corners (every one plus a duplicate) so the range
            // check cannot mask the count gate — and the message pins
            // which gate refused.
            val every_corner = buildList {
                for (lon in -180..175 step 5) {
                    for (lat in -90..85 step 5) add(GraphPipeline.bucketName(lon, lat))
                }
            }
            val too_many = adopt(zipOf(
                "manifest.json" to manifestBytes(
                    fingerprint,
                    every_corner + every_corner.first(),
                ),
            ))
            assertTrue(too_many.isFailure, "absurd manifest accepted")
            assertTrue(
                "wrong file" in (too_many.exceptionOrNull()?.message ?: ""),
                "count gate did not refuse the manifest: ${too_many.exceptionOrNull()?.message}",
            )

            // the legitimate edge: a manifest-only ZIP (nothing but empty
            // buckets) installs and records them built with rd5: null
            val empties = adopt(zipOf(
                "manifest.json" to manifestBytes(fingerprint, listOf("E140_S45", "E145_S40")),
            ))
            assertTrue(empties.isSuccess, "manifest-only ZIP refused: ${empties.exceptionOrNull()}")
            val adoption = empties.getOrThrow()
            assertTrue(adoption.buckets.isEmpty())
            assertEquals(listOf("E140_S45", "E145_S40"), adoption.emptyBuckets)
            assertNull(manager.bucketState("E140_S45")?.rd5, "empty bucket recorded with an rd5")
            assertTrue("E140_S45" in manager.builtBuckets(), "empty bucket not recorded built")
            assertNull(manager.bucketState("E145_S40")?.rd5, "second empty bucket recorded with an rd5")

            root.deleteRecursively()
        }
    }

    /**
     * The extraction bounds against decompression bombs and malformed
     * names: a lookups.dat larger than the memory cap is refused (this
     * read lands on the UI-process heap, and a crafted few-MB ZIP entry
     * can expand to GBs), a bucket name with an unparseable number fails
     * with the actionable bucket-name message instead of leaking
     * `For input string: ...`, and the bounded copy itself enforces its
     * cap while passing under-cap bytes through untouched.
     */
    @Test
    fun adoptionRejectsBombsAndBadNumbers() {
        runBlocking {
            val root = createTempDirectory("atlas-manager-bomb-").toFile()
            val profiles = findProfileDir()
            val segments = File(root, "segments").apply { mkdirs() }
            val archive = File(root, "atlas.pmtiles").apply { writeBytes(ByteArray(1024)) }
            val manager = GraphBuildManager(
                archiveFile = archive,
                segmentsDir = segments,
                workRoot = File(root, "work"),
                assetsDir = profiles,
            )
            suspend fun adopt(bytes: ByteArray) =
                runCatching { manager.adoptPrebuiltSegments(bytes.inputStream()) }

            // lookups.dat past the 1 MB memory cap: rejected while reading,
            // before anything is compared or installed
            val bomb = adopt(zipOf("lookups.dat" to ByteArray(2 shl 20)))
            assertTrue(bomb.isFailure, "oversized lookups.dat accepted")
            assertTrue(
                "lookups.dat" in (bomb.exceptionOrNull()?.message ?: ""),
                "lookups bomb message is not user-actionable",
            )
            assertTrue(segments.listFiles().isNullOrEmpty(), "rejected bomb left files behind")

            // manifest.json past its own 4 MB cap: same bomb shape, same
            // bounded read — deleting the cap would stream the entry whole
            // onto the UI-process heap, and this test would still pass.
            val manifest_bomb = adopt(zipOf("manifest.json" to ByteArray((4 shl 20) + 1)))
            assertTrue(manifest_bomb.isFailure, "oversized manifest accepted")
            assertTrue(
                "manifest.json" in (manifest_bomb.exceptionOrNull()?.message ?: ""),
                "manifest bomb message is not user-actionable",
            )
            assertTrue(segments.listFiles().isNullOrEmpty(), "rejected manifest bomb left files behind")

            // more .rd5 entries than every 5-degree bucket on Earth
            // (2592): the extraction-time count gate, with its message.
            // All names are valid corners, and the 2593rd — a NESTED
            // duplicate, which flattens to the same bucket key but is a
            // distinct ZIP entry — trips the count require BEFORE the map
            // put, so the corner gate cannot mask it. Tiny entries keep
            // the ZIP small; integrity never runs.
            val corner_entries = ArrayList<Pair<String, ByteArray>>()
            for (lon in -180..175 step 5) {
                for (lat in -90..85 step 5) {
                    corner_entries.add(GraphPipeline.bucketName(lon, lat) + ".rd5" to ByteArray(8))
                }
            }
            // (corner_entries.first().first already ends in ".rd5".)
            corner_entries.add("nested/" + corner_entries.first().first to ByteArray(8))
            val too_many_entries = adopt(zipOf(*corner_entries.toTypedArray()))
            assertTrue(too_many_entries.isFailure, "absurd segment count accepted")
            val count_message = too_many_entries.exceptionOrNull()?.message ?: ""
            assertTrue(
                "wrong file" in count_message,
                "segment count gate did not refuse the ZIP: $count_message",
            )

            // a bucket corner with a number too large to parse: the same
            // actionable "bad bucket name" message, not a raw toInt() error
            val bad_number = adopt(zipOf("E99999999999999999999_N0.rd5" to ByteArray(100)))
            assertTrue(bad_number.isFailure, "unparseable bucket number accepted")
            val number_message = bad_number.exceptionOrNull()?.message ?: ""
            assertTrue(
                "bad bucket name" in number_message && "For input string" !in number_message,
                "unparseable number leaked a raw toInt() error: $number_message",
            )

            // the bounded copy: under the cap it is a plain copy, over the
            // cap it refuses with the what-named message
            val under = java.io.ByteArrayOutputStream()
            val copied = copyBounded(
                "0123456789".byteInputStream(), under, capBytes = 100, what = "test entry",
            )
            assertEquals(10L, copied)
            assertEquals("0123456789", under.toString(Charsets.US_ASCII))
            val over = runCatching {
                copyBounded(
                    java.io.ByteArrayInputStream(ByteArray(1_000)),
                    java.io.ByteArrayOutputStream(),
                    capBytes = 100,
                    what = "test entry",
                )
            }
            assertTrue(over.isFailure, "bounded copy accepted over-cap input")
            assertTrue(
                "test entry" in (over.exceptionOrNull()?.message ?: ""),
                "bounded copy message does not name the entry",
            )

            root.deleteRecursively()
        }
    }

    /** A minimal in-memory ZIP of name -> bytes entries. */
    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * The state file's empty-bucket record (`"rd5":null`, unquoted) must
     * survive the render -> parse -> render round-trip untouched — parsing
     * it into the literal string "null" would re-persist as "rd5":"null"
     * and permanently break the invariant.
     */
    @Test
    fun emptyBucketStateRoundTripsAsNull() {
        val root = createTempDirectory("atlas-manager-roundtrip-").toFile()
        val manager = GraphBuildManager(
            archiveFile = File(root, "atlas.pmtiles"), // never read: the state file exists
            segmentsDir = File(root, "segments").apply { mkdirs() },
            workRoot = File(root, "work"),
            assetsDir = null,
        )
        val state = GraphBuildManager.BuildState(
            archiveFingerprint = "deadbeef",
            buckets = mutableMapOf(
                "E140_S40" to GraphBuildManager.BucketState(state = "built", rd5 = "E140_S40.rd5", nodeCount = 1_000_000),
                "E140_S45" to GraphBuildManager.BucketState(state = "built", rd5 = null, nodeCount = 0),
            ),
        )

        val rendered = manager.renderState(state)
        assertTrue("\"rd5\":null" in rendered, "render must emit unquoted null for an empty bucket")

        val reparsed = manager.parseState(rendered)
        assertNull(reparsed.buckets["E140_S45"]?.rd5, "literal null parsed as the string \"null\"")
        assertEquals("E140_S40.rd5", reparsed.buckets["E140_S40"]?.rd5)

        // re-writing the parsed state keeps the null
        val re_rendered = manager.renderState(reparsed)
        assertEquals(rendered, re_rendered)

        // and through the on-disk path: write, re-read via the manager, re-write
        val state_file = File(root, "segments/build-state.json")
        state_file.writeText(rendered)
        assertTrue("E140_S45" in manager.builtBuckets(), "empty bucket lost on re-read")
        assertEquals(rendered, manager.renderState(manager.parseState(state_file.readText())))

        root.deleteRecursively()
    }

    private fun findProfileDir(): File {
        val candidates = listOf(
            File(System.getProperty("user.dir"), "src/main/kotlin/com/danemadsen/atlas/beerouter/profiles2"),
            File(System.getProperty("user.dir"), "misc/profiles2"),
        )
        return candidates.firstOrNull(File::isDirectory)
            ?: error("could not find profiles2")
    }
}