package com.danemadsen.atlas.beerouter.map.generator

import java.io.File

public object OsmFastCutter : GeneratorBase() {
    public fun doCut(
        lookupFile: File,
        nodeDir: File,
        wayDir: File,
        node55Dir: File,
        way55Dir: File,
        borderFile: File,
        relFile: File,
        resFile: File,
        profileAll: File,
        profileReport: File,
        profileCheck: File,
        mapFile: File?,
        dbTagInfo: String?,
        poiGenerationConfig: PoiGenerationConfig = PoiGenerationConfig.EMPTY,
    ) {
        var cutter = OsmCutter()
        if (dbTagInfo != null) {
            if (dbTagInfo.lowercase()
                    .startsWith("jdbc")
            ) cutter.setDbTagDatabase(dbTagInfo) else cutter.setDbTagFilename(dbTagInfo)
        }
        cutter.setNodeTagRetentionPolicy(
            NodeTagRetentionPolicy.fromProfiles(
                lookupFile = lookupFile,
                profileFiles = listOf(profileAll, profileReport, profileCheck),
                poiConfig = poiGenerationConfig,
            ),
        )
        cutter.wayCutter = WayCutter().also { it.init(wayDir) }
        cutter.restrictionCutter = RestrictionCutter().also {
            it.init(File(requireNotNull(nodeDir.parentFile), "restrictions"), cutter.wayCutter!!)
        }
        val nodeFilter = NodeFilter().also {
            it.init()
            it.retainDescribedNodes = !poiGenerationConfig.isEmpty
        }
        cutter.nodeFilter = nodeFilter
        cutter.process(
            lookupFile,
            nodeDir,
            null,
            relFile,
            null,
            profileAll,
            requireNotNull(mapFile)
        )
        cutter.wayCutter!!.finish()
        cutter.restrictionCutter!!.finish()
        cutter = OsmCutter()

        val wayCut5 = WayCutter5()
        wayCut5.relMerger =
            RelationMerger().also { it.init(relFile, lookupFile, profileReport, profileCheck) }
        wayCut5.restrictionCutter5 = RestrictionCutter5().also {
            it.init(File(requireNotNull(nodeDir.parentFile), "restrictions55"), wayCut5)
        }
        wayCut5.nodeFilter = nodeFilter
        wayCut5.nodeCutter = NodeCutter().also { it.init(node55Dir) }
        wayCut5.process(nodeDir, wayDir, way55Dir, borderFile)
    }
}
