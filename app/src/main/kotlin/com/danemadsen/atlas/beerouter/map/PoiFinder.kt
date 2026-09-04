package com.danemadsen.atlas.beerouter.map

import com.danemadsen.atlas.beerouter.codec.DataBuffers
import com.danemadsen.atlas.beerouter.codec.TagValueValidator
import com.danemadsen.atlas.beerouter.expressions.BExpressionContextNode
import com.danemadsen.atlas.beerouter.expressions.BExpressionMetaData
import com.danemadsen.atlas.beerouter.geo.latitudeFromId
import com.danemadsen.atlas.beerouter.geo.longitudeFromId
import kotlin.math.max
import kotlin.math.min

public class PoiFinder(
    private val mapSource: MapSource,
    lookupContent: String,
) {
    private val dataBuffers: DataBuffers = DataBuffers()
    private val nodeContext: BExpressionContextNode
    private val lookupVersion: Int
    private val lookupMinorVersion: Int
    private val fileCache: MutableMap<String, PhysicalFile?> = linkedMapOf()

    init {
        val meta = BExpressionMetaData()
        nodeContext = BExpressionContextNode(meta)
        meta.readMetaData(lookupContent)
        lookupVersion = meta.lookupVersion.toInt()
        lookupMinorVersion = meta.lookupMinorVersion.toInt()
    }

    /**
     * @throws IOException if an I/O error occurs while reading map data
     * @throws IllegalStateException if an unsupported cache version is encountered
     */
    public fun find(query: PoiQuery): List<PoiResult> {
        val results = mutableListOf<PoiResult>()
        val seenNodeIds = mutableSetOf<Long>()
        val minLonDegree = floorDiv(query.minLon, ONE_DEGREE)
        val maxLonDegree = floorDiv(query.maxLon, ONE_DEGREE)
        val minLatDegree = floorDiv(query.minLat, ONE_DEGREE)
        val maxLatDegree = floorDiv(query.maxLat, ONE_DEGREE)

        for (latDegree in minLatDegree..maxLatDegree) {
            for (lonDegree in minLonDegree..maxLonDegree) {
                val physicalFile = physicalFileForDegrees(lonDegree, latDegree) ?: continue
                val osmFile = OsmFile(physicalFile, lonDegree, latDegree, dataBuffers)
                if (!osmFile.hasData()) {
                    continue
                }

                val divisor = physicalFile.divisor
                val cellSize = ONE_DEGREE / divisor
                val degreeLonMin = lonDegree * ONE_DEGREE
                val degreeLatMin = latDegree * ONE_DEGREE
                val degreeLonMax = degreeLonMin + ONE_DEGREE - 1
                val degreeLatMax = degreeLatMin + ONE_DEGREE - 1

                val scanLonMin = max(query.minLon, degreeLonMin)
                val scanLonMax = min(query.maxLon, degreeLonMax)
                val scanLatMin = max(query.minLat, degreeLatMin)
                val scanLatMax = min(query.maxLat, degreeLatMax)
                if (scanLonMin > scanLonMax || scanLatMin > scanLatMax) {
                    continue
                }

                val minLonIdx = floorDiv(scanLonMin, cellSize)
                val maxLonIdx = floorDiv(scanLonMax, cellSize)
                val minLatIdx = floorDiv(scanLatMin, cellSize)
                val maxLatIdx = floorDiv(scanLatMax, cellSize)

                for (latIdx in minLatIdx..maxLatIdx) {
                    for (lonIdx in minLonIdx..maxLonIdx) {
                        val segment = osmFile.createMicroCacheForCell(
                            lonIdx,
                            latIdx,
                            dataBuffers,
                            RejectAllWayValidator,
                            null,
                            null
                        )

                        val size = segment.size
                        for (index in 0 until size) {
                            val nodeId = segment.getIdForIndex(index)
                            val lon = nodeId.longitudeFromId()
                            val lat = nodeId.latitudeFromId()

                            if (lon !in query.minLon..query.maxLon ||
                                lat !in query.minLat..query.maxLat
                            ) {
                                continue
                            }

                            if (!segment.getAndClear(nodeId)) {
                                continue
                            }

                            val node = OsmNode(nodeId)
                            val description = node.parseNodeTags(segment, nodeContext) ?: continue

                            val tags = nodeContext.getMap(false, description)
                            if (!matchesRequiredTags(tags, query.requiredTags)) {
                                continue
                            }

                            if (!seenNodeIds.add(nodeId)) {
                                continue
                            }

                            results += PoiResult(
                                nodeId = nodeId,
                                position = node.position,
                                tags = tags,
                                sourceFile = physicalFile.fileName,
                            )
                            if (query.limit != null && results.size >= query.limit) {
                                return results
                            }
                        }
                    }
                }
            }
        }
        return results
    }

    public fun close() {
        for (physicalFile in fileCache.values) {
            runCatching { physicalFile?.close() }
        }
        fileCache.clear()
    }

    private fun physicalFileForDegrees(lonDegree: Int, latDegree: Int): PhysicalFile? {
        val fileName = rd5FileName(lonDegree, latDegree)
        return fileCache.getOrPut(fileName.removeSuffix(".rd5")) {
            if (!mapSource.exists(fileName)) {
                return@getOrPut null
            }
            PhysicalFile(
                fileName = fileName,
                mapSource = mapSource,
                dataBuffers = dataBuffers,
                lookupVersion = lookupVersion,
                lookupMinorVersion = lookupMinorVersion,
            )
        }
    }

    private fun rd5FileName(lonDegree: Int, latDegree: Int): String {
        val lonMod5 = lonDegree % 5
        val latMod5 = latDegree % 5
        val lon = lonDegree - 180 - lonMod5
        val lat = latDegree - 90 - latMod5
        val slon = if (lon < 0) "W${-lon}" else "E$lon"
        val slat = if (lat < 0) "S${-lat}" else "N$lat"
        return "${slon}_${slat}.rd5"
    }

    private fun matchesRequiredTags(
        tags: Map<String, String>,
        requiredTags: Map<String, Set<String>>
    ): Boolean {
        for ((key, expectedValues) in requiredTags) {
            val actualValue = tags[key] ?: return false
            if (expectedValues.isNotEmpty() && actualValue !in expectedValues) {
                return false
            }
        }
        return true
    }

    private object RejectAllWayValidator : TagValueValidator {
        override fun accessType(tagValueSet: ByteArray?): Int = 0
        override fun unify(ab: ByteArray, offset: Int, len: Int): ByteArray? = null
        override fun isLookupIdxUsed(idx: Int): Boolean = false
        override fun setDecodeForbidden(decodeForbidden: Boolean) {}
        override fun checkStartWay(ab: ByteArray?): Boolean = false
    }

    private companion object {
        private const val ONE_DEGREE: Int = 1_000_000

        private fun floorDiv(dividend: Int, divisor: Int): Int {
            val quotient = dividend / divisor
            val remainder = dividend % divisor
            return if (remainder != 0 && (dividend xor divisor) < 0) quotient - 1 else quotient
        }
    }
}
