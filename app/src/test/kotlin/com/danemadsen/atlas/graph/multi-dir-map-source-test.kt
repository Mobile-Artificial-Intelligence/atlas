package com.danemadsen.atlas.graph

import java.io.File
import java.io.FileOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [MultiDirMapSource]: the engine's single-map view over several regions'
 * segment dirs. The contract under test is the one the routing engine
 * depends on — `exists` and `open` agree, and the first dir holding a
 * bucket wins deterministically.
 */
class MultiDirMapSourceTest {

    /** A bucket file is found in any dir and opened to the right bytes. */
    @Test
    fun resolvesAcrossDirs() {
        val dir_a = createTempDirectory("atlas-mms-a-").toFile()
        val dir_b = createTempDirectory("atlas-mms-b-").toFile()
        writeBucket(dir_a, "E10_S30.rd5", "bucket-a")
        writeBucket(dir_b, "E15_S35.rd5", "bucket-b")

        val source = MultiDirMapSource(listOf(dir_a, dir_b))
        assertTrue(source.exists("E10_S30.rd5"))
        assertTrue(source.exists("E15_S35.rd5"))
        assertFalse(source.exists("E20_S40.rd5"))

        // Open returns a readable reader over the resolved file.
        val reader = source.open("E10_S30.rd5")
        try {
            assertEquals("bucket-a".length.toLong(), reader.length())
            val bytes = ByteArray("bucket-a".length)
            reader.readFully(bytes, 0, bytes.size)
            assertEquals("bucket-a", String(bytes))
            reader.close()
        } finally {
            reader.close()
        }
    }

    /** First dir wins: a bucket present in both resolves to dir A's copy. */
    @Test
    fun firstDirPrecedence() {
        val dir_a = createTempDirectory("atlas-mms-a-").toFile()
        val dir_b = createTempDirectory("atlas-mms-b-").toFile()
        writeBucket(dir_a, "E0_S0.rd5", "from-a")
        writeBucket(dir_b, "E0_S0.rd5", "from-b")

        val source = MultiDirMapSource(listOf(dir_a, dir_b))
        val reader = source.open("E0_S0.rd5")
        try {
            val bytes = ByteArray("from-a".length)
            reader.readFully(bytes, 0, bytes.size)
            assertEquals("from-a", String(bytes))
        } finally {
            reader.close()
        }
    }

    /**
     * `exists` and `open` agree in both directions — the engine's contract
     * is that it can never see a bucket exist then fail to open, or open
     * one that exists() denied.
     */
    @Test
    fun existsAndOpenAgree() {
        val dir_a = createTempDirectory("atlas-mms-a-").toFile()
        val dir_b = createTempDirectory("atlas-mms-b-").toFile()
        writeBucket(dir_b, "W5_N10.rd5", "only-in-b")

        val source = MultiDirMapSource(listOf(dir_a, dir_b))
        // exists true -> open succeeds (the file lives in the second dir).
        assertTrue(source.exists("W5_N10.rd5"))
        source.open("W5_N10.rd5").close()
        // exists false -> open fails with FileNotFoundException.
        assertFalse(source.exists("E1_E1.rd5"))
        assertIs<java.io.FileNotFoundException>(runCatching { source.open("E1_E1.rd5") }.exceptionOrNull())
    }

    /** A bucket that exists only as a directory must not route. */
    @Test
    fun ignoresDirectoriesNamedLikeBuckets() {
        val dir_a = createTempDirectory("atlas-mms-a-").toFile()
        File(dir_a, "E10_S30.rd5").mkdirs()
        val source = MultiDirMapSource(listOf(dir_a))
        assertFalse(source.exists("E10_S30.rd5"))
        assertNotNull(runCatching { source.open("E10_S30.rd5") }.exceptionOrNull())
    }

    /** An empty source is a caller bug: fail at construction, not mid-route. */
    @Test
    fun rejectsEmptyDirList() {
        assertIs<IllegalArgumentException>(runCatching { MultiDirMapSource(emptyList()) }.exceptionOrNull())
    }

    private fun writeBucket(dir: File, name: String, content: String) {
        FileOutputStream(File(dir, name)).use { it.write(content.toByteArray()) }
    }
}