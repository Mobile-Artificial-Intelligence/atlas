package com.danemadsen.atlas.beerouter.map.generator

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * End-to-end rd5 generation entry point matching the upstream BRouter mapcreation flow:
 * OsmFastCutter -> PosUnifier -> WayLinker.
 *
 * The intended caller is the Gradle `generatePlanetRd5` task.
 */
public object PlanetRd5Generator {
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size in 7..8) {
            "usage: PlanetRd5Generator <planet-file> <work-dir> <output-dir> <profile-dir> <srtm-dir> <srtm-fallback-dir> <db-tag-info> [poi-config]"
        }

        val planetFile = File(args[0])
        val workDir = File(args[1])
        val outputDir = File(args[2])
        val profileDir = File(args[3])
        val srtmDir = args[4].ifBlank { null }?.let(::File)
        val srtmFallbackDir = args[5].ifBlank { null }?.let(::File)
        val dbTagInfo = args[6].ifBlank { null }
        val poiConfigFile = args.getOrNull(7)?.ifBlank { null }?.let(::File)

        require(planetFile.isFile) { "planet file does not exist: ${planetFile.absolutePath}" }
        require(profileDir.isDirectory) { "profile directory does not exist: ${profileDir.absolutePath}" }
        require(poiConfigFile == null || poiConfigFile.isFile) {
            "poi config file does not exist: ${poiConfigFile?.absolutePath}"
        }

        val runDir = File(workDir, "tmp")
        if (runDir.exists()) {
            runDir.deleteRecursively()
        }
        runDir.mkdirs()

        val nodes = File(runDir, "nodetiles").apply { mkdirs() }
        val ways = File(runDir, "waytiles").apply { mkdirs() }
        val nodes55 = File(runDir, "nodes55").apply { mkdirs() }
        val ways55 = File(runDir, "waytiles55").apply { mkdirs() }
        val baseLookupFile = File(profileDir, "lookups.dat")
        val poiGenerationConfig =
            poiConfigFile?.let { PoiConfigParser.parse(it.readText()) } ?: PoiGenerationConfig.EMPTY
        val generatedLookup =
            GeneratedLookups.generate(baseLookupFile.readText(), poiGenerationConfig)
        val lookupFile =
            File(runDir, "generated-lookups.dat").apply { writeText(generatedLookup.lookupContent) }
        val relFile = File(runDir, "cycleways.dat")
        val resFile = File(runDir, "restrictions.dat")
        val profileAll = File(profileDir, "all.brf")
        val profileReport = File(profileDir, "trekking.brf")
        val profileCheck = File(profileDir, "softaccess.brf")
        val borderFile = File(runDir, "bordernids.dat")

        println("Running OsmFastCutter on ${planetFile.absolutePath}")
        OsmFastCutter.doCut(
            lookupFile = lookupFile,
            nodeDir = nodes,
            wayDir = ways,
            node55Dir = nodes55,
            way55Dir = ways55,
            borderFile = borderFile,
            relFile = relFile,
            resFile = resFile,
            profileAll = profileAll,
            profileReport = profileReport,
            profileCheck = profileCheck,
            mapFile = planetFile,
            dbTagInfo = dbTagInfo,
            poiGenerationConfig = poiGenerationConfig,
        )

        val unodes55 = File(runDir, "unodes55").apply { mkdirs() }
        val bordernodes = File(runDir, "bordernodes.dat")
        println(
            if (srtmDir == null) {
                "Running PosUnifier without SRTM data"
            } else {
                "Running PosUnifier with SRTM dir ${srtmDir.absolutePath}" +
                        (srtmFallbackDir?.let { " and fallback ${it.absolutePath}" } ?: "")
            },
        )
        PosUnifier().process(
            nodeTilesIn = nodes55,
            nodeTilesOut = unodes55,
            bordernidsinfile = borderFile,
            bordernodesoutfile = bordernodes,
            srtmdir = srtmDir?.absolutePath ?: runDir.absolutePath,
            srtmfallbackdir = srtmFallbackDir?.absolutePath,
        )

        val stagedSegmentsDir = File(runDir, "segments").apply { mkdirs() }
        println("Running WayLinker into ${stagedSegmentsDir.absolutePath}")
        WayLinker().process(
            nodeTilesIn = unodes55,
            wayTilesIn = ways55,
            borderFileIn = bordernodes,
            restrictionsFileIn = resFile,
            lookupFile = lookupFile,
            profileFile = profileAll,
            dataTilesOut = stagedSegmentsDir,
            dataTilesSuffix = "rd5",
        )

        require(stagedSegmentsDir.listFiles()?.isNotEmpty() == true) {
            "rd5 generation produced no output in ${stagedSegmentsDir.absolutePath}"
        }

        if (outputDir.exists()) {
            outputDir.deleteRecursively()
        }
        outputDir.parentFile?.mkdirs()
        moveDirectory(stagedSegmentsDir, outputDir)

        val storageConfig = File(outputDir, "storageconfig.txt")
        if (!storageConfig.exists()) {
            storageConfig.writeText(
                """
                # Generated by Beerouter generator.
                # Add secondary_segment_dir=/path/to/other/segments if needed.
                """.trimIndent() + "\n",
            )
        }
        Files.copy(
            lookupFile.toPath(),
            File(outputDir, "lookups.dat").toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
        poiConfigFile?.let {
            Files.copy(
                it.toPath(),
                File(outputDir, "poi-rules.conf").toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }

        println("rd5 files written to ${outputDir.absolutePath}")
    }

    private fun moveDirectory(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return
        } catch (_: Exception) {
            source.copyRecursively(target, overwrite = true)
            source.deleteRecursively()
        }
    }
}
