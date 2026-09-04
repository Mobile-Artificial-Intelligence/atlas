package com.danemadsen.atlas.beerouter.codec

import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.geo.latitudeFromId
import com.danemadsen.atlas.beerouter.geo.longitudeFromId
import com.danemadsen.atlas.beerouter.util.ByteDataReader
import com.danemadsen.atlas.beerouter.util.IByteArrayUnifier
import com.danemadsen.atlas.beerouter.router.exceptions.CacheStateException

/**
 * MicroCache2 is the new format that uses statistical encoding and
 * is able to do access filtering and waypoint matching during encoding
 */
public class MicroCache2 : MicroCache {
    private val lonBase: Int
    private val latBase: Int
    private val cellsize: Int

    public constructor(
        size: Int,
        databuffer: ByteArray,
        lonIdx: Int,
        latIdx: Int,
        divisor: Int
    ) : super(
        databuffer
    ) // sets ab=databuffer, aboffset=0
    {
        faid = IntArray(size)
        fapos = IntArray(size)
        this.size = 0
        cellsize = 1000000 / divisor
        lonBase = lonIdx * cellsize
        latBase = latIdx * cellsize
    }

    /**
     * Read a unified byte array from the cache.
     *
     * @param len the length to read
     * @param u the byte array unifier
     * @return the unified byte array, or null if not unified
     * @throws IndexOutOfBoundsException if reading beyond the buffer bounds
     */
    public fun readUnified(len: Int, u: IByteArrayUnifier): ByteArray? {
        val b = u.unify(ab, aboffset, len)
        aboffset += len
        return b
    }

