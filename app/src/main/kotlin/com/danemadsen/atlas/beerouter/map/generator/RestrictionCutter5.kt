package com.danemadsen.atlas.beerouter.map.generator

import java.io.File

public class RestrictionCutter5 : GeneratorBase() {
    private lateinit var wayCutter5: WayCutter5

    public fun init(outTileDir: File, wayCutter5: WayCutter5) {
        outTileDir.mkdir()
        this.outTileDir = outTileDir
        this.wayCutter5 = wayCutter5
    }

    public fun finish() {
        closeTileOutStreams()
    }

    public fun nextRestriction(data: RestrictionData) {
        val tileIndex = wayCutter5.getTileIndexForNid(data.viaNid)
        if (tileIndex != -1) {
            data.writeTo(getOutStreamForTile(tileIndex))
        }
    }

    public override fun getNameForTile(tileIndex: Int): String {
        val name = wayCutter5.getNameForTile(tileIndex)
        return name.substring(0, name.length - 3) + "rt5"
    }
}
