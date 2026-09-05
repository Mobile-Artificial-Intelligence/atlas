package com.danemadsen.atlas.graph

import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The CI ZIP contract: [GraphBuildCli.writeRoutingZip] must mint exactly
 * what [GraphBuildManager.adoptPrebuiltSegments] installs — every `.rd5`
 * at the root (sorted, byte-identical), the `lookups.dat` the segments
 * were built against, and the manifest whose fingerprint gate and
 * empty-bucket list the adoption path parses back. A mismatch between
 * mint and adopt only surfaces on a user's device, so this test is the
 * pairing's only executable proof.
 */
class GraphBuildCliTest {

    @Test
    fun routingZipLayoutCarriesSegmentsLookupsAndManifest() {
        val root = createTempDirectory("atlas-cli-zip-").toFile()
        val segments = File(root, "segments").apply { mkdirs() }
        // Two buckets, one .empty resume marker, the lookups table, plus
        // files the ZIP must NOT carry (the marker itself, stray notes).
        val rd5_west = ByteArray(64) { it.toByte() }
        val rd5_east = ByteArray(32) { (it * 3).toByte() }
        File(segments, "W170_S65.rd5").writeBytes(rd5_west)
        File(segments, "E010_S20.rd5").writeBytes(rd5_east)
        File(segments, "E005_S45.empty").writeText("")
        val lookups = ByteArray(128) { (it % 7).toByte() }
        File(segments, "lookups.dat").writeBytes(lookups)
        File(segments, "notes.txt").writeText("not routing data")

        val zip_file = File(root, "atlas-routing-test.zip")
        GraphBuildCli.writeRoutingZip(segments, zip_file, "a".repeat(64))

        ZipFile(zip_file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertEquals(
                listOf("E010_S20.rd5", "W170_S65.rd5", "lookups.dat", "manifest.json"),
                names,
                "the ZIP must carry exactly the segments, lookups, and manifest, rd5s sorted",
            )
            fun entryBytes(name: String) = zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
            // contentEquals, not assertEquals: kotlin.test's assertEquals on
            // arrays compares references.
            assertTrue(rd5_east.contentEquals(entryBytes("E010_S20.rd5")), "segment bytes not preserved")
            assertTrue(rd5_west.contentEquals(entryBytes("W170_S65.rd5")), "segment bytes not preserved")
            assertTrue(lookups.contentEquals(entryBytes("lookups.dat")), "lookups bytes not preserved")

            // The manifest reads back as the gate expects: this archive's
            // fingerprint, and the .empty markers' buckets (and only
            // those — the stray files and the real segments must not leak
            // into the list).
            val (fingerprint, empty) = parseRoutingManifest(
                entryBytes("manifest.json").toString(Charsets.UTF_8),
            )
            assertEquals("a".repeat(64), fingerprint)
            assertEquals(listOf("E005_S45"), empty)
        }

        root.deleteRecursively()
    }

    /**
     * The all-mode contract: [GraphBuildCli.writeRoutingManifest] drops the
     * manifest NEXT TO the segments, because CI uploads the out dir as the
     * routing artifact (upload-artifact zips it). The manifest must read
     * back exactly as the adoption gate expects — same fingerprint, empty
     * list from the .empty markers and nothing else.
     */
    @Test
    fun dirManifestMakesTheOutDirAdoptable() {
        val root = createTempDirectory("atlas-cli-dir-").toFile()
        val segments = File(root, "segments").apply { mkdirs() }
        File(segments, "E010_S20.rd5").writeBytes(ByteArray(16))
        File(segments, "E005_S45.empty").writeText("")
        File(segments, "E150_S30.empty").writeText("")
        File(segments, "lookups.dat").writeBytes(ByteArray(8))

        GraphBuildCli.writeRoutingManifest(segments, "ab".repeat(32))

        val manifest = File(segments, "manifest.json").readText()
        val (fingerprint, empty) = parseRoutingManifest(manifest)
        assertEquals("ab".repeat(32), fingerprint)
        assertEquals(listOf("E005_S45", "E150_S30"), empty)

        root.deleteRecursively()
    }

    /**
     * Render -> parse must round-trip both fields, and the malformed
     * manifests the adoption gate must refuse (no fingerprint, short hex,
     * no empty array) fail with the gate's own messages.
     */
    @Test
    fun manifestRenderParseRoundTripAndRejections() {
        val fingerprint = "ab".repeat(32)
        val rendered = renderRoutingManifest(fingerprint, listOf("E140_S40", "W170_S65"))
        val parsed = parseRoutingManifest(rendered)
        assertEquals(fingerprint, parsed.first)
        assertEquals(listOf("E140_S40", "W170_S65"), parsed.second)

        // empty list renders as [] and parses back empty
        val none = parseRoutingManifest(renderRoutingManifest(fingerprint, emptyList()))
        assertEquals(fingerprint, none.first)
        assertEquals(emptyList(), none.second)

        // rejections, each with the user-actionable phrasing
        val no_fingerprint = runCatching { parseRoutingManifest("{\"format\":1,\"empty\":[]}") }
        assertTrue(no_fingerprint.isFailure, "manifest without a fingerprint parsed")
        assertTrue(
            "no archive fingerprint" in (no_fingerprint.exceptionOrNull()?.message ?: ""),
            "missing-fingerprint message is not user-actionable",
        )
        val short = runCatching { parseRoutingManifest(renderRoutingManifest("deadbeef", emptyList())) }
        assertTrue(short.isFailure, "short fingerprint parsed")
        assertTrue(
            "malformed archive fingerprint" in (short.exceptionOrNull()?.message ?: ""),
            "short-fingerprint message is not user-actionable",
        )
        val no_array = runCatching {
            parseRoutingManifest("{\"format\":1,\"archiveFingerprint\":\"$fingerprint\"}")
        }
        assertTrue(no_array.isFailure, "manifest without an empty list parsed")
        assertTrue(
            "no empty-bucket list" in (no_array.exceptionOrNull()?.message ?: ""),
            "missing-array message is not user-actionable",
        )
    }
}