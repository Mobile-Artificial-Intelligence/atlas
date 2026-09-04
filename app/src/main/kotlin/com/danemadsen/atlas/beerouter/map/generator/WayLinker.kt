package com.danemadsen.atlas.beerouter.map.generator

import androidx.collection.MutableLongObjectMap
import androidx.collection.MutableLongSet
import com.danemadsen.atlas.beerouter.codec.DataBuffers
import com.danemadsen.atlas.beerouter.codec.MicroCache
import com.danemadsen.atlas.beerouter.codec.MicroCache2
import com.danemadsen.atlas.beerouter.codec.StatCoderContext
import com.danemadsen.atlas.beerouter.expressions.BExpressionContextWay
import com.danemadsen.atlas.beerouter.expressions.BExpressionMetaData
import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.util.ByteArrayUnifier
import com.danemadsen.atlas.beerouter.util.Crc32
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.util.TreeMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

public class WayLinker : GeneratorBase(), Runnable {
    private lateinit var nodeTilesIn: File
    private lateinit var wayTilesIn: File
    private lateinit var dataTilesOut: File
    private lateinit var borderFileIn: File
    private lateinit var dataTilesSuffix: String
    private var readingBorder: Boolean = false
    private lateinit var nodesMap: MutableLongObjectMap<OsmNodeP>
    private var nodesList: MutableList<OsmNodeP>? = null
    private lateinit var borderSet: MutableLongSet
    private var lookupVersion: Short = 0
    private var lookupMinorVersion: Short = 0
    private var creationTimeStamp: Long = 0
    private var elevationType: Byte = 0
    private lateinit var expctxWay: BExpressionContextWay
    private lateinit var abUnifier: ByteArrayUnifier
    private var minLon: Int = -1
    private var minLat: Int = -1
    private val microCacheEncoding: Int = 2
    private val divisor: Int = 32
    private val cellsize: Int = 1_000_000 / divisor
    private var skipEncodingCheck: Boolean = false
    private var isSlave: Boolean = false
    private lateinit var tc: ThreadController

    public class ThreadController {
        private val lock = ReentrantLock()
        private val stateChanged = lock.newCondition()

        public var maxFileSize: Long = 0L
        public var currentSlaveSize: Long = 0L
        public var currentMasterSize: Long = 2_000_000_000L

        public fun setCurrentMasterSize(size: Long): Boolean {
            lock.withLock {
                if (size <= currentSlaveSize) {
                    maxFileSize = Long.MAX_VALUE
                    stateChanged.signalAll()
                    return false
                }
                currentMasterSize = size
                if (maxFileSize == 0L) {
                    maxFileSize = size
                }
                stateChanged.signalAll()
                return true
            }
        }

        public fun setCurrentSlaveSize(size: Long): Boolean {
            lock.withLock {
                if (size >= currentMasterSize) {
                    return false
                }
                while (size + currentMasterSize + 50_000_000L > maxFileSize) {
                    println(
                        "****** slave thread waiting for permission to process file of size $size " +
                                "currentMaster=$currentMasterSize maxFileSize=$maxFileSize"
                    )
                    stateChanged.awaitNanos(10_000_000_000L)
                }
                currentSlaveSize = size
                return true
            }
        }
    }

    private fun reset() {
        minLon = -1
        minLat = -1
        nodesMap = MutableLongObjectMap()
        borderSet = MutableLongSet()
    }

    /**
     * LOCAL PATCH (Atlas): dev-only phase instrumentation. Null in production
     * (the app never sets it), so the probe calls cost one null check. The
     * build CLI registers a callback to print heap use at each linker phase,
     * which is how the on-device OOM was localized to this class's node map.
     */
    public companion object {
        public var onPhase: ((phase: String) -> Unit)? = null
    }

    private fun probe(phase: String) {
        onPhase?.invoke(phase)
    }

