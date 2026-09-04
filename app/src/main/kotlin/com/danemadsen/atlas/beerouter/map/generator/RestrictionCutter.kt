package com.danemadsen.atlas.beerouter.map.generator

import java.io.File

public class RestrictionCutter : GeneratorBase() {
    private lateinit var wayCutter: WayCutter

    public fun init(outTileDir: File, wayCutter: WayCutter) {
        outTileDir.mkdir()
        this.outTileDir = outTileDir
        this.wayCutter = wayCutter
    }

    public fun finish() {
        closeTileOutStreams()
    }

    public fun nextRestriction(data: RestrictionData) {
        val tileIndex = wayCutter.getTileIndexForNid(data.viaNid)
        if (tileIndex != -1) {
            data.writeTo(getOutStreamForTile(tileIndex))
        }
    }

    public override fun getNameForTile(tileIndex: Int): String {
        val name = wayCutter.getNameForTile(tileIndex)
        return name.substring(0, name.length - 3) + "rtl"
    }
}
