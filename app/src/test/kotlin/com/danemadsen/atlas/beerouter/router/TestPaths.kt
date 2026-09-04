package com.danemadsen.atlas.beerouter.router

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal val testSegmentFile: Path = Path(System.getProperty("java.io.tmpdir"), "E5_N45.rd5")

// Provisioned out of band (upstream generates it with their planet
// tooling); a placeholder keeps class-load cheap — the ensure* guards
// below skip the tests that need it when it was never provisioned.
internal val generatedTestSegmentDir: Path =
    System.getProperty("beerouter.generatedSegmentDir")?.let { Path(it) }
        ?: Path(System.getProperty("java.io.tmpdir"), "beerouter-generated-segments-not-provisioned")

internal fun profilePath(profileName: String): Path {
    val candidates = listOf(
        Path(System.getProperty("user.dir"), "src", "main", "kotlin", "com", "danemadsen", "atlas", "beerouter", "profiles2", profileName),
        Path(System.getProperty("user.dir"), "misc", "profiles2", profileName),
        Path(System.getProperty("user.dir"), "..", "misc", "profiles2", profileName),
    )
    return candidates.firstOrNull(SystemFileSystem::exists)
        ?: error("missing profile file for $profileName")
}

internal fun Path.toJavaFile(): File = File(toString())

/**
 * Upstream downloads E5_N45.rd5 from segments.skynomads.dev on demand;
 * Atlas runs its suite offline, so this only verifies the fixture is
 * already in the tmp dir and skips the calling test otherwise. To
 * provision it once (dev machine, network allowed):
 *
 *   curl -o "$TMPDIR/E5_N45.rd5" https://segments.skynomads.dev/v10/E5_N45.rd5
 */
internal fun ensureTestSegmentFile() {
    org.junit.Assume.assumeTrue(
        "E5_N45.rd5 not provisioned in the tmp dir (see ensureTestSegmentFile doc)",
        SystemFileSystem.exists(testSegmentFile),
    )
}

/** Skips the calling test unless the generated-segments dir was provisioned. */
internal fun ensureGeneratedTestSegmentDir() {
    org.junit.Assume.assumeTrue(
        "generated segments dir not provisioned (set -Dbeerouter.generatedSegmentDir=<dir>)",
        SystemFileSystem.exists(generatedTestSegmentDir),
    )
}

internal fun lookupPathForSegments(segmentDir: Path): Path {
    val segmentLookup = Path(segmentDir, "lookups.dat")
    return if (SystemFileSystem.exists(segmentLookup)) {
        segmentLookup
    } else {
        Path(profilePath("trekking.brf").parent ?: error("profile parent missing"), "lookups.dat")
    }
}

internal fun profilePathForSegments(profileName: String, segmentDir: Path): Path {
    val profile = profilePath(profileName)
    val segmentLookup = Path(segmentDir, "lookups.dat")
    if (!SystemFileSystem.exists(segmentLookup)) {
        return profile
    }

    val cacheDir = Path(
        System.getProperty("java.io.tmpdir"),
        "beerouter-routing-test-profiles",
        segmentDir.toString().hashCode().toUInt().toString(),
    )
    val cachedProfile = Path(cacheDir, profileName)
    if (SystemFileSystem.exists(cachedProfile)) {
        return cachedProfile
    }

    Files.createDirectories(File(cacheDir.toString()).toPath())
    val sourceDir = File(requireNotNull(profile.parent).toString())
    sourceDir.listFiles()?.forEach { source ->
        if (source.isFile) {
            Files.copy(
                source.toPath(),
                File(cacheDir.toString(), source.name).toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
    Files.copy(
        File(segmentLookup.toString()).toPath(),
        File(cacheDir.toString(), "lookups.dat").toPath(),
        StandardCopyOption.REPLACE_EXISTING,
    )
    return cachedProfile
}
