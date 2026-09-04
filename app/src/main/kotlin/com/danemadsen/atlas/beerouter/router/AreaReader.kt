package com.danemadsen.atlas.beerouter.router

import com.danemadsen.atlas.beerouter.codec.DataBuffers
import com.danemadsen.atlas.beerouter.expressions.BExpressionContextWay
import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.geo.UNSET_ELEVATION
import com.danemadsen.atlas.beerouter.geo.encodedAltitudeToMeters
import com.danemadsen.atlas.beerouter.geo.latitudeFromId
import com.danemadsen.atlas.beerouter.geo.longitudeFromId
import com.danemadsen.atlas.beerouter.map.MatchedWaypoint
import com.danemadsen.atlas.beerouter.map.MatchedWaypoint.Companion.readFromStream
import com.danemadsen.atlas.beerouter.map.NodesCache
import com.danemadsen.atlas.beerouter.map.OsmFile
import com.danemadsen.atlas.beerouter.map.OsmNode
import com.danemadsen.atlas.beerouter.map.OsmNodesMap
import com.danemadsen.atlas.beerouter.map.PhysicalFile
import kotlinx.io.IOException
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlin.math.abs
import kotlin.math.roundToInt

public class AreaReader {
    /**
     * @throws IOException if an I/O error occurs while reading map data
     */
    public fun getDirectAllData(
        rc: RoutingContext,
        wp: OsmNodeNamed,
        maxscale: Int,
        expctxWay: BExpressionContextWay,
        searchRect: OsmNogoPolygon,
        ais: MutableList<AreaInfo>
    ) {
        val div = 32
        val cellsize = 1000000 / div
        val scale = maxscale
        var count = 0
        var used = 0
        val checkBorder = maxscale > 7

        val tiles = mutableMapOf<Long, String>()
        for (idxLat in -scale..scale) {
            for (idxLon in -scale..scale) {
                if (ignoreCenter(maxscale, idxLon, idxLat)) continue
                val tmplon = wp.position.longitude + cellsize * idxLon
                val tmplat = wp.position.latitude + cellsize * idxLat
                val lonDegree = tmplon / 1000000
                val latDegree = tmplat / 1000000
                val lonMod5 = lonDegree % 5
                val latMod5 = latDegree % 5

                var lon = lonDegree - 180 - lonMod5
                val slon = if (lon < 0) "W" + (-lon) else "E$lon"
                var lat = latDegree - 90 - latMod5
                val slat = if (lat < 0) "S" + (-lat) else "N$lat"
                val filenameBase = slon + "_" + slat

                val lonIdx = tmplon / cellsize
                val latIdx = tmplat / cellsize
                val subIdx = (latIdx - div * latDegree) * div + (lonIdx - div * lonDegree)

                val subLonIdx = (lonIdx - div * lonDegree)
                val subLatIdx = (latIdx - div * latDegree)

                val dataRect = OsmNogoPolygon(true)
                lon = lonDegree * 1000000
                lat = latDegree * 1000000
                var tmplon2 = lon + cellsize * (subLonIdx)
                var tmplat2 = lat + cellsize * (subLatIdx)
                dataRect.addVertex(Position(tmplon2, tmplat2))

                tmplon2 = lon + cellsize * (subLonIdx + 1)
                tmplat2 = lat + cellsize * (subLatIdx)
                dataRect.addVertex(Position(tmplon2, tmplat2))

                tmplon2 = lon + cellsize * (subLonIdx + 1)
                tmplat2 = lat + cellsize * (subLatIdx + 1)
                dataRect.addVertex(Position(tmplon2, tmplat2))

                tmplon2 = lon + cellsize * (subLonIdx)
                tmplat2 = lat + cellsize * (subLatIdx + 1)
                dataRect.addVertex(Position(tmplon2, tmplat2))

                var intersects =
                    checkBorder && dataRect.intersects(searchRect.points[0], searchRect.points[2])
                if (!intersects && checkBorder) intersects = dataRect.intersects(
                    searchRect.points[1],
                    Position(searchRect.points[2].longitude, searchRect.points[3].latitude)
                )
                if (intersects) {
                    continue
                }

                intersects = searchRect.intersects(dataRect.points[0], dataRect.points[2])
                if (!intersects) intersects =
                    searchRect.intersects(dataRect.points[1], dataRect.points[3])
                if (!intersects) intersects = containsRect(
                    searchRect,
                    dataRect.points[0],
                    dataRect.points[2]
                )

                if (!intersects) {
                    continue
                }

                tiles[Position(tmplon, tmplat).id] = filenameBase
                count++
            }
        }

        val list = tiles.entries.sortedBy { it.value }

        val nodesCache =
            NodesCache(
                rc.mapSource,
                expctxWay,
                rc.global.forceSecondaryData,
                rc.memoryPolicy,
                null,
                false
            )
        var currentPhysicalFile: PhysicalFile? = null
        var currentDataBuffers: DataBuffers? = null
        var lastFilenameBase = ""
        try {
            for (entry in list) {
                val n = OsmNode(entry.key)
                val filenameBase: String = entry.value
                if (filenameBase != lastFilenameBase) {
                    currentPhysicalFile?.close()
                    lastFilenameBase = filenameBase
                    val fileName = "$filenameBase.rd5"
                    currentDataBuffers = DataBuffers()
                    currentPhysicalFile = PhysicalFile(
                        fileName,
                        rc.mapSource,
                        currentDataBuffers,
                        -1,
                        -1
                    )
                }
                val physicalFile = requireNotNull(currentPhysicalFile)
                val dataBuffers = requireNotNull(currentDataBuffers)
                if (getDirectData(
                        physicalFile,
                        dataBuffers,
                        n.position.longitude,
                        n.position.latitude,
                        rc,
                        expctxWay,
                        ais
                    )
                ) {
                    used++
                }
            }
        } catch (e: IOException) {
            // I/O failure while scanning area info is non-fatal; clear results and continue.
            println("AreaReader I/O error after used=$used / count=$count: ${e.message}")
            ais.clear()
        } finally {
            runCatching { currentPhysicalFile?.close() }
            nodesCache.close()
        }
    }

    /**
     * @throws IOException if an I/O error occurs while reading map data
     */
    public fun getDirectData(
        pf: PhysicalFile,
        dataBuffers: DataBuffers,
        inlon: Int,
        inlat: Int,
        rc: RoutingContext?,
        expctxWay: BExpressionContextWay,
        ais: MutableList<AreaInfo>
    ): Boolean {
        val lonDegree = inlon / 1000000
        val latDegree = inlat / 1000000

        val nodesMap = OsmNodesMap()

        try {
            val div = pf.divisor

            val osmf = OsmFile(pf, lonDegree, latDegree, dataBuffers)
            if (osmf.hasData()) {
                val cellsize = 1000000 / div
                val tmplon = inlon
                val tmplat = inlat
                val lonIdx = tmplon / cellsize
                val latIdx = tmplat / cellsize

                val segment =
                    osmf.createMicroCacheForCell(lonIdx, latIdx, dataBuffers, expctxWay, null, null)

                val size = segment.size
                for (i in 0..<size) {
                    val id = segment.getIdForIndex(i)
                    val lon = id.longitudeFromId()
                    val lat = id.latitudeFromId()

                    var possibleMatch = false
                    for (ai in ais) {
                        val polygon = requireNotNull(ai.polygon)
                        if (polygon.radius > 0) {
                            val dx = (lon - polygon.position.longitude).toDouble()
                            val dy = (lat - polygon.position.latitude).toDouble()
                            // rough circle check using 100m per degree approx is too crude,
                            // but since they are integers we can just use a large enough box or the real distance
                            if (abs(dx) < 2000 && abs(dy) < 2000) { // 2000 E6 units is ~200m
                                possibleMatch = true
                                break
                            }
                        } else {
                            possibleMatch = true // no bounding circle, must check
                            break
                        }
                    }
                    if (!possibleMatch) continue

                    if (segment.getAndClear(id)) {
                        val node = OsmNode(id)
                        node.parseNodeBody(segment, nodesMap, expctxWay)
                        var link = node.firstlink
                        while (link != null) {
                            val nextNode = link.getTarget(node)
                            if (nextNode!!.firstlink != null && nextNode.firstlink!!.descriptionBitmap != null) {
                                for (ai in ais) {
                                    if (ai.polygon!!.isWithin(lon.toLong(), lat.toLong())) {
                                        ai.checkAreaInfo(
                                            expctxWay,
                                            node.altitude
                                                .takeUnless { it == UNSET_ELEVATION }
                                                ?.let(::encodedAltitudeToMeters)
                                                ?: 0.0,
                                            nextNode.firstlink!!.descriptionBitmap!!
                                        )
                                        break
                                    }
                                }
                                break
                            }
                            link = link.getNext(node)
                        }
                    }
                }
                return true
            }
        } catch (e: IOException) {
            println("AreaReader: ${e.message}")
        }
        return false
    }

    public fun ignoreCenter(maxscale: Int, idxLon: Int, idxLat: Int): Boolean {
        val centerScale = (maxscale * .2).roundToInt() - 1
        return centerScale >= 0 &&
                idxLon >= -centerScale && idxLon <= centerScale &&
                idxLat >= -centerScale && idxLat <= centerScale
    }

    /*
    in this case the polygon is 'only' a rectangle
  */
    public fun containsRect(
        searchRect: OsmNogoPolygon,
        start: Position,
        end: Position
    ): Boolean {
        return searchRect.isWithin(start.longitude.toLong(), start.latitude.toLong()) &&
                searchRect.isWithin(end.longitude.toLong(), end.latitude.toLong())
    }

    /**
     * @throws IOException if an I/O error occurs while writing to the sink
     */
    public fun writeAreaInfo(sink: Sink, wp: MatchedWaypoint, ais: MutableList<AreaInfo>) {
        wp.writeToStream(sink)
        for (ai in ais) {
            sink.writeInt(ai.direction)
            sink.writeLong(ai.elevStart.toBits())
            sink.writeInt(ai.ways)
            sink.writeInt(ai.greenWays)
            sink.writeInt(ai.riverWays)
            sink.writeInt(ai.elev50)
        }
    }

    public fun readAreaInfo(source: Source, wp: MatchedWaypoint, ais: MutableList<AreaInfo>) {
        try {
            val ep = readFromStream(source)
            if (abs(ep.waypoint!!.position.longitude - wp.waypoint!!.position.longitude) > 500 &&
                abs(ep.waypoint!!.position.latitude - wp.waypoint!!.position.latitude) > 500
            ) {
                return
            }
            if (abs(ep.radius - wp.radius) > 500) {
                return
            }
            for (i in 0..3) {
                val direction = source.readInt()
                val ai = AreaInfo(direction)
                ai.elevStart = Double.fromBits(source.readLong())
                ai.ways = source.readInt()
                ai.greenWays = source.readInt()
                ai.riverWays = source.readInt()
                ai.elev50 = source.readInt()
                ais.add(ai)
            }
        } catch (_: IOException) {
            ais.clear()
        }
    }
}