    public constructor(
        bc: StatCoderContext,
        dataBuffers: DataBuffers,
        lonIdx: Int,
        latIdx: Int,
        divisor: Int,
        wayValidator: TagValueValidator?,
        waypointMatcher: WaypointMatcher?
    ) : super() {
        cellsize = 1000000 / divisor
        lonBase = lonIdx * cellsize
        latBase = latIdx * cellsize

        val wayTagCoder = TagValueCoder(bc, dataBuffers, wayValidator)
        val nodeTagCoder = TagValueCoder.rawDataDecoder(bc, dataBuffers, null)
        val nodeIdxDiff = NoisyDiffCoder(bc)
        val nodeEleDiff = NoisyDiffCoder(bc)
        val extLonDiff = NoisyDiffCoder(bc)
        val extLatDiff = NoisyDiffCoder(bc)
        val transEleDiff = NoisyDiffCoder(bc)

        size = bc.decodeNoisyNumber(5)
        faid = if (size > dataBuffers.ibuf2.size) IntArray(size) else dataBuffers.ibuf2
        fapos = if (size > dataBuffers.ibuf3.size) IntArray(size) else dataBuffers.ibuf3


        val alon = if (size > dataBuffers.alon.size) IntArray(size) else dataBuffers.alon
        val alat = if (size > dataBuffers.alat.size) IntArray(size) else dataBuffers.alat

        //        if (MicroCache.debug) println("*** decoding cache of size=" + size + " for lonIdx=" + lonIdx + " latIdx=" + latIdx)

        bc.decodeSortedArray(faid, 0, size, 29, 0)

        for (n in 0..<size) {
            val id64 = expandId(faid[n])
            alon[n] = id64.longitudeFromId()
            alat[n] = id64.latitudeFromId()
        }

        val netdatasize = bc.decodeNoisyNumber(10)
        ab = if (netdatasize > dataBuffers.bbuf1.size) ByteArray(netdatasize) else dataBuffers.bbuf1
        aboffset = 0

        val validBits = IntArray((size + 31) shr 5)

        var finaldatasize = 0

        val reverseLinks = LinkedListContainer(size, dataBuffers.ibuf1)

        var selev = 0
        for (n in 0..<size) { // loop over nodes
            val ilon = alon[n]
            val ilat = alat[n]

            // future escapes (turn restrictions?)
            var trExceptions: Short = 0
            var featureId = bc.decodeVarBits()
            if (featureId == 13) {
                fapos[n] = aboffset
                validBits[n shr 5] = validBits[n shr 5] or (1 shl n) // mark dummy-node valid
                continue  // empty node escape (delta files only)
            }
            while (featureId != 0) {
                val bitsize = bc.decodeNoisyNumber(5)

                when (featureId) {
                    2 -> { // exceptions to turn-restriction
                        trExceptions = bc.decodeBounded(1023).toShort()
                    }

                    1 -> { // turn-restriction
                        writeBoolean(true)
                        writeShort(trExceptions.toInt()) // exceptions from previous feature
                        trExceptions = 0

                        writeBoolean(bc.decodeBit()) // isPositive
                        writeInt(ilon + bc.decodeNoisyDiff(10)) // fromLon
                        writeInt(ilat + bc.decodeNoisyDiff(10)) // fromLat
                        writeInt(ilon + bc.decodeNoisyDiff(10)) // toLon
                        writeInt(ilat + bc.decodeNoisyDiff(10)) // toLat
                    }

                    else -> {
                        for (i in 0..<bitsize) bc.decodeBit() // unknown feature, just skip
                    }
                }
                featureId = bc.decodeVarBits()
            }
            writeBoolean(false)

            selev += nodeEleDiff.decodeSignedValue()
            writeShort(selev.toShort().toInt())
            val nodeTags = nodeTagCoder.decodeTagValueData()
            writeVarBytes(nodeTags)
            if (nodeTags != null) {
                validBits[n shr 5] = validBits[n shr 5] or (1 shl n)
            }

            val links = bc.decodeNoisyNumber(1)
            //            if (MicroCache.debug) println("***   decoding node " + ilon + "/" + ilat + " with links=" + links)
            for (li in 0..<links) {
                var sizeoffset = 0
                val nodeIdx = n + nodeIdxDiff.decodeSignedValue()

                var dlonRemaining: Int
                var dlatRemaining: Int

                var isReverse = false
                if (nodeIdx != n) { // internal (forward-) link
                    dlonRemaining = alon[nodeIdx] - ilon
                    dlatRemaining = alat[nodeIdx] - ilat
                } else {
                    isReverse = bc.decodeBit()
                    dlonRemaining = extLonDiff.decodeSignedValue()
                    dlatRemaining = extLatDiff.decodeSignedValue()
                }
                //                if (MicroCache.debug) println("***     decoding link to " + (ilon + dlonRemaining) + "/" + (ilat + dlatRemaining) + " extern=" + (nodeIdx == n))

                val wayTags = wayTagCoder.decodeTagValueSet()

                val linkValid = wayTags != null || wayValidator == null
                if (linkValid) {
                    val startPointer = aboffset
                    sizeoffset = writeSizePlaceHolder()

                    writeVarLengthSigned(dlonRemaining)
                    writeVarLengthSigned(dlatRemaining)

                    validBits[n shr 5] = validBits[n shr 5] or (1 shl n) // mark source-node valid
                    if (nodeIdx != n) { // valid internal (forward-) link
                        reverseLinks.addDataElement(nodeIdx, n) // register reverse link
                        finaldatasize += 1 + aboffset - startPointer // reserve place for reverse
                        validBits[nodeIdx shr 5] =
                            validBits[nodeIdx shr 5] or (1 shl nodeIdx) // mark target-node valid
                    }
                    writeModeAndDesc(isReverse, wayTags?.data)
                }

                if (!isReverse) { // write geometry for forward links only
                    var matcher =
                        if (wayTags == null || wayTags.accessType < 2) null else waypointMatcher
                    val ilontarget = ilon + dlonRemaining
                    val ilattarget = ilat + dlatRemaining
                    if (matcher != null) {
                        val useAsStartWay = wayValidator!!.checkStartWay(wayTags!!.data)
                        if (!matcher.start(
                                ilon,
                                ilat,
                                ilontarget,
                                ilattarget,
                                useAsStartWay
                            )
                        ) {
                            matcher = null
                        }
                    }

                    val transcount = bc.decodeVarBits()
                    //                    if (MicroCache.debug) println("***       decoding geometry with count=" + transcount)
                    var count = transcount + 1
                    for (i in 0..<transcount) {
                        val dlon = bc.decodePredictedValue(dlonRemaining / count)
                        val dlat = bc.decodePredictedValue(dlatRemaining / count)
                        dlonRemaining -= dlon
                        dlatRemaining -= dlat
                        count--
                        val elediff = transEleDiff.decodeSignedValue()
                        if (wayTags != null) {
                            writeVarLengthSigned(dlon)
                            writeVarLengthSigned(dlat)
                            writeVarLengthSigned(elediff)
                        }

                        matcher?.transferNode(ilontarget - dlonRemaining, ilattarget - dlatRemaining)
                    }
                    matcher?.end()
                }
                if (linkValid) {
                    injectSize(sizeoffset)
                }
            }
            fapos[n] = aboffset
        }

        // calculate final data size
        var finalsize = 0
        var startpos = 0
        for (i in 0..<size) {
            val endpos = fapos[i]
            if ((validBits[i shr 5] and (1 shl i)) != 0) {
                finaldatasize += endpos - startpos
                finalsize++
            }
            startpos = endpos
        }
        // append the reverse links at the end of each node
        val abOld = ab
        val faidOld = faid
        val faposOld = fapos
        val sizeOld = size
        ab = ByteArray(finaldatasize)
        faid = IntArray(finalsize)
        fapos = IntArray(finalsize)
        aboffset = 0
        size = 0

        startpos = 0
        for (n in 0..<sizeOld) {
            val endpos = faposOld[n]
            if ((validBits[n shr 5] and (1 shl n)) != 0) {
                val len = endpos - startpos
                abOld.copyInto(ab, aboffset, startpos, startpos + len)
                //                if (MicroCache.debug) println("*** copied " + len + " bytes from " + aboffset + " for node " + n)
                aboffset += len

                val cnt = reverseLinks.initList(n)
                //                if (MicroCache.debug) println("*** appending " + cnt + " reverse links for node " + n)

                for (ri in 0..<cnt) {
                    val nodeIdx = reverseLinks.dataElement
                    val sizeoffset = writeSizePlaceHolder()
                    writeVarLengthSigned(alon[nodeIdx] - alon[n])
                    writeVarLengthSigned(alat[nodeIdx] - alat[n])
                    writeModeAndDesc(true, null)
                    injectSize(sizeoffset)
                }
                faid[size] = faidOld[n]
                fapos[size] = aboffset
                size++
            }
            startpos = endpos
        }
        init(size)
    }