    public fun process(
        nodeTilesIn: File,
        wayTilesIn: File,
        borderFileIn: File,
        restrictionsFileIn: File,
        lookupFile: File,
        profileFile: File,
        dataTilesOut: File,
        dataTilesSuffix: String,
        // LOCAL PATCH (Atlas): heap-constrained devices run the linker
        // single-threaded. The master/slave pair exists to overlap two way
        // files within one assumed memory budget; on Android's largeHeap
        // (512-576 MB) one 5-degree metro file's node map is all that fits,
        // so the slave only doubles the peak. With no slave started,
        // currentSlaveSize stays 0 and the master's wayFileStart never
        // declines a file — every file is processed, just sequentially.
        disableSlave: Boolean = false,
    ) {
        val master = WayLinker()
        val controller = ThreadController()
        master.isSlave = false
        master.tc = controller
        master.processInternal(
            nodeTilesIn,
            wayTilesIn,
            borderFileIn,
            restrictionsFileIn,
            lookupFile,
            profileFile,
            dataTilesOut,
            dataTilesSuffix
        )
        val m = Thread(master)
        if (!disableSlave) {
            val slave = WayLinker()
            slave.isSlave = true
            slave.tc = controller
            slave.processInternal(
                nodeTilesIn,
                wayTilesIn,
                borderFileIn,
                restrictionsFileIn,
                lookupFile,
                profileFile,
                dataTilesOut,
                dataTilesSuffix
            )
            val s = Thread(slave)
            m.start()
            s.start()
            s.join()
        } else {
            m.start()
        }
        m.join()
    }

    private fun processInternal(
        nodeTilesIn: File,
        wayTilesIn: File,
        borderFileIn: File,
        restrictionsFileIn: File,
        lookupFile: File,
        profileFile: File,
        dataTilesOut: File,
        dataTilesSuffix: String,
    ) {
        this.nodeTilesIn = nodeTilesIn
        this.wayTilesIn = wayTilesIn
        this.dataTilesOut = dataTilesOut
        this.borderFileIn = borderFileIn
        this.dataTilesSuffix = dataTilesSuffix

        val meta = BExpressionMetaData()
        expctxWay = BExpressionContextWay(meta)
        meta.readMetaData(lookupFile.readText())
        lookupVersion = meta.lookupVersion
        lookupMinorVersion = meta.lookupMinorVersion
        expctxWay.parseProfile(profileFile.readText(), "global")
        creationTimeStamp = System.currentTimeMillis()
        abUnifier = ByteArrayUnifier(16384, false)
        skipEncodingCheck = java.lang.Boolean.getBoolean("skipEncodingCheck")
    }

    public override fun run() {
        try {
            // Both worker threads read from the same directory with different size ordering.
            // Do not delete input tiles during iteration or one thread can race the other.
            WayIterator(this, false, !isSlave).processDir(wayTilesIn, ".wt5")
        } catch (e: Exception) {
            throw RuntimeException(e)
        } finally {
            if (!isSlave) {
                tc.setCurrentMasterSize(0L)
            }
        }
    }

