package com.danemadsen.atlas.beerouter.map.generator

import androidx.collection.MutableLongSet
import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.geo.UNSET_ELEVATION
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream

public class PosUnifier : GeneratorBase() {
    private lateinit var nodesOutStream: DiffCoderDataOutputStream
    private lateinit var borderNodesOut: DiffCoderDataOutputStream
    private lateinit var nodeTilesOut: File
    private var outNodeFile: File? = null
    private lateinit var positionSets: Array<MutableLongSet?>
    private var srtmmap: MutableMap<String, ElevationRaster> = HashMap()
    private var lastSrtmLonIdx: Int = -1
    private var lastSrtmLatIdx: Int = -1
    private var lastSrtmRaster: ElevationRaster? = null
    private lateinit var srtmdir: String
    private var srtmfallbackdir: String? = null
    private lateinit var borderNids: MutableLongSet

    public fun process(
        nodeTilesIn: File,
        nodeTilesOut: File,
        bordernidsinfile: File,
        bordernodesoutfile: File,
        srtmdir: String,
        srtmfallbackdir: String?,
    ) {
        this.nodeTilesOut = nodeTilesOut
        this.srtmdir = srtmdir
        this.srtmfallbackdir = srtmfallbackdir
        val dis: DataInputStream = createInStream(bordernidsinfile)
        val mutableBorderNids = MutableLongSet()
        try {
            while (true) {
                val nid = readId(dis)
                if (!mutableBorderNids.contains(nid)) {
                    mutableBorderNids.add(nid)
                }
            }
        } catch (_: EOFException) {
            dis.close()
        }
        borderNids = mutableBorderNids
        borderNodesOut = createOutStream(bordernodesoutfile)
        NodeIterator(this, true).processDir(nodeTilesIn, ".n5d")
        borderNodesOut.close()
    }

    public override fun nodeFileStart(nodefile: File?) {
        resetElevationRaster()
        outNodeFile = fileFromTemplate(requireNotNull(nodefile), nodeTilesOut, "u5d")
        nodesOutStream = createOutStream(requireNotNull(outNodeFile))
        positionSets = arrayOfNulls(2500)
    }

    public override fun nextNode(data: NodeData) {
        val srtm = srtmForNode(data.position)
        val selev = srtm?.getElevation(data.position) ?: UNSET_ELEVATION
        data.position = data.position.copy(altitude = selev)
        findUniquePos(data)
        data.writeTo(nodesOutStream)
        if (borderNids.contains(data.nid)) {
            data.writeTo(borderNodesOut)
        }
    }

    public override fun nodeFileEnd(nodefile: File?) {
        nodesOutStream.close()
        val raster = lastSrtmRaster
        val output = outNodeFile
        if (output != null && raster != null) {
            val newName = output.absolutePath + if (raster.nrows > 6001) "_1" else "_3"
            output.renameTo(File(newName))
        }
        resetElevationRaster()
    }

    private fun checkAdd(position: Position): Boolean {
        val slot =
            ((position.longitude % 5_000_000) / 100_000) * 50 + ((position.latitude % 5_000_000) / 100_000)
        val id = position.id
        var set = positionSets[slot]
        if (set == null) {
            set = MutableLongSet()
            positionSets[slot] = set
        }
        return if (!set.contains(id)) {
            set.add(id)
            true
        } else {
            false
        }
    }

    private fun findUniquePos(node: NodeData) {
        if (!checkAdd(node.position)) {
            val lonmod = node.position.longitude % 1_000_000
            val londelta = if (lonmod < 500_000) 1 else -1
            val latmod = node.position.latitude % 1_000_000
            val latdelta = if (latmod < 500_000) 1 else -1
            for (latsteps in 0 until 100) {
                for (lonsteps in 0..latsteps) {
                    val lon = node.position.longitude + lonsteps * londelta
                    val lat = node.position.latitude + latsteps * latdelta
                    val newPos = node.position.copy(longitude = lon, latitude = lat)
                    if (checkAdd(newPos)) {
                        node.position = newPos
                        return
                    }
                }
            }
            println("*** WARNING: cannot unify position for: ${node.position.longitude} ${node.position.latitude}")
        }
    }

    private fun srtmForNode(position: Position): ElevationRaster? {
        val srtmLonIdx = (position.longitude + 5_000_000) / 5_000_000
        val srtmLatIdx = (654_999_999 - position.latitude) / 5_000_000 - 100
        if (srtmLonIdx == lastSrtmLonIdx && srtmLatIdx == lastSrtmLatIdx) {
            return lastSrtmRaster
        }
        lastSrtmLonIdx = srtmLonIdx
        lastSrtmLatIdx = srtmLatIdx
        val filename = if (UseRasterRd5FileName) genFilenameRd5(position) else genFilenameXY(
            srtmLonIdx,
            srtmLatIdx
        )
        lastSrtmRaster = srtmmap[filename]
        if (lastSrtmRaster == null && !srtmmap.containsKey(filename)) {
            fun loadRaster(dir: String?): ElevationRaster? {
                if (dir == null) return null
                val file = File(File(dir), "$filename.bef")
                if (!file.exists()) return null
                return try {
                    BufferedInputStream(FileInputStream(file)).use {
                        ElevationRasterCoder().decodeRaster(
                            it
                        )
                    }
                } catch (_: Exception) {
                    null
                }
            }
            lastSrtmRaster = loadRaster(srtmdir) ?: loadRaster(srtmfallbackdir)
            srtmmap[filename] = lastSrtmRaster ?: return null
        }
        return lastSrtmRaster
    }

    private fun resetElevationRaster() {
        srtmmap = HashMap()
        lastSrtmLonIdx = -1
        lastSrtmLatIdx = -1
        lastSrtmRaster = null
    }

    public companion object {
        public const val UseRasterRd5FileName: Boolean = false

        public fun genFilenameXY(srtmLonIdx: Int, srtmLatIdx: Int): String {
            val slonidx = "0$srtmLonIdx"
            val slatidx = "0$srtmLatIdx"
            return "srtm_${slonidx.takeLast(2)}_${slatidx.takeLast(2)}"
        }

        public fun genFilenameRd5(position: Position): String {
            var lonDegree = position.longitude / 1_000_000
            var latDegree = position.latitude / 1_000_000
            val lonMod5 = lonDegree % 5
            val latMod5 = latDegree % 5
            lonDegree = lonDegree - 180 - lonMod5
            latDegree = latDegree - 90 - latMod5
            val lonPart = if (lonDegree < 0) "W${-lonDegree}" else "E$lonDegree"
            val latPart = if (latDegree < 0) "S${-latDegree}" else "N$latDegree"
            return "srtm_${lonPart}_${latPart}"
        }
    }
}
