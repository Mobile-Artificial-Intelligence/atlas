/**
 * Efficient cache or osmnodes
 *
 * @author ab
 */
package com.danemadsen.atlas.beerouter.map

import com.danemadsen.atlas.beerouter.codec.DataBuffers
import com.danemadsen.atlas.beerouter.codec.MicroCache
import com.danemadsen.atlas.beerouter.codec.WaypointMatcher
import com.danemadsen.atlas.beerouter.expressions.BExpressionContextWay
import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.geo.latitudeFromId
import com.danemadsen.atlas.beerouter.geo.longitudeFromId
import com.danemadsen.atlas.beerouter.router.exceptions.DataFileNotFoundException

public class NodesCache(
    private val mapSource: MapSource,
    private val ctxWay: BExpressionContextWay,
    forceSecondaryData: Boolean,
    memoryPolicy: RoutingMemoryPolicy,
    oldCache: NodesCache?,
    detailed: Boolean
) {
    private val MAX_DYNAMIC_CATCHES = 20 // used with RoutingEngine MAX_DYNAMIC_RANGE = 60000m

    public var nodesMap: OsmNodesMap = OsmNodesMap()
    private val lookupVersion: Int
    private val lookupMinorVersion: Int
    private val forceSecondaryData: Boolean
    private var currentFileName: String? = null
    private var lastLonDegree: Int = Int.MIN_VALUE
    private var lastLatDegree: Int = Int.MIN_VALUE
    private var lastOsmFile: OsmFile? = null

    private var fileCache: MutableMap<String, PhysicalFile?> = HashMap(4)
    private var dataBuffers: DataBuffers = DataBuffers()

    private var fileRows: Array<Array<OsmFile?>?>
    private var fileGrid: Array<Array<OsmFile?>> = Array(180) { arrayOfNulls(360) }

    public var waypointMatcher: WaypointMatcher? = null

    public var firstFileAccessFailed: Boolean = false
    public var firstFileAccessName: String?

    private var cacheSum: Long = 0
    private val maxTileCacheBudgetBytes = memoryPolicy.tileCacheBudgetBytes
    private var tileCacheLimitBytes: Long = memoryPolicy.tileCacheBudgetBytes
    private var detailed: Boolean // NOPMD used in constructor

    private var garbageCollectionEnabled = false
    private var ghostCleaningDone = false


    private var cacheSumClean: Long = 0
    private var ghostSum: Long = 0
    private var ghostWakeup: Long = 0

    private val directWeaving = true //!Boolean.getBoolean("disableDirectWeaving")

    public fun formatStatus(): String {
        return "collecting=$garbageCollectionEnabled noGhosts=$ghostCleaningDone cacheSum=$cacheSum cacheSumClean=$cacheSumClean ghostSum=$ghostSum ghostWakeup=$ghostWakeup"
    }

    init {
        this.nodesMap.configureMemoryPolicy(memoryPolicy)
        this.lookupVersion = ctxWay.meta.lookupVersion.toInt()
        this.lookupMinorVersion = ctxWay.meta.lookupMinorVersion.toInt()
        this.forceSecondaryData = forceSecondaryData
        this.detailed = detailed

        ctxWay.setDecodeForbidden(detailed)

        firstFileAccessFailed = false
        firstFileAccessName = null

        if (oldCache != null) {
            fileCache = oldCache.fileCache
            dataBuffers = oldCache.dataBuffers

            // re-use old, virgin caches (if same detail-mode)
            if (oldCache.detailed == detailed) {
                fileRows = oldCache.fileRows
                fileGrid = oldCache.fileGrid
                for (fileRow in fileRows) {
                    if (fileRow == null) continue
                    for (osmf in fileRow) {
                        cacheSum += osmf!!.setGhostState()
                    }
                }
            } else {
                fileRows = arrayOfNulls(180)
            }
        } else {
            fileRows = arrayOfNulls(180)
            dataBuffers = DataBuffers()
        }
        ghostSum = cacheSum
    }

    public fun clean(all: Boolean) {
        for (fileRow in fileRows) {
            if (fileRow == null) continue
            for (osmf in fileRow) {
                osmf!!.clean(all)
            }
        }
    }

    // if the cache sum exceeded a threshold,
    // clean all ghosts and enable garbage collection
    private fun checkEnableCacheCleaning() {
        if (cacheSum < tileCacheLimitBytes) {
            return
        }

        for (fileRow in fileRows) {
            if (fileRow == null) continue
            for (osmf in fileRow) {
                cacheSum -= if (garbageCollectionEnabled && !ghostCleaningDone) {
                    osmf!!.cleanGhosts()
                } else {
                    osmf!!.collectAll()
                }
            }
        }

        if (garbageCollectionEnabled) {
            ghostCleaningDone = true
            tileCacheLimitBytes = minOf(tileCacheLimitBytes * 2, maxTileCacheBudgetBytes)
        } else {
            cacheSumClean = cacheSum
            garbageCollectionEnabled = true
        }
    }

    /**
     * @throws DataFileNotFoundException if an error occurs while loading the segment
     */
    public fun loadSegmentFor(position: Position): Int = loadSegmentFor(position.longitude, position.latitude)

    /**
     * @throws DataFileNotFoundException if an error occurs while loading the segment
     */
    public fun loadSegmentFor(lon: Int, lat: Int): Int {
        val mc = getSegmentFor(lon, lat)
        return mc?.size ?: 0
    }

    /**
     * @throws DataFileNotFoundException if an error occurs while loading the segment
     */
    public fun getSegmentFor(position: Position): MicroCache? = getSegmentFor(position.longitude, position.latitude)

    /**
     * @throws DataFileNotFoundException if an error occurs while loading the segment
     */
    public fun getSegmentFor(lon: Int, lat: Int): MicroCache? {
        try {
            val lonDegree = lon / 1000000
            val latDegree = lat / 1000000
            var osmf: OsmFile? =
                if (lonDegree == lastLonDegree && latDegree == lastLatDegree) {
                    lastOsmFile
                } else {
                    null
                }

            if (osmf == null) {
                osmf = fileGrid[latDegree][lonDegree]

                if (osmf == null) {
                    val fileRow = fileRows[latDegree]
                    val ndegrees = fileRow?.size ?: 0
                    osmf = fileForSegment(lonDegree, latDegree)
                    val newFileRow = arrayOfNulls<OsmFile>(ndegrees + 1)
                    for (i in 0..<ndegrees) {
                        newFileRow[i] = fileRow!![i]
                    }
                    newFileRow[ndegrees] = osmf
                    fileRows[latDegree] = newFileRow
                    fileGrid[latDegree][lonDegree] = osmf
                }

                lastLonDegree = lonDegree
                lastLatDegree = latDegree
                lastOsmFile = osmf
            }
            currentFileName = osmf.filename

            if (!osmf.hasData()) {
                return null
            }

            var segment = osmf.getMicroCache(lon, lat)
            // needed for a second chance
            if (segment == null || (waypointMatcher != null && (waypointMatcher as WaypointMatcherImpl).useDynamicRange)) {
                checkEnableCacheCleaning()
                segment = osmf.createMicroCache(
                    lon,
                    lat,
                    dataBuffers,
                    ctxWay,
                    waypointMatcher,
                    if (directWeaving) nodesMap else null
                )

                cacheSum += segment.dataSize.toLong()
            } else if (segment.ghost) {
                segment.unGhost()
                ghostWakeup += segment.dataSize.toLong()
            }
            return segment
        } catch (re: RuntimeException) {
            throw re
        } catch (e: Exception) {
            throw DataFileNotFoundException("error reading datafile $currentFileName", e)
        }
    }

    /**
     * make sure the given node is non-hollow,
     * which means it contains not just the id,
     * but also the actual data
     *
     * @return true if successfull, false if node is still hollow
     * @throws DataFileNotFoundException if an error occurs while resolving the node
     */
    public fun obtainNonHollowNode(node: OsmNode): Boolean {
        if (!node.isHollow) return true

        val nodeId = node.idFromPos
        val segment = getSegmentFor(nodeId.longitudeFromId(), nodeId.latitudeFromId())
        if (segment == null) {
            return false
        }
        if (!node.isHollow) {
            return true // direct weaving...
        }

        val id = node.idFromPos
        if (segment.getAndClear(id)) {
            node.parseNodeBody(segment, nodesMap, ctxWay)
        }

        if (garbageCollectionEnabled) { // garbage collection
            cacheSum -= segment.collect(segment.size shr 1)
                .toLong() // threshold = 1/2 of size is deleted
        }

        return !node.isHollow
    }


    /**
     * make sure all link targets of the given node are non-hollow
     *
     * @throws DataFileNotFoundException if an error occurs while resolving a link target
     */
    public fun expandHollowLinkTargets(n: OsmNode) {
        var link = n.firstlink
        while (link != null) {
            obtainNonHollowNode(link.getTarget(n)!!)
            link = link.getNext(n)
        }
    }

    /**
     * make sure all link targets of the given node are non-hollow
     */
    public fun hasHollowLinkTargets(n: OsmNode): Boolean {
        var link = n.firstlink
        while (link != null) {
            if (link.getTarget(n)!!.isHollow) {
                return true
            }
            link = link.getNext(n)
        }
        return false
    }

    /**
     * get a node for the given id with all link-targets also non-hollow
     *
     *
     * It is required that an instance of the start-node does not yet
     * exist, not even a hollow instance, so getStartNode should only
     * be called once right after resetting the cache
     *
     * @param id the id of the node to load
     * @return the fully expanded node for id, or null if it was not found
     * @throws DataFileNotFoundException if an error occurs while loading the node
     */
    public fun getStartNode(id: Long): OsmNode? {
        // initialize the start-node
        val n = OsmNode(id)
        n.setHollow()
        nodesMap.put(n)
        if (!obtainNonHollowNode(n)) {
            return null
        }
        expandHollowLinkTargets(n)
        return n
    }

    public fun getGraphNode(template: OsmNode): OsmNode {
        val graphNode = OsmNode(template.position)
        graphNode.setHollow()
        val existing = nodesMap.put(graphNode)
        if (existing == null) {
            return graphNode
        }
        nodesMap.put(existing)
        return existing
    }

    /**
     * @throws IllegalArgumentException if waypoints are empty or a required data file is not found
     * @throws DataFileNotFoundException if an error occurs while loading map data
     */
    public fun matchWaypointsToNodes(
        unmatchedWaypoints: MutableList<MatchedWaypoint>,
        maxDistance: Double,
        islandNodePairs: OsmNodePairSet?
    ): Boolean {
        waypointMatcher = WaypointMatcherImpl(unmatchedWaypoints, maxDistance, islandNodePairs!!)
        for (mwp in unmatchedWaypoints) {
            var cellsize = 12500
            preloadPosition(mwp.waypoint!!, cellsize, 1, false)
            // get a second chance
            if (mwp.crosspoint == null || mwp.radius > RETRY_RANGE) {
                cellsize = 1000000 / 32
                preloadPosition(
                    mwp.waypoint!!,
                    cellsize,
                    if (maxDistance < 0) MAX_DYNAMIC_CATCHES else 2,
                    maxDistance < 0
                )
            }
        }

        require(!firstFileAccessFailed) { "datafile $firstFileAccessName not found" }
        for ((i, mwp) in unmatchedWaypoints.withIndex()) {
            if (mwp.crosspoint == null) {
                if (unmatchedWaypoints.size > 1 && i == unmatchedWaypoints.size - 1 && unmatchedWaypoints[i - 1].type == MatchedWaypoint.Type.DIRECT
                ) {
                    mwp.crosspoint = OsmNode(mwp.waypoint!!.position)
                    mwp.type = MatchedWaypoint.Type.DIRECT
                } else {
                    // do not break here throw new IllegalArgumentException(mwp.name + "-position not mapped in existing datafile");
                    return false
                }
            }
            if (unmatchedWaypoints.size > 1 && i == unmatchedWaypoints.size - 1 && unmatchedWaypoints[i - 1].type == MatchedWaypoint.Type.DIRECT
            ) {
                mwp.crosspoint = OsmNode(mwp.waypoint!!.position)
                mwp.type = MatchedWaypoint.Type.DIRECT
            }
        }
        return true
    }

    private fun preloadPosition(
        n: OsmNode,
        d: Int,
        maxscale: Int,
        bUseDynamicRange: Boolean
    ) {
        firstFileAccessFailed = false
        firstFileAccessName = null
        loadSegmentFor(n.position)
        require(!firstFileAccessFailed) { "datafile $firstFileAccessName not found" }
        var scale = 1
        while (scale < maxscale) {
            for (idxLat in -scale..scale) for (idxLon in -scale..scale) {
                if (idxLon != 0 || idxLat != 0) {
                    loadSegmentFor(n.position.longitude + d * idxLon, n.position.latitude + d * idxLat)
                }
            }
            if (bUseDynamicRange && waypointMatcher!!.hasMatch(n.position.longitude, n.position.latitude)) break
            scale++
        }
    }

    private fun fileForSegment(lonDegree: Int, latDegree: Int): OsmFile {
        val lonMod5 = lonDegree % 5
        val latMod5 = latDegree % 5

        val lon = lonDegree - 180 - lonMod5
        val slon = if (lon < 0) "W" + (-lon) else "E$lon"
        val lat = latDegree - 90 - latMod5

        val slat = if (lat < 0) "S" + (-lat) else "N$lat"
        val filenameBase = slon + "_" + slat

        currentFileName = "$filenameBase.rd5"

        val ra: PhysicalFile? = fileCache.getOrPut(filenameBase) {
            if (!forceSecondaryData) {
                val fileName = "$filenameBase.rd5"
                if (mapSource.exists(fileName)) {
                    currentFileName = "$filenameBase.rd5"
                    return@getOrPut PhysicalFile(
                        fileName,
                        mapSource,
                        dataBuffers,
                        lookupVersion,
                        lookupMinorVersion
                    )
                }
            }
            null
        }
        val osmf = OsmFile(ra, lonDegree, latDegree, dataBuffers)

        if (firstFileAccessName == null) {
            firstFileAccessName = currentFileName
            firstFileAccessFailed = osmf.filename == null
        }

        return osmf
    }

    public fun close() {
        for (f in fileCache.values) {
            runCatching { f?.close() }
        }
    }

    public fun getElevationType(position: Position): Int {
        val lonDegree = position.longitude / 1000000
        val latDegree = position.latitude / 1000000
        return fileGrid[latDegree][lonDegree]?.elevationType?.toInt() ?: 3
    }

    public companion object {
        public const val RETRY_RANGE: Int = 250
    }
}
