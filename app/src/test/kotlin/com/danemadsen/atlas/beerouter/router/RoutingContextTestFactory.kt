package com.danemadsen.atlas.beerouter.router

import com.danemadsen.atlas.beerouter.map.DefaultMapSource
import com.danemadsen.atlas.beerouter.map.RoutingMemoryPolicy
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import org.junit.Assume

internal fun routingContextFromFiles(
    profile: Path,
    segmentDir: Path,
    generateTurns: Boolean = true,
    memoryPolicy: RoutingMemoryPolicy = RoutingMemoryPolicy.default(),
    profileOverrides: Map<String, String> = emptyMap(),
): RoutingContext {
    require(SystemFileSystem.exists(profile)) { "profile exists $profile" }
    // Upstream provisions the engine fixtures outside the repo (a
    // downloaded E5_N45.rd5 in the tmp dir, or a locally generated
    // segments dir via the beerouter.generatedSegmentDir property).
    // Atlas's suite is offline by default: these tests skip unless the
    // fixtures are present instead of fetching anything.
    val has_segments = SystemFileSystem.exists(segmentDir) &&
        SystemFileSystem.list(segmentDir).any { it.name.endsWith(".rd5") }
    Assume.assumeTrue("segment fixtures not present in $segmentDir", has_segments)
    val segmentLookup = Path(segmentDir, "lookups.dat")
    val lookup = if (SystemFileSystem.exists(segmentLookup)) {
        segmentLookup
    } else {
        Path(profile.parent ?: error("profile has no parent: $profile"), "lookups.dat")
    }
    require(SystemFileSystem.exists(lookup)) { "lookup exists $lookup" }
    return SystemFileSystem.source(profile).buffered().use { profileSource ->
        SystemFileSystem.source(lookup).buffered().use { lookupSource ->
            RoutingContext(
                profileContent = profileSource.readString(),
                lookupContent = lookupSource.readString(),
                mapSource = DefaultMapSource(segmentDir),
                generateTurns = generateTurns,
                memoryPolicy = memoryPolicy,
                profileOverrides = profileOverrides,
            )
        }
    }
}