    public override fun expandId(id32: Int): Long {
        var id32 = id32
        var dlon = 0
        var dlat = 0

        var bm = 1
        while (bm < 0x8000) {
            if ((id32 and 1) != 0) dlon = dlon or bm
            if ((id32 and 2) != 0) dlat = dlat or bm
            id32 = id32 shr 2
            bm = bm shl 1
        }

        val lon32 = lonBase + dlon
        val lat32 = latBase + dlat

        return Position.computeId(lon32, lat32)
    }

    public override fun shrinkId(id64: Long): Int {
        val lon32 = id64.longitudeFromId()
        val lat32 = id64.latitudeFromId()
        val dlon = lon32 - lonBase
        val dlat = lat32 - latBase
        var id32 = 0

        var bm = 0x4000
        while (bm > 0) {
            id32 = id32 shl 2
            if ((dlon and bm) != 0) id32 = id32 or 1
            if ((dlat and bm) != 0) id32 = id32 or 2
            bm = bm shr 1
        }
        return id32
    }

    public override fun isInternal(position: Position): Boolean {
        return position.longitude >= lonBase &&
                position.longitude < lonBase + cellsize &&
                position.latitude >= latBase &&
                position.latitude < latBase + cellsize
    }

    /** LOCAL PATCH (Atlas): allocation-free variant of [isInternal]. */
    public override fun isInternal(longitude: Int, latitude: Int): Boolean {
        return longitude >= lonBase &&
                longitude < lonBase + cellsize &&
                latitude >= latBase &&
                latitude < latBase + cellsize
    }

