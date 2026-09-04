package com.danemadsen.atlas.beerouter.map.generator

import androidx.collection.MutableLongIntMap
import com.danemadsen.atlas.beerouter.geo.Position
import java.io.File

public class WayCutter : GeneratorBase() {
    /**
     * LOCAL PATCH (Atlas): the nid -> tile-index map has two storage modes.
     * The default is the original scatter map (nids are arbitrary longs
     * when the nodes come from OSM PBF). A caller that assigns DENSE nids
     * (Atlas's PmtilesCutter numbers them 1..nodeCount) calls
     * [beginDenseIndex] with the final count before any node or way
     * arrives, and the map becomes an IntArray — ~30x smaller at a 4.6M
     * node bucket, which is what keeps the on-device build inside
     * Android's largeHeap. Reads and writes answer identically for nids
     * inside the promised range; outside it, dense mode fails loudly (a
     * scatter map would answer -1 on a lookup but silently LOSE a write).
     */
    private var tileIndexArray: IntArray? = null
    private var tileIndexMap: MutableLongIntMap = MutableLongIntMap()

    public fun process(nodeTilesIn: File, wayFileIn: File, wayTilesOut: File) {
        init(wayTilesOut)
        NodeIterator(this, false).processDir(nodeTilesIn, ".tlf")
        WayIterator(this, true).processFile(wayFileIn)
        finish()
    }

    public fun init(wayTilesOut: File) {
        outTileDir = wayTilesOut
        tileIndexArray = null
        tileIndexMap = MutableLongIntMap()
    }

    /**
     * LOCAL PATCH (Atlas): switch to dense-array storage. Must be called
     * after the node ids are all assigned but before any [nextNode]/
     * [nextWay] call.
     */
    public fun beginDenseIndex(nodeCount: Int) {
        tileIndexMap = MutableLongIntMap()
        tileIndexArray = IntArray(nodeCount + 1) { -1 }
    }

    public fun finish() {
        closeTileOutStreams()
    }

    public override fun nextNode(data: NodeData) {
        setTileIndex(data.nid, getTileIndex(data.position))
    }

    public override fun nextWay(data: WayData) {
        var waytileset = 0L
        for (i in 0 until data.nodes.size) {
            val tileIndex = tileIndexFor(data.nodes.get(i))
            if (tileIndex != -1) {
                waytileset = waytileset or (1L shl tileIndex)
            }
        }
        for (tileIndex in 0 until 54) {
            if ((waytileset and (1L shl tileIndex)) != 0L) {
                data.writeTo(getOutStreamForTile(tileIndex))
            }
        }
    }

    public fun getTileIndexForNid(nid: Long): Int = tileIndexFor(nid)

    private fun tileIndexFor(nid: Long): Int {
        val array = tileIndexArray ?: return tileIndexMap.getOrDefault(nid, -1)
        require(nid in 0 until array.size) {
            "nid $nid is outside the dense range promised to beginDenseIndex (0..${array.size - 1})"
        }
        return array[nid.toInt()]
    }

    private fun setTileIndex(nid: Long, tileIndex: Int) {
        val array = tileIndexArray
        if (array != null) {
            require(nid in 0 until array.size) {
                "nid $nid is outside the dense range promised to beginDenseIndex (0..${array.size - 1})"
            }
            array[nid.toInt()] = tileIndex
        } else {
            tileIndexMap[nid] = tileIndex
        }
    }

    private fun getTileIndex(position: Position): Int {
        val lon = position.longitude / 45000000
        val lat = position.latitude / 30000000
        if (lon !in 0..7 || lat !in 0..5) {
            throw IllegalArgumentException("illegal pos: ${position.longitude},${position.latitude}")
        }
        return lon * 6 + lat
    }

    public override fun getNameForTile(tileIndex: Int): String {
        val lon = (tileIndex / 6) * 45 - 180
        val lat = (tileIndex % 6) * 30 - 90
        val slon = if (lon < 0) "W${-lon}" else "E$lon"
        val slat = if (lat < 0) "S${-lat}" else "N$lat"
        return "${slon}_${slat}.wtl"
    }
}
