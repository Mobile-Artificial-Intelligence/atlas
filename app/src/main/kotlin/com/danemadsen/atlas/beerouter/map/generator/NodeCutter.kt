package com.danemadsen.atlas.beerouter.map.generator

import com.danemadsen.atlas.beerouter.geo.Position
import java.io.File

public class NodeCutter : GeneratorBase() {
    private var lonoffset: Int = -1
    private var latoffset: Int = -1

    public fun init(nodeTilesOut: File) {
        outTileDir = nodeTilesOut
    }

    public fun process(nodeTilesIn: File, nodeTilesOut: File) {
        init(nodeTilesOut)
        NodeIterator(this, true).processDir(nodeTilesIn, ".tlf")
    }

    public override fun nodeFileStart(nodefile: File?) {
        lonoffset = -1
        latoffset = -1
    }

    public override fun nextNode(data: NodeData) {
        data.writeTo(getOutStreamForTile(getTileIndex(data.position)))
    }

    public override fun nodeFileEnd(nodefile: File?) {
        closeTileOutStreams()
    }

    private fun getTileIndex(position: Position): Int {
        val ilon = position.longitude
        val ilat = position.latitude
        val lonoff = (ilon / 45000000) * 45
        val latoff = (ilat / 30000000) * 30
        if (lonoffset == -1) lonoffset = lonoff
        if (latoffset == -1) latoffset = latoff
        if (lonoff != lonoffset || latoff != latoffset) {
            throw IllegalArgumentException("inconsistent node: $ilon $ilat")
        }
        val lon = (ilon / 5000000) % 9
        val lat = (ilat / 5000000) % 6
        if (lon !in 0..8 || lat !in 0..5) {
            throw IllegalArgumentException("illegal pos: $ilon,$ilat")
        }
        return lon * 6 + lat
    }

    public override fun getNameForTile(tileIndex: Int): String {
        val lon = (tileIndex / 6) * 5 + lonoffset - 180
        val lat = (tileIndex % 6) * 5 + latoffset - 90
        val slon = if (lon < 0) "W${-lon}" else "E$lon"
        val slat = if (lat < 0) "S${-lat}" else "N$lat"
        return "${slon}_${slat}.n5d"
    }
}