    public override fun wayFileStart(wayfile: File): Boolean {
        val filesize = wayfile.length()
        if (isSlave) {
            if (!tc.setCurrentSlaveSize(filesize)) return false
        } else {
            if (!tc.setCurrentMasterSize(filesize)) return false
        }

        elevationType = 3
        var nodeFile = fileFromTemplate(wayfile, nodeTilesIn, "u5d_1")
        if (nodeFile.exists()) {
            elevationType = 1
        } else {
            nodeFile = fileFromTemplate(wayfile, nodeTilesIn, "u5d_3")
            if (!nodeFile.exists()) {
                nodeFile = fileFromTemplate(wayfile, nodeTilesIn, "u5d")
            }
        }
        if (nodeFile.exists()) {
            reset()
            readingBorder = true
            NodeIterator(this, false).processFile(borderFileIn)
            readingBorder = false
            NodeIterator(this, true).processFile(nodeFile)
            val restrictionFile = fileFromTemplate(
                wayfile,
                File(requireNotNull(nodeTilesIn.parentFile), "restrictions55"),
                "rt5"
            )
            if (restrictionFile.exists()) {
                val di = DataInputStream(BufferedInputStream(FileInputStream(restrictionFile)))
                try {
                    while (true) {
                        val res = RestrictionData(di)
                        var node = nodesMap.get(res.viaNid)
                        if (node != null) {
                            if (node !is OsmNodePT) {
                                node = OsmNodePT(node)
                                nodesMap[res.viaNid] = node
                            }
                            res.viaPosition = Position(node.longitude, node.latitude)
                            node.firstRestrictionData =
                                res.also { it.next = node.firstRestrictionData }
                        }
                    }
                } catch (_: EOFException) {
                    di.close()
                }
            }
            val sortedNodeIds = LongArray(nodesMap.size)
            var nodeIndex = 0
            nodesMap.forEachKey { nid ->
                sortedNodeIds[nodeIndex++] = nid
            }
            sortedNodeIds.sort()
            nodesList = ArrayList<OsmNodeP>(sortedNodeIds.size).also { values ->
                for (nid in sortedNodeIds) {
                    values.add(requireNotNull(nodesMap.get(nid)) { "missing node $nid after sorting" })
                }
            }
            probe("wayFileStart:done nodes=${nodesMap.size}")
            return true
        }
        return false
    }

    public override fun nextNode(data: NodeData) {
        val node = if (data.description == null) {
            OsmNodeP()
        } else {
            OsmNodePT(data.description, data.retainWithoutLinks)
        }
        // LOCAL PATCH (Atlas): copy the DTO's scalars; no Position is retained
        node.longitude = data.position.longitude
        node.latitude = data.position.latitude
        node.altitude = data.position.altitude
        if (readingBorder || !borderSet.contains(data.nid)) {
            nodesMap[data.nid] = node
        }
        if (readingBorder) {
            node.bits = (node.bits.toInt() or OsmNodeP.BORDER_BIT).toByte()
            borderSet.add(data.nid)
            return
        }
        val minLon = (node.longitude / 5_000_000) * 5_000_000
        val minLat = (node.latitude / 5_000_000) * 5_000_000
        if (this.minLon == -1) this.minLon = minLon
        if (this.minLat == -1) this.minLat = minLat
        if (this.minLon != minLon || this.minLat != minLat) {
            throw IllegalArgumentException("inconsistent node: ${node.longitude} ${node.latitude}")
        }
    }

    private fun checkRestriction(n1: OsmNodeP, n2: OsmNodeP, w: WayData) {
        checkRestriction(n1, n2, w, true)
        checkRestriction(n2, n1, w, false)
    }

    private fun checkRestriction(n1: OsmNodeP, n2: OsmNodeP, w: WayData, checkFrom: Boolean) {
        var restriction = n2.getFirstRestriction()
        while (restriction != null) {
            if (restriction.fromWid == w.wid && (restriction.fromPosition.longitude == 0 || checkFrom)) {
                restriction.fromPosition = Position(n1.longitude, n1.latitude)
                n1.bits = (n1.bits.toInt() or OsmNodeP.DP_SURVIVOR_BIT).toByte()
                if (!isEndNode(n2, w)) restriction.badWayMatch = true
            }
            if (restriction.toWid == w.wid && (restriction.toPosition.longitude == 0 || !checkFrom)) {
                restriction.toPosition = Position(n1.longitude, n1.latitude)
                n1.bits = (n1.bits.toInt() or OsmNodeP.DP_SURVIVOR_BIT).toByte()
                if (!isEndNode(n2, w)) restriction.badWayMatch = true
            }
            restriction = restriction.next
        }
    }

    private fun isEndNode(node: OsmNodeP, way: WayData): Boolean =
        node === nodesMap.get(way.nodes.get(0)) || node === nodesMap.get(way.nodes.get(way.nodes.size - 1))

