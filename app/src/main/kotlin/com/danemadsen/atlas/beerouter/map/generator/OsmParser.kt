package com.danemadsen.atlas.beerouter.map.generator

import androidx.collection.MutableLongList
import org.openstreetmap.osmosis.osmbinary.Fileformat
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream

public class OsmParser : GeneratorBase() {
    private lateinit var nListener: NodeListener
    private lateinit var wListener: WayListener
    private lateinit var rListener: RelationListener

    public fun readMap(
        mapFile: File,
        nListener: NodeListener,
        wListener: WayListener,
        rListener: RelationListener
    ) {
        this.nListener = nListener
        this.wListener = wListener
        this.rListener = rListener

        println("*** PBF Parsing: $mapFile")
        var rawBlobCount = 0
        val dis = DataInputStream(BufferedInputStream(FileInputStream(mapFile)))
        while (true) {
            val headerLength = try {
                dis.readInt()
            } catch (_: EOFException) {
                break
            }
            val headerBuffer = ByteArray(headerLength)
            dis.readFully(headerBuffer)
            val blobHeader = Fileformat.BlobHeader.parseFrom(headerBuffer)
            val blobData = ByteArray(blobHeader.datasize)
            dis.readFully(blobData)
            BPbfBlobDecoder(blobHeader.type, blobData, this).process()
            rawBlobCount++
        }
        dis.close()
        println("read raw blobs: $rawBlobCount")
    }

    public fun addNode(nid: Long, tags: Map<String, String>?, lat: Double, lon: Double) {
        val node = NodeData(nid, lon, lat)
        node.setTags(tags as HashMap<String, String>?)
        try {
            nListener.nextNode(node)
        } catch (e: Exception) {
            throw RuntimeException("error writing node: $e", e)
        }
    }

    public fun addWay(wid: Long, tags: Map<String, String>?, nodes: MutableLongList) {
        val way = WayData(wid, nodes)
        way.setTags(tags as HashMap<String, String>?)
        try {
            wListener.nextWay(way)
        } catch (e: Exception) {
            throw RuntimeException("error writing way: $e", e)
        }
    }

    public fun addRelation(
        rid: Long,
        tags: Map<String, String>?,
        wayIds: MutableLongList,
        fromWid: MutableLongList?,
        toWid: MutableLongList?,
        viaNid: MutableLongList?,
    ) {
        val relation = RelationData(rid, wayIds)
        relation.setTags(tags as HashMap<String, String>?)
        try {
            rListener.nextRelation(relation)
            if (fromWid == null || toWid == null || viaNid == null || viaNid.size != 1) {
                for (vi in 0 until (viaNid?.size ?: 0)) {
                    rListener.nextRestriction(relation, 0L, 0L, viaNid!!.get(vi))
                }
                return
            }
            for (fi in 0 until fromWid.size) {
                for (ti in 0 until toWid.size) {
                    rListener.nextRestriction(
                        relation,
                        fromWid.get(fi),
                        toWid.get(ti),
                        viaNid.get(0)
                    )
                }
            }
        } catch (e: Exception) {
            throw RuntimeException("error writing relation", e)
        }
    }
}
