/**
 * cache for a single square
 *
 * @author ab
 */
package com.danemadsen.atlas.beerouter.map

import com.danemadsen.atlas.beerouter.codec.DataBuffers
import com.danemadsen.atlas.beerouter.util.ByteDataReader
import com.danemadsen.atlas.beerouter.util.Crc32.crc
import kotlinx.io.IOException

public class PhysicalFile(
    public val fileName: String,
    mapSource: MapSource,
    dataBuffers: DataBuffers,
    lookupVersion: Int,
    lookupMinorVersion: Int
) {
    public val ra: RandomAccessReader = mapSource.open(fileName)
    public val fileIndex: LongArray = LongArray(25)
    public var fileHeaderCrcs: IntArray = IntArray(0)

    public var creationTime: Long = 0L
        internal set

    public var divisor: Int = 80
        internal set
    public var elevationType: Byte = 3
        internal set

    init {
        val iobuffer = dataBuffers.iobuffer
        val raf = ra
        raf.readFully(iobuffer, 0, 200)
        val fileIndexCrc = crc(iobuffer, 0, 200)
        var dis = ByteDataReader(iobuffer)
        for (i in 0..24) {
            val lv = dis.readLong()
            val readVersion = (lv shr 48).toShort()
            if (i == 0 && lookupVersion != -1 && readVersion.toInt() != lookupVersion) {
                throw IOException(
                    "lookup version mismatch (old rd5?) lookups.dat=$lookupVersion $fileName=$readVersion"
                )
            }
            if (i == 1 && lookupMinorVersion != -1 && readVersion.toInt() != lookupMinorVersion) {
                throw IOException(
                    "lookup minor version mismatch lookups.dat=$lookupMinorVersion $fileName=$readVersion"
                )
            }
            fileIndex[i] = lv and 0xffffffffffffL
        }

        val len = raf.length()
        val pos = fileIndex[24]
        var extraLen = 8 + 26 * 4

        if (len != pos) {
            if ((len - pos) > extraLen) extraLen++

            if (len < pos + extraLen) {
                throw IOException("file of size $len too short, should be ${pos + extraLen}")
            }

            raf.seek(pos)
            raf.readFully(iobuffer, 0, extraLen)
            dis = ByteDataReader(iobuffer)
            creationTime = dis.readLong()

            val crcData = dis.readInt()
            divisor = when {
                crcData == fileIndexCrc -> 80 // old format
                (crcData xor 2) == fileIndexCrc -> 32 // new format
                else -> throw IOException("top index checksum error")
            }
            fileHeaderCrcs = IntArray(25)
            for (i in 0..24) {
                fileHeaderCrcs[i] = dis.readInt()
            }
            runCatching { elevationType = dis.readByte() }
        }
    }

    public fun close() {
        runCatching { ra.close() }
    }

    public companion object {
        public fun checkVersionIntegrity(
            fileName: String,
            mapSource: MapSource
        ): Int {
            return try {
                mapSource.open(fileName).use { raf ->
                    val iobuffer = ByteArray(200)
                    raf.readFully(iobuffer, 0, 200)
                    val dis = ByteDataReader(iobuffer)
                    val lv = dis.readLong()
                    (lv shr 48).toInt()
                }
            } catch (_: IOException) {
                -1
            }
        }

        /**
         * Checks the integrity of the file using the build-in checksums
         *
         * @return the error message if file corrupt, else null
         * @throws IOException if an I/O error occurs or the file is corrupt
         */
        public fun checkFileIntegrity(
            fileName: String,
            mapSource: MapSource
        ): String? {
            val dataBuffers = DataBuffers()
            val pf = PhysicalFile(fileName, mapSource, dataBuffers, -1, -1)
            try {
                val div = pf.divisor
                for (lonDegree in 0..4) {
                    for (latDegree in 0..4) {
                        val osmf = OsmFile(pf, lonDegree, latDegree, dataBuffers)
                        if (osmf.hasData()) {
                            for (lonIdx in 0..<div) {
                                for (latIdx in 0..<div) {
                                    osmf.createMicroCacheForCell(
                                        lonDegree * div + lonIdx,
                                        latDegree * div + latIdx,
                                        dataBuffers, null, null, null
                                    )
                                }
                            }
                        }
                    }
                }
            } finally {
                pf.close()
            }
            return null
        }

    }
}

private inline fun <T : RandomAccessReader, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        close()
    }
}
