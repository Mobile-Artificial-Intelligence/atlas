package com.danemadsen.atlas.beerouter.map.generator

import androidx.collection.MutableLongIntMap
import com.danemadsen.atlas.beerouter.geo.Position
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream

public class WayCutter5 : GeneratorBase() {
    private lateinit var borderNidsOutStream: DiffCoderDataOutputStream

    /**
     * LOCAL PATCH (Atlas): the per-way-file nid -> tile-index map has two
     * storage modes, like WayCutter's. The caller sets [expectedNodeCount]
     * to the total node count when the upstream scan assigned dense nids
     * (Atlas's PmtilesCutter numbers them 1..nodeCount), and each
     * [wayFileStart] then uses an IntArray instead of a hashed map. Left at
     * -1 (the default), the original scatter map is used unchanged.
     */
    private var tileIndexArray: IntArray? = null
    private var tileIndexMap: MutableLongIntMap = MutableLongIntMap()
    private lateinit var nodeTilesIn: File
    private var lonoffset: Int = -1
    private var latoffset: Int = -1

    /** Dense-nid mode: every nid seen is in 0..expectedNodeCount. -1 = scatter. */
    public var expectedNodeCount: Int = -1

    public var relMerger: RelationMerger? = null
    public var nodeFilter: NodeFilter? = null
    public var nodeCutter: NodeCutter? = null
    public var restrictionCutter5: RestrictionCutter5? = null

    public fun process(
        nodeTilesIn: File,
        wayTilesIn: File,
        wayTilesOut: File,
        borderNidsOut: File
    ) {
        this.nodeTilesIn = nodeTilesIn
        this.outTileDir = wayTilesOut
        borderNidsOutStream = createOutStream(borderNidsOut)
        WayIterator(this, true).processDir(wayTilesIn, ".wtl")
        borderNidsOutStream.close()
        // The per-file index is dead once the last way file is processed —
        // drop it so the phase that follows (PosUnifier/WayLinker) starts
        // without this array still pinned.
        tileIndexArray = null
    }

    public override fun wayFileStart(wayfile: File): Boolean {
        val nodefilename = wayfile.name.substring(0, wayfile.name.length - 3) + "ntl"
        val nodefile = File(nodeTilesIn, nodefilename)
        tileIndexArray = if (expectedNodeCount > 0) {
            IntArray(expectedNodeCount + 1) { -1 }
        } else {
            null
        }
        if (tileIndexArray == null) tileIndexMap = MutableLongIntMap()
        lonoffset = -1
        latoffset = -1
        nodeCutter?.nodeFileStart(null)
        NodeIterator(this, nodeCutter != null).processFile(nodefile)
        restrictionCutter5?.let { cutter ->
            val resfilename = wayfile.name.substring(0, wayfile.name.length - 3) + "rtl"
            val resfile = File("restrictions", resfilename)
            if (resfile.exists()) {
                val di = DataInputStream(BufferedInputStream(FileInputStream(resfile)))
                try {
                    while (true) {
                        cutter.nextRestriction(RestrictionData(di))
                    }
                } catch (_: EOFException) {
                    di.close()
                }
            }
        }
        return true
    }

    public override fun nextNode(data: NodeData) {
        if (nodeFilter?.isRelevant(data) == false) {
            return
        }
        nodeCutter?.nextNode(data)
        setTileIndex(data.nid, getTileIndex(data.position))
    }

    public override fun nextWay(data: WayData) {
        var waytileset = 0L
        val tiForNode = IntArray(data.nodes.size)
        for (i in 0 until data.nodes.size) {
            val tileIndex = tileIndexFor(data.nodes.get(i))
            if (tileIndex != -1) {
                waytileset = waytileset or (1L shl tileIndex)
            }
            tiForNode[i] = tileIndex
        }
        relMerger?.nextWay(data)
        for (tileIndex in 0 until 54) {
            if ((waytileset and (1L shl tileIndex)) != 0L) {
                data.writeTo(getOutStreamForTile(tileIndex))
            }
        }
        for (i in 0 until data.nodes.size) {
            val ti = tiForNode[i]
            if (ti != -1 && ((i > 0 && tiForNode[i - 1] != ti) || (i + 1 < data.nodes.size && tiForNode[i + 1] != ti))) {
                writeId(borderNidsOutStream, data.nodes.get(i))
            }
        }
    }

    public override fun wayFileEnd(wayfile: File) {
        closeTileOutStreams()
        nodeCutter?.nodeFileEnd(null)
        restrictionCutter5?.finish()
    }

    public fun getTileIndexForNid(nid: Long): Int = tileIndexFor(nid)

    private fun tileIndexFor(nid: Long): Int {
        val array = tileIndexArray ?: return tileIndexMap.getOrDefault(nid, -1)
        require(nid in 0 until array.size) {
            "nid $nid is outside the dense range 0..${array.size - 1} implied by expectedNodeCount"
        }
        return array[nid.toInt()]
    }

    private fun setTileIndex(nid: Long, tileIndex: Int) {
        val array = tileIndexArray
        if (array != null) {
            require(nid in 0 until array.size) {
                "nid $nid is outside the dense range 0..${array.size - 1} implied by expectedNodeCount"
            }
            array[nid.toInt()] = tileIndex
        } else {
            tileIndexMap[nid] = tileIndex
        }
    }

    private fun getTileIndex(position: Position): Int {
        val lonoff = (position.longitude / 45000000) * 45
        val latoff = (position.latitude / 30000000) * 30
        if (lonoffset == -1) lonoffset = lonoff
        if (latoffset == -1) latoffset = latoff
        if (lonoff != lonoffset || latoff != latoffset) {
            throw IllegalArgumentException("inconsistent node: ${position.longitude} ${position.latitude}")
        }
        val lon = (position.longitude / 5000000) % 9
        val lat = (position.latitude / 5000000) % 6
        if (lon !in 0..8 || lat !in 0..5) {
            throw IllegalArgumentException("illegal pos: ${position.longitude},${position.latitude}")
        }
        return lon * 6 + lat
    }

    public override fun getNameForTile(tileIndex: Int): String {
        val lon = (tileIndex / 6) * 5 + lonoffset - 180
        val lat = (tileIndex % 6) * 5 + latoffset - 90
        val slon = if (lon < 0) "W${-lon}" else "E$lon"
        val slat = if (lat < 0) "S${-lat}" else "N$lat"
        return "${slon}_${slat}.wt5"
    }
}