    public override fun nextWay(data: WayData) {
        val description = abUnifier.unify(requireNotNull(data.description))
        expctxWay.evaluate(false, description)
        var ok = expctxWay.costfactor < 10000f
        expctxWay.evaluate(true, description)
        ok = ok || expctxWay.costfactor < 10000f
        if (!ok) return

        var wayBits: Byte = 0
        expctxWay.decode(description)
        if (!expctxWay.getBooleanLookupValue("bridge")) wayBits =
            (wayBits.toInt() or OsmNodeP.NO_BRIDGE_BIT).toByte()
        if (!expctxWay.getBooleanLookupValue("tunnel")) wayBits =
            (wayBits.toInt() or OsmNodeP.NO_TUNNEL_BIT).toByte()

        var n1: OsmNodeP? = null
        var n2: OsmNodeP? = null
        for (i in 0 until data.nodes.size) {
            val nid = data.nodes.get(i)
            n1 = n2
            n2 = nodesMap.get(nid)
            if (n1 != null && n2 != null && n1 !== n2) {
                checkRestriction(n1, n2, data)
                val link = n2.createLink(n1)
                link.descriptionBitmap = description
                if (n1.longitude / cellsize != n2.longitude / cellsize || n1.latitude / cellsize != n2.latitude / cellsize) {
                    n2.incWayCount()
                }
            }
            if (n2 != null) {
                n2.bits = (n2.bits.toInt() or wayBits.toInt()).toByte()
                n2.incWayCount()
            }
        }
    }

