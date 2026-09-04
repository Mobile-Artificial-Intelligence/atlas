package com.danemadsen.atlas.beerouter.map.generator

import com.danemadsen.atlas.beerouter.expressions.BExpressionContextNode
import com.danemadsen.atlas.beerouter.expressions.BExpressionContextWay
import com.danemadsen.atlas.beerouter.expressions.BExpressionMetaData
import com.danemadsen.atlas.beerouter.geo.Position
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

public class OsmCutter : GeneratorBase() {
    private var recordCnt: Long = 0
    private var nodesParsed: Long = 0
    private var waysParsed: Long = 0
    private var relsParsed: Long = 0
    private var changesetsParsed: Long = 0

    private var wayDos: DataOutputStream? = null
    private lateinit var cyclewayDos: DataOutputStream
    private var restrictionsDos: DataOutputStream? = null

    public var wayCutter: WayCutter? = null
    public var restrictionCutter: RestrictionCutter? = null
    public var nodeFilter: NodeFilter? = null

    private var dbPseudoTagProvider: DatabasePseudoTagProvider? = null
    private lateinit var expctxWay: BExpressionContextWay
    private lateinit var expctxNode: BExpressionContextNode
    private var nodeTagRetentionPolicy: NodeTagRetentionPolicy = NodeTagRetentionPolicy.DEFAULT

    public fun process(
        lookupFile: File,
        outTileDir: File,
        wayFile: File?,
        relFile: File,
        resFile: File?,
        profileFile: File,
        mapFile: File,
    ) {
        require(lookupFile.exists()) { "lookup-file: $lookupFile does not exist" }
        val meta = BExpressionMetaData()
        expctxWay = BExpressionContextWay(meta)
        expctxNode = BExpressionContextNode(meta)
        meta.readMetaData(lookupFile.readText())
        expctxWay.parseProfile(profileFile.readText(), "global")

        this.outTileDir = outTileDir
        if (!outTileDir.isDirectory) {
            throw RuntimeException("out tile directory $outTileDir does not exist")
        }

        wayDos = wayFile?.let { DataOutputStream(BufferedOutputStream(FileOutputStream(it))) }
        cyclewayDos = DataOutputStream(BufferedOutputStream(FileOutputStream(relFile)))
        if (resFile != null) {
            restrictionsDos = DataOutputStream(BufferedOutputStream(FileOutputStream(resFile)))
        }
        val t0 = System.currentTimeMillis()
        OsmParser().readMap(mapFile, this, this, this)
        val t1 = System.currentTimeMillis()
        println("parsing time (ms) =${t1 - t0}")
        closeTileOutStreams()
        wayDos?.close()
        cyclewayDos.close()
        restrictionsDos?.close()
        println(statsLine())
    }

    public fun setDbTagFilename(filename: String) {
        dbPseudoTagProvider = DatabasePseudoTagProvider(filename, null)
    }

    public fun setDbTagDatabase(jdbcurl: String) {
        dbPseudoTagProvider = DatabasePseudoTagProvider(null, jdbcurl)
    }

    internal fun setNodeTagRetentionPolicy(nodeTagRetentionPolicy: NodeTagRetentionPolicy) {
        this.nodeTagRetentionPolicy = nodeTagRetentionPolicy
    }

    public override fun nextNode(data: NodeData) {
        nodesParsed++
        checkStats()
        val tags = data.getTagsOrNull()
        if (tags != null) {
            val poiMatches = nodeTagRetentionPolicy.poiConfig.matchingRules(tags)
            val retainedTags = nodeTagRetentionPolicy.retainedTags(tags)
            val lookupData = expctxNode.createNewLookupData()!!
            for ((key, value) in retainedTags) {
                expctxNode.addLookupValue(key, value, lookupData)
            }
            data.description = expctxNode.encode(lookupData)
            data.retainWithoutLinks = poiMatches.isNotEmpty()
        }
        val tileIndex = getTileIndex(data.position)
        if (tileIndex >= 0) {
            data.writeTo(getOutStreamForTile(tileIndex))
            wayCutter?.nextNode(data)
        }
    }

    private fun generatePseudoTags(map: MutableMap<String, String>) {
        var concrete: String? = null
        for ((key, value) in map) {
            if (key == "concrete") {
                return
            }
            if (key == "surface" && value.startsWith("concrete:")) {
                concrete = value.substring("concrete:".length)
            }
        }
        if (concrete != null) {
            map["concrete"] = concrete
        }
    }