    /**
     * Encodes this micro-cache into the given buffer.
     *
     * @param buffer byte array to encode into
     * @return the size of the encoded data
     * @throws IllegalStateException if a self-referencing node is encountered
     */
    public override fun encodeMicroCache(buffer: ByteArray): Int {
        val idMap = mutableMapOf<Long, Int>()
        for (n in 0..<size) { // loop over nodes
            idMap[expandId(faid[n])] = n
        }

        val linkCounts = IntegerFifo3Pass(256)
        val transCounts = IntegerFifo3Pass(256)
        val restrictionBits = IntegerFifo3Pass(16)

        val wayTagCoder = TagValueCoder()
        val nodeTagCoder = TagValueCoder()
        val nodeIdxDiff = NoisyDiffCoder()
        val nodeEleDiff = NoisyDiffCoder()
        val extLonDiff = NoisyDiffCoder()
        val extLatDiff = NoisyDiffCoder()
        val transEleDiff = NoisyDiffCoder()

        var netdatasize = 0

        var pass = 1
        while (true) {
            // 3 passes: counters, stat-collection, encoding
            val dostats = pass == 3
            val dodebug = false // MicroCache.debug && pass == 3

            if (pass < 3) netdatasize = fapos[size - 1]

            val bc = StatCoderContext(buffer)

            linkCounts.init()
            transCounts.init()
            restrictionBits.init()

            wayTagCoder.encodeDictionary(bc)
            if (dostats) bc.assignBits("wayTagDictionary")
            nodeTagCoder.encodeDictionary(bc)
            if (dostats) bc.assignBits("nodeTagDictionary")
            nodeIdxDiff.encodeDictionary(bc)
            nodeEleDiff.encodeDictionary(bc)
            extLonDiff.encodeDictionary(bc)
            extLatDiff.encodeDictionary(bc)
            transEleDiff.encodeDictionary(bc)
            if (dostats) bc.assignBits("noisebits")
            bc.encodeNoisyNumber(size, 5)
            if (dostats) bc.assignBits("nodecount")
            bc.encodeSortedArray(faid, 0, size, 0x20000000, 0)
            if (dostats) bc.assignBits("node-positions")
            bc.encodeNoisyNumber(netdatasize, 10) // net-size
            if (dostats) bc.assignBits("netdatasize")
            if (dodebug) println("*** encoding cache of size=$size")
            var lastSelev = 0

            for (n in 0..<size) { // loop over nodes
                aboffset = startPos(n)
                aboffsetEnd = fapos[n]
                if (dodebug) println("*** encoding node $n from $aboffset to $aboffsetEnd")

                val id64 = expandId(faid[n])
                val ilon = id64.longitudeFromId()
                val ilat = id64.latitudeFromId()

                if (aboffset == aboffsetEnd) {
                    bc.encodeVarBits(13) // empty node escape (delta files only)
                    continue
                }

                // write turn restrictions
                while (readBoolean()) {
                    val exceptions = readShort() // except bikes, psv, ...
                    if (exceptions.toInt() != 0) {
                        bc.encodeVarBits(2) // 2 = tr exceptions
                        bc.encodeNoisyNumber(10, 5) // bit-count
                        bc.encodeBounded(1023, exceptions.toInt() and 1023)
                    }
                    bc.encodeVarBits(1) // 1 = turn restriction
                    bc.encodeNoisyNumber(
                        restrictionBits.next,
                        5
                    ) // bit-count using look-ahead fifo
                    val b0 = bc.writingBitPosition.toLong()
                    bc.encodeBit(readBoolean()) // isPositive
                    bc.encodeNoisyDiff(readInt() - ilon, 10) // fromLon
                    bc.encodeNoisyDiff(readInt() - ilat, 10) // fromLat
                    bc.encodeNoisyDiff(readInt() - ilon, 10) // toLon
                    bc.encodeNoisyDiff(readInt() - ilat, 10) // toLat
                    restrictionBits.add((bc.writingBitPosition - b0).toInt())
                }
                bc.encodeVarBits(0) // end of extra data

                if (dostats) bc.assignBits("extradata")

                val selev = readShort().toInt()
                nodeEleDiff.encodeSignedValue(selev - lastSelev)
                if (dostats) bc.assignBits("nodeele")
                lastSelev = selev
                nodeTagCoder.encodeTagValueSet(readVarBytes())
                if (dostats) bc.assignBits("nodeTagIdx")
                var nlinks = linkCounts.next
                if (dodebug) println("*** nlinks=$nlinks")
                bc.encodeNoisyNumber(nlinks, 1)
                if (dostats) bc.assignBits("link-counts")

                nlinks = 0
                while (hasMoreData()) { // loop over links
                    // read link data
                    val startPointer = aboffset
                    val endPointer = endPointer

                    val ilonlink = ilon + readVarLengthSigned()
                    val ilatlink = ilat + readVarLengthSigned()

                    val sizecode = readVarLengthUnsigned()
                    val isReverse = (sizecode and 1) != 0
                    val descSize = sizecode shr 1
                    var description: ByteArray? = null
                    if (descSize > 0) {
                        description = ByteArray(descSize)
                        readFully(description)
                    }

                    val link64 = Position.computeId(ilonlink, ilatlink)
                    val idx = idMap[link64]
                    val isInternal = idx != null

                    if (isReverse && isInternal) {
                        if (dodebug) println("*** NOT encoding link reverse=$isReverse internal=$isInternal")
                        netdatasize -= aboffset - startPointer
                        continue  // do not encode internal reverse links
                    }
                    if (dodebug) println("*** encoding link reverse=$isReverse internal=$isInternal")
                    nlinks++

                    if (isInternal) {
                        val nodeIdx = idx
                        if (dodebug) println("*** target nodeIdx=$nodeIdx")
                        if (nodeIdx == n) throw CacheStateException("self reference in node encoding")
                        nodeIdxDiff.encodeSignedValue(nodeIdx - n)
                        if (dostats) bc.assignBits("nodeIdx")
                    } else {
                        nodeIdxDiff.encodeSignedValue(0)
                        bc.encodeBit(isReverse)
                        extLonDiff.encodeSignedValue(ilonlink - ilon)
                        extLatDiff.encodeSignedValue(ilatlink - ilat)
                        if (dostats) bc.assignBits("externalNode")
                    }
                    wayTagCoder.encodeTagValueSet(description)
                    if (dostats) bc.assignBits("wayDescIdx")

                    if (!isReverse) {
                        val geometry = readDataUntil(endPointer)
                        // write transition nodes
                        var count = transCounts.next
                        if (dodebug) println("*** encoding geometry with count=$count")
                        bc.encodeVarBits(count++)
                        if (dostats) bc.assignBits("transcount")
                        var transcount = 0
                        if (geometry != null) {
                            var dlonRemaining = ilonlink - ilon
                            var dlatRemaining = ilatlink - ilat

                            val r = ByteDataReader(geometry)
                            while (r.hasMoreData()) {
                                transcount++

                                val dlon = r.readVarLengthSigned()
                                val dlat = r.readVarLengthSigned()
                                bc.encodePredictedValue(dlon, dlonRemaining / count)
                                bc.encodePredictedValue(dlat, dlatRemaining / count)
                                dlonRemaining -= dlon
                                dlatRemaining -= dlat
                                if (count > 1) count--
                                if (dostats) bc.assignBits("transpos")
                                transEleDiff.encodeSignedValue(r.readVarLengthSigned())
                                if (dostats) bc.assignBits("transele")
                            }
                        }
                        transCounts.add(transcount)
                    }
                }
                linkCounts.add(nlinks)
            }
            if (pass == 3) {
                return bc.closeAndGetEncodedLength()
            }
            pass++
        }
    }
}