    public override fun wayFileEnd(wayfile: File) {
        probe("wayFileEnd:start ${wayfile.name}")
        val ncaches = divisor * divisor
        val indexsize = ncaches * 4
        val localNodesList = nodesList ?: return

        nodesMap = MutableLongObjectMap()
        borderSet = MutableLongSet()
        probe("wayFileEnd:mapsCleared")

        val abBuf1 = ByteArray(10 * 1024 * 1024)
        val abBuf2 = ByteArray(10 * 1024 * 1024)
        val maxLon = minLon + 5_000_000
        val maxLat = minLat + 5_000_000

        for (node in localNodesList) {
            if ((node.getFirstLink() == null && !node.retainWithoutLinks()) || node.isTransferNode()) continue
            node.checkDuplicateTargets()
        }

        val nLonSegs = (maxLon - minLon) / 1_000_000
        val nLatSegs = (maxLat - minLat) / 1_000_000
        val seglists: Array<MutableList<OsmNodeP>?> = arrayOfNulls(nLonSegs * nLatSegs)
        for (node in localNodesList) {
            if ((node.getFirstLink() == null && !node.retainWithoutLinks()) || node.isTransferNode()) continue
            if (node.longitude < minLon || node.longitude >= maxLon || node.latitude < minLat || node.latitude >= maxLat) continue
            val lonIdx = (node.longitude - minLon) / 1_000_000
            val latIdx = (node.latitude - minLat) / 1_000_000
            val idx = lonIdx * nLatSegs + latIdx
            val list = seglists[idx] ?: ArrayList<OsmNodeP>().also { seglists[idx] = it }
            list.add(node)
        }

        val outfile = fileFromTemplate(wayfile, dataTilesOut, dataTilesSuffix)
        val os = createOutStream(outfile)
        val fileIndex = LongArray(25)
        val fileHeaderCrcs = IntArray(25)
        repeat(25) { os.writeLong(0L) }
        var filepos = 200L

        for (lonIdx in 0 until nLonSegs) {
            for (latIdx in 0 until nLatSegs) {
                val tileIndex = lonIdx * nLatSegs + latIdx
                val nlist = seglists[tileIndex]
                if (nlist != null && nlist.isNotEmpty()) {
                    val subs: Array<MutableList<OsmNodeP>?> = arrayOfNulls(ncaches)
                    val subByteArrays: Array<ByteArray?> = arrayOfNulls(ncaches)
                    for (node in nlist) {
                        val subLonIdx =
                            (node.longitude - minLon) / cellsize - divisor * lonIdx
                        val subLatIdx =
                            (node.latitude - minLat) / cellsize - divisor * latIdx
                        val si = subLatIdx * divisor + subLonIdx
                        val list = subs[si] ?: ArrayList<OsmNodeP>().also { subs[si] = it }
                        list.add(node)
                    }
                    val posIdx = IntArray(ncaches)
                    var pos = indexsize
                    for (si in 0 until ncaches) {
                        val subList = subs[si]
                        if (subList != null && subList.isNotEmpty()) {
                            val n0 = subList[0]
                            val lonIdxDiv = n0.longitude / cellsize
                            val latIdxDiv = n0.latitude / cellsize
                            val mc: MicroCache =
                                MicroCache2(subList.size, abBuf2, lonIdxDiv, latIdxDiv, divisor)
                            val sortedList = TreeMap<Int, OsmNodeP>()
                            for (node in subList) {
                                val longId = node.idFromPos
                                val shrinkid = mc.shrinkId(longId)
                                require(mc.expandId(shrinkid) == longId) { "inconstistent shrinking: $longId" }
                                sortedList[shrinkid] = node
                            }
                            for (node in sortedList.values) {
                                node.writeNodeData(mc)
                            }
                            if (mc.size > 0) {
                                var subBytes: ByteArray
                                while (true) {
                                    val len = mc.encodeMicroCache(abBuf1)
                                    subBytes = abBuf1.copyOf(len)
                                    if (skipEncodingCheck) break
                                    val mc2 = MicroCache2(
                                        StatCoderContext(subBytes),
                                        DataBuffers(),
                                        lonIdxDiv,
                                        latIdxDiv,
                                        divisor,
                                        null,
                                        null,
                                    )
                                    val diffMessage = mc.compareWith(mc2)
                                    if (diffMessage == null) {
                                        break
                                    }
                                    throw RuntimeException("encoding crosscheck failed: $diffMessage")
                                }
                                pos += subBytes.size + 4
                                subByteArrays[si] = subBytes
                            }
                        }
                        posIdx[si] = pos
                    }
                    val abSubIndex = compileSubFileIndex(posIdx)
                    fileHeaderCrcs[tileIndex] = Crc32.crc(abSubIndex, 0, abSubIndex.size)
                    os.write(abSubIndex)
                    for (ab in subByteArrays) {
                        if (ab != null) {
                            os.write(ab)
                            os.writeInt(Crc32.crc(ab, 0, ab.size) xor microCacheEncoding)
                        }
                    }
                    filepos += pos.toLong()
                }
                fileIndex[tileIndex] = filepos
                probe("wayFileEnd:tile[$tileIndex] pos=$filepos")
            }
        }

        val abFileIndex = compileFileIndex(fileIndex, lookupVersion, lookupMinorVersion)
        os.writeLong(creationTimeStamp)
        os.writeInt(Crc32.crc(abFileIndex, 0, abFileIndex.size) xor microCacheEncoding)
        for (i in 0 until 25) {
            os.writeInt(fileHeaderCrcs[i])
        }
        os.writeByte(elevationType.toInt())
        os.close()

        RandomAccessFile(outfile, "rw").use { ra ->
            ra.write(abFileIndex)
        }
        probe("wayFileEnd:done ${wayfile.name}")
        println("**** codec stats: *******\n${StatCoderContext.bitReport}")
    }

    private fun compileFileIndex(
        fileIndex: LongArray,
        lookupVersion: Short,
        lookupMinorVersion: Short
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { dos ->
            for (i55 in 0 until 25) {
                var versionPrefix =
                    if (i55 == 1) lookupMinorVersion.toLong() else lookupVersion.toLong()
                versionPrefix = versionPrefix shl 48
                dos.writeLong(fileIndex[i55] or versionPrefix)
            }
        }
        return bos.toByteArray()
    }

    private fun compileSubFileIndex(posIdx: IntArray): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { dos ->
            for (value in posIdx) {
                dos.writeInt(value)
            }
        }
        return bos.toByteArray()
    }
}