    public override fun nextWay(data: WayData) {
        waysParsed++
        checkStats()
        val tags = data.getTagsOrNull() ?: return
        dbPseudoTagProvider?.addTags(data.wid, tags)
        generatePseudoTags(tags)
        val lookupData = expctxWay.createNewLookupData()!!
        for (key in tags.keys) {
            expctxWay.addLookupValue(
                key,
                requireNotNull(data.getTag(key)).replace(' ', '_'),
                lookupData
            )
        }
        data.description = expctxWay.encode(lookupData)
        if (data.description == null) return
        expctxWay.evaluate(false, requireNotNull(data.description))
        var ok = expctxWay.costfactor < 10000f
        expctxWay.evaluate(true, requireNotNull(data.description))
        ok = ok || expctxWay.costfactor < 10000f
        if (!ok) return
        wayDos?.let { data.writeTo(it) }
        wayCutter?.nextWay(data)
        nodeFilter?.nextWay(data)
    }

    public override fun nextRelation(data: RelationData) {
        relsParsed++
        checkStats()
        val route = data.getTag("route") ?: return
        var network = data.getTag("network")
        if (network == null) network = ""
        var state = data.getTag("state")
        if (state == null) state = ""
        writeId(cyclewayDos, data.rid)
        cyclewayDos.writeUTF(route)
        cyclewayDos.writeUTF(network)
        cyclewayDos.writeUTF(state)
        for (i in 0 until data.ways.size) {
            writeId(cyclewayDos, data.ways.get(i))
        }
        writeId(cyclewayDos, -1)
    }

    public override fun nextRestriction(
        data: RelationData,
        fromWid: Long,
        toWid: Long,
        viaNid: Long
    ) {
        val type = data.getTag("type")
        if (type != "restriction") return
        var exceptions: Short = 0
        val except = data.getTag("except")
        if (except != null) {
            exceptions = (exceptions.toInt() or toBit("bicycle", 0, except).toInt()).toShort()
            exceptions = (exceptions.toInt() or toBit("motorcar", 1, except).toInt()).toShort()
            exceptions = (exceptions.toInt() or toBit("agricultural", 2, except).toInt()).toShort()
            exceptions = (exceptions.toInt() or toBit("forestry", 2, except).toInt()).toShort()
            exceptions = (exceptions.toInt() or toBit("psv", 3, except).toInt()).toShort()
            exceptions = (exceptions.toInt() or toBit("hgv", 4, except).toInt()).toShort()
        }
        for (restrictionKey in requireNotNull(data.getTagsOrNull()).keys) {
            if (!(restrictionKey == "restriction" || restrictionKey.startsWith("restriction:"))) continue
            val res = RestrictionData().apply {
                this.restrictionKey = restrictionKey
                this.restriction = requireNotNull(data.getTag(restrictionKey))
                this.exceptions = exceptions
                this.fromWid = fromWid
                this.toWid = toWid
                this.viaNid = viaNid
            }
            restrictionsDos?.let { res.writeTo(it) }
            restrictionCutter?.nextRestriction(res)
        }
    }

    private fun checkStats() {
        if ((++recordCnt % 100000L) == 0L) {
            println(statsLine())
        }
    }

    private fun statsLine(): String =
        "records read: $recordCnt nodes=$nodesParsed ways=$waysParsed rels=$relsParsed changesets=$changesetsParsed"

    private fun getTileIndex(position: Position): Int {
        val lon = position.longitude / 45000000
        val lat = position.latitude / 30000000
        if (lon !in 0..7 || lat !in 0..5) {
            println("warning: ignoring illegal pos: ${position.longitude},${position.latitude}")
            return -1
        }
        return lon * 6 + lat
    }

    public override fun getNameForTile(tileIndex: Int): String {
        val lon = (tileIndex / 6) * 45 - 180
        val lat = (tileIndex % 6) * 30 - 90
        val slon = if (lon < 0) "W${-lon}" else "E$lon"
        val slat = if (lat < 0) "S${-lat}" else "N$lat"
        return "${slon}_${slat}.ntl"
    }

    private companion object {
        private fun toBit(tag: String, bitpos: Int, value: String): Short =
            if (value.indexOf(tag) < 0) 0 else (1 shl bitpos).toShort()
    }
}
