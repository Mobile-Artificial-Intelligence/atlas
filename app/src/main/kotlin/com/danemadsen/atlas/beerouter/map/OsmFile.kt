/**
 * cache for a single square
 *
 * @author ab
 */
package com.danemadsen.atlas.beerouter.map

import com.danemadsen.atlas.beerouter.codec.DataBuffers
import com.danemadsen.atlas.beerouter.codec.MicroCache
import com.danemadsen.atlas.beerouter.codec.MicroCache2
import com.danemadsen.atlas.beerouter.codec.StatCoderContext
import com.danemadsen.atlas.beerouter.codec.TagValueValidator
import com.danemadsen.atlas.beerouter.codec.WaypointMatcher
import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.util.ByteDataReader
import com.danemadsen.atlas.beerouter.util.Crc32.crc
import kotlinx.io.IOException

public class OsmFile(
    rafile: PhysicalFile?,
    public val lonDegree: Int,
    public val latDegree: Int,
    dataBuffers: DataBuffers
) {
    private var raf: RandomAccessReader? = null
    private var fileOffset: Long = 0

    private var posIdx: IntArray = IntArray(0)
    private var microCaches: Array<MicroCache?>? = null

    public var filename: String? = null
        internal set

    private var divisor = 0
    private var cellsize = 0
    private var indexsize = 0
    public var elevationType: Byte = 3
        internal set

    init {
        val lonMod5 = lonDegree % 5
        val latMod5 = latDegree % 5
        val tileIndex = lonMod5 * 5 + latMod5

        if (rafile != null) {
            divisor = rafile.divisor
            elevationType = rafile.elevationType

            cellsize = 1000000 / divisor
            val ncaches = divisor * divisor
            indexsize = ncaches * 4

            val iobuffer = dataBuffers.iobuffer
            filename = rafile.fileName

            val index = rafile.fileIndex
            fileOffset = if (tileIndex > 0) index[tileIndex - 1] else 200L
            if (fileOffset != index[tileIndex]) {
                raf = rafile.ra
                posIdx = IntArray(ncaches)
                microCaches = arrayOfNulls(ncaches)
                val randomAccessFile = requireNotNull(raf)
                randomAccessFile.seek(fileOffset)
                randomAccessFile.readFully(iobuffer, 0, indexsize)

                val headerCrc = crc(iobuffer, 0, indexsize)
                if (rafile.fileHeaderCrcs[tileIndex] != headerCrc) {
                    throw IOException("sub index checksum error")
                }

                val dis = ByteDataReader(iobuffer)
                for (i in 0..<ncaches) {
                    posIdx[i] = dis.readInt()
                }
            }
        }
    }

    public fun hasData(): Boolean {
        return microCaches != null
    }

    public fun getMicroCache(position: Position): MicroCache? = getMicroCache(position.longitude, position.latitude)

    public fun getMicroCache(lon: Int, lat: Int): MicroCache? {
        val lonIdx = lon / cellsize
        val latIdx = lat / cellsize
        val subIdx = (latIdx - divisor * latDegree) * divisor + (lonIdx - divisor * lonDegree)
        return microCaches!![subIdx]
    }

    /**
     * @throws IOException if an I/O error occurs or the data is corrupt
     */
    public fun createMicroCache(
        position: Position,
        dataBuffers: DataBuffers,
        wayValidator: TagValueValidator?,
        waypointMatcher: WaypointMatcher?,
        hollowNodes: OsmNodesMap?
    ): MicroCache = createMicroCache(position.longitude, position.latitude, dataBuffers, wayValidator, waypointMatcher, hollowNodes)

    /**
     * @throws IOException if an I/O error occurs or the data is corrupt
     */
    public fun createMicroCache(
        lon: Int,
        lat: Int,
        dataBuffers: DataBuffers,
        wayValidator: TagValueValidator?,
        waypointMatcher: WaypointMatcher?,
        hollowNodes: OsmNodesMap?
    ): MicroCache {
        val lonIdx = lon / cellsize
        val latIdx = lat / cellsize
        val segment = createMicroCacheForCell(
            lonIdx, latIdx, dataBuffers, wayValidator, waypointMatcher, hollowNodes
        )
        val subIdx = (latIdx - divisor * latDegree) * divisor + (lonIdx - divisor * lonDegree)
        microCaches!![subIdx] = segment
        return segment
    }

    private fun getPosIdx(idx: Int): Int {
        return if (idx == -1) indexsize else posIdx[idx]
    }

    /**
     * @throws IllegalArgumentException if this file has no data
     * @throws IOException if an I/O error occurs while reading the file
     */
    public fun getDataInputForSubIdx(subIdx: Int, iobuffer: ByteArray): Int {
        val startPos = getPosIdx(subIdx - 1)
        val endPos = getPosIdx(subIdx)
        val size = endPos - startPos
        if (size > 0) {
            val randomAccessFile = requireNotNull(raf)
            randomAccessFile.seek(fileOffset + startPos)
            if (size <= iobuffer.size) {
                randomAccessFile.readFully(iobuffer, 0, size)
            }
        }
        return size
    }

    /**
     * @throws IOException if an I/O error occurs or the data is corrupt
     */
    public fun createMicroCacheForCell(
        lonIdx: Int, latIdx: Int, dataBuffers: DataBuffers, wayValidator: TagValueValidator?,
        waypointMatcher: WaypointMatcher?, hollowNodes: OsmNodesMap?
    ): MicroCache {
        val subIdx = (latIdx - divisor * latDegree) * divisor + (lonIdx - divisor * lonDegree)

        var ab = dataBuffers.iobuffer
        var asize = getDataInputForSubIdx(subIdx, ab)

        if (asize == 0) {
            return MicroCache.emptyCache()
        }
        if (asize > ab.size) {
            ab = ByteArray(asize)
            asize = getDataInputForSubIdx(subIdx, ab)
        }

        val bc = StatCoderContext(ab)

        try {
            if (hollowNodes == null) {
                return MicroCache2(bc, dataBuffers, lonIdx, latIdx, divisor, wayValidator, waypointMatcher)
            }
            DirectWeaver(bc, dataBuffers, lonIdx, latIdx, divisor, wayValidator!!, waypointMatcher, hollowNodes)
            return MicroCache.emptyNonVirgin
        } finally {
            // crc check only if the buffer has not been fully read
            val readBytes = (bc.readingBitPosition + 7) shr 3
            if (readBytes != asize - 4) {
                val crcData = crc(ab, 0, asize - 4)
                val crcFooter = ByteDataReader(ab, asize - 4).readInt()
                if (crcData == crcFooter) {
                    throw IOException("old, unsupported data-format")
                } else if ((crcData xor 2) != crcFooter) {
                    throw IOException("checksum error")
                }
            }
        }
    }

    // set this OsmFile to ghost-state:
    public fun setGhostState(): Long {
        val caches = microCaches ?: return 0
        var sum: Long = 0
        for (i in caches.indices) {
            val mc = caches[i] ?: continue
            if (mc.virgin) {
                mc.ghost = true
                sum += mc.dataSize.toLong()
            } else {
                caches[i] = null
            }
        }
        return sum
    }

    public fun collectAll(): Long {
        val caches = microCaches ?: return 0
        var deleted: Long = 0
        for (mc in caches) {
            if (mc != null && !mc.ghost) {
                deleted += mc.collect(0).toLong()
            }
        }
        return deleted
    }

    public fun cleanGhosts(): Long {
        val caches = microCaches ?: return 0
        for (i in caches.indices) {
            if (caches[i]?.ghost == true) {
                caches[i] = null
            }
        }
        return 0
    }

    public fun clean(all: Boolean) {
        val caches = microCaches ?: return
        for (i in caches.indices) {
            val mc = caches[i] ?: continue
            if (all || !mc.virgin) {
                caches[i] = null
            }
        }
    }
}
