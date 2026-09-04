package com.danemadsen.atlas.beerouter.map.generator

import androidx.collection.MutableLongIntMap
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

public class NodeFilter : GeneratorBase() {
    private lateinit var nodesOutStream: DiffCoderDataOutputStream
    private lateinit var nodeTilesOut: File

    /**
     * LOCAL PATCH (Atlas): the "this nid is in a way" marks have two
     * storage modes, like WayCutter's tile-index map. The default is the
     * original scatter map; [beginDenseMarks] — called by Atlas's
     * PmtilesCutter once its dense node ids are all assigned — switches to
     * a ByteArray where 0xFF marks "not in any way" and 0 marks "in a way"
     * (the original map's only value). Answers are identical for nids
     * inside the promised range; outside it, dense mode fails loudly.
     */
    private var wayMarks: ByteArray? = null
    private var nodebitmap: MutableLongIntMap = MutableLongIntMap()

    public var retainDescribedNodes: Boolean = false

    public fun init() {
        wayMarks = null
        nodebitmap = MutableLongIntMap()
    }

    /**
     * LOCAL PATCH (Atlas): switch to dense-array storage. Must be called
     * after the node ids are all assigned but before any [nextWay] call
     * (and before the [isRelevant] queries that read the marks).
     */
    public fun beginDenseMarks(nodeCount: Int) {
        nodebitmap = MutableLongIntMap()
        wayMarks = ByteArray(nodeCount + 1) { 0xFF.toByte() }
    }

    public fun process(nodeTilesIn: File, wayFileIn: File, nodeTilesOut: File) {
        init()
        this.nodeTilesOut = nodeTilesOut
        WayIterator(this, false).processFile(wayFileIn)
        NodeIterator(this, true).processDir(nodeTilesIn, ".tls")
    }

    public override fun nextWay(data: WayData) {
        for (i in 0 until data.nodes.size) {
            markInWay(data.nodes.get(i))
        }
    }

    private fun markInWay(nid: Long) {
        val marks = wayMarks
        if (marks != null) {
            require(nid in 0 until marks.size) {
                "nid $nid is outside the dense range promised to beginDenseMarks (0..${marks.size - 1})"
            }
            marks[nid.toInt()] = 0
        } else {
            nodebitmap[nid] = 0
        }
    }

    public override fun nodeFileStart(nodefile: File?) {
        val outfile = File(nodeTilesOut, requireNotNull(nodefile).name)
        nodesOutStream = DiffCoderDataOutputStream(BufferedOutputStream(FileOutputStream(outfile)))
    }

    public override fun nextNode(data: NodeData) {
        if (isRelevant(data)) {
            data.writeTo(nodesOutStream)
        }
    }

    public fun isRelevant(node: NodeData): Boolean {
        val marks = wayMarks
        if (marks != null) {
            require(node.nid in 0 until marks.size) {
                "nid ${node.nid} is outside the dense range promised to beginDenseMarks (0..${marks.size - 1})"
            }
            if (marks[node.nid.toInt()] == 0.toByte()) return true
        } else if (nodebitmap.getOrDefault(node.nid, -1) == 0) {
            return true
        }
        return retainDescribedNodes && node.description != null
    }

    public override fun nodeFileEnd(nodefile: File?) {
        nodesOutStream.close()
    }
}
