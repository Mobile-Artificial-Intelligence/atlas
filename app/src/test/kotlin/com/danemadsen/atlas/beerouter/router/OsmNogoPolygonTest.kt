/**********************************************************************************************
 * Copyright (C) 2018 Norbert Truchsess norbert.truchsess@t-online.de
 */
package com.danemadsen.atlas.beerouter.router

import com.danemadsen.atlas.beerouter.geo.CheapRuler.distance
import com.danemadsen.atlas.beerouter.geo.Position
import com.danemadsen.atlas.beerouter.geo.coordinateScaleAt
import com.danemadsen.atlas.beerouter.router.OsmNogoPolygon.Companion.isOnLine
import kotlin.math.sqrt
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OsmNogoPolygonTest {
    @Test
    fun testCalcBoundingCircle() {
        polygon!!.updateBoundingCircle()

        val scale = coordinateScaleAt(polygon!!.position.latitude)
        val dlon2m = scale.longitudeToMeters
        val dlat2m = scale.latitudeToMeters

        var r: Double = polygon!!.radius
        for (i in lons.indices) {
            val testLon = toOsmLon(lons[i], OFFSET_X)
            val testLat = toOsmLat(lats[i], OFFSET_Y)
            val dpx: Double = (testLon - polygon!!.position.longitude) * dlon2m
            val dpy: Double = (testLat - polygon!!.position.latitude) * dlat2m
            val r1 = sqrt(dpx * dpx + dpy * dpy)
            val diff = r - r1
            assertTrue(diff >= 0, "i: $i r($r) >= r1($r1)")
        }
        polyline!!.updateBoundingCircle()
        r = polyline!!.radius
        for (i in lons.indices) {
            val testLon = toOsmLon(lons[i], OFFSET_X)
            val testLat = toOsmLat(lats[i], OFFSET_Y)
            val dpx: Double = (testLon - polyline!!.position.longitude) * dlon2m
            val dpy: Double = (testLat - polyline!!.position.latitude) * dlat2m
            val r1 = sqrt(dpx * dpx + dpy * dpy)
            val diff = r - r1
            assertTrue(diff >= 0, "i: $i r($r) >= r1($r1)")
        }
    }

    @Test
    fun testIsWithin() {
        val plons = doubleArrayOf(0.0, 0.5, 1.0, -1.5, -0.5, 1.0, 1.0, 0.5, 0.5, 0.5)
        val plats = doubleArrayOf(0.0, 1.5, 0.0, 0.5, -1.5, -1.0, -0.1, -0.1, 0.0, 0.1)
        val within = booleanArrayOf(true, false, false, false, false, true, true, true, true, true)

        for (i in plons.indices) {
            assertEquals(
                within[i], polygon!!.isWithin(
                    toOsmLon(plons[i], OFFSET_X).toLong(), toOsmLat(plats[i], OFFSET_Y).toLong()
                ),
                message = "(" + plons[i] + "," + plats[i] + ")"
            )
        }
    }

    @Test
    fun testIntersectsPolygon() {
        val p0lons = doubleArrayOf(0.0, 1.0, -0.5, 0.5, 0.7, 0.7, 0.7, -1.5, -1.5, 0.0)
        val p0lats = doubleArrayOf(0.0, 0.0, 0.5, 0.5, 0.5, 0.05, 0.05, -1.5, 0.2, 0.0)
        val p1lons = doubleArrayOf(0.0, 1.0, 0.5, 1.0, 0.7, 0.7, 0.7, -0.5, -0.2, 0.5)
        val p1lats = doubleArrayOf(0.0, 0.0, 0.5, 0.5, -0.5, -0.5, -0.05, -0.5, 1.5, -1.5)
        val within = booleanArrayOf(false, false, false, true, true, true, false, true, true, true)

        for (i in p0lons.indices) {
            assertEquals(
                within[i],
                polygon!!.intersects(
                    Position(toOsmLon(p0lons[i], OFFSET_X), toOsmLat(p0lats[i], OFFSET_Y)),
                    Position(toOsmLon(p1lons[i], OFFSET_X), toOsmLat(p1lats[i], OFFSET_Y))
                ),
                message = "(" + p0lons[i] + "," + p0lats[i] + ")-(" + p1lons[i] + "," + p1lats[i] + ")"
            )
        }
    }

    @Test
    fun testIntersectsPolyline() {
        val p0lons = doubleArrayOf(0.0, 1.0, -0.5, 0.5, 0.7, 0.7, 0.7, -1.5, -1.5, 0.0)
        val p0lats = doubleArrayOf(0.0, 0.0, 0.5, 0.5, 0.5, 0.05, 0.05, -1.5, 0.2, 0.0)
        val p1lons = doubleArrayOf(0.0, 1.0, 0.5, 1.0, 0.7, 0.7, 0.7, -0.5, -0.2, 0.5)
        val p1lats = doubleArrayOf(0.0, 0.0, 0.5, 0.5, -0.5, -0.5, -0.05, -0.5, 1.5, -1.5)
        val within = booleanArrayOf(false, false, false, true, true, true, false, true, true, false)

        for (i in p0lons.indices) {
            assertEquals(
                within[i],
                polyline!!.intersects(
                    Position(toOsmLon(p0lons[i], OFFSET_X), toOsmLat(p0lats[i], OFFSET_Y)),
                    Position(toOsmLon(p1lons[i], OFFSET_X), toOsmLat(p1lats[i], OFFSET_Y))
                ),
                message = "(" + p0lons[i] + "," + p0lats[i] + ")-(" + p1lons[i] + "," + p1lats[i] + ")"
            )
        }
    }

    @Test
    fun testBelongsToLine() {
        assertTrue(isOnLine(10, 10, 10, 10, 10, 20))
        assertTrue(isOnLine(10, 10, 10, 10, 20, 10))
        assertTrue(isOnLine(10, 10, 20, 10, 10, 10))
        assertTrue(isOnLine(10, 10, 10, 20, 10, 10))
        assertTrue(isOnLine(10, 15, 10, 10, 10, 20))
        assertTrue(isOnLine(15, 10, 10, 10, 20, 10))
        assertTrue(isOnLine(10, 10, 10, 10, 20, 30))
        assertTrue(isOnLine(20, 30, 10, 10, 20, 30))
        assertTrue(isOnLine(15, 20, 10, 10, 20, 30))
        assertFalse(isOnLine(11, 11, 10, 10, 10, 20))
        assertFalse(isOnLine(11, 11, 10, 10, 20, 10))
        assertFalse(isOnLine(15, 21, 10, 10, 20, 30))
        assertFalse(isOnLine(15, 19, 10, 10, 20, 30))
        assertFalse(isOnLine(0, -10, 10, 10, 20, 30))
        assertFalse(isOnLine(30, 50, 10, 10, 20, 30))
    }

    @Test
    fun testDistanceWithinPolygon() {
        // Testing polygon
        val lons = doubleArrayOf(2.333523, 2.333432, 2.333833, 2.333983, 2.334815, 2.334766)
        val lats = doubleArrayOf(48.823778, 48.824091, 48.82389, 48.824165, 48.824232, 48.82384)
        val polygon = OsmNogoPolygon(true)
        for (i in lons.indices) {
            polygon.addVertex(Position(toOsmLon(lons[i], 0), toOsmLat(lats[i], 0)))
        }
        val polyline = OsmNogoPolygon(false)
        for (i in lons.indices) {
            polyline.addVertex(Position(toOsmLon(lons[i], 0), toOsmLat(lats[i], 0)))
        }

        // Check with a segment with a single intersection with the polygon
        var lon1: Int = toOsmLon(2.33308732509613, 0)
        var lat1: Int = toOsmLat(48.8238790443901, 0)
        var lon2: Int = toOsmLon(2.33378201723099, 0)
        var lat2: Int = toOsmLat(48.8239585098974, 0)
        var start = Position(lon1, lat1)
        var end = Position(lon2, lat2)
        assertEquals(
            17.5,
            polygon.distanceWithinPolygon(start, end),
            0.05 * 17.5,
            "Should give the correct length for a segment with a single intersection"
        )

        // Check with a segment crossing multiple times the polygon
        lon2 = toOsmLon(2.33488172292709, 0)
        lat2 = toOsmLat(48.8240891862353, 0)
        end = Position(lon2, lat2)
        assertEquals(
            85.0,
            polygon.distanceWithinPolygon(start, end),
            0.05 * 85,
            "Should give the correct length for a segment with multiple intersections"
        )

        // Check that it works when a point is within the polygon
        lon2 = toOsmLon(2.33433187007904, 0)
        lat2 = toOsmLat(48.8240238480664, 0)
        end = Position(lon2, lat2)
        assertEquals(
            50.0,
            polygon.distanceWithinPolygon(start, end),
            0.05 * 50,
            "Should give the correct length when last point is within the polygon"
        )
        lon1 = toOsmLon(2.33433187007904, 0)
        lat1 = toOsmLat(48.8240238480664, 0)
        lon2 = toOsmLon(2.33488172292709, 0)
        lat2 = toOsmLat(48.8240891862353, 0)
        start = Position(lon1, lat1)
        end = Position(lon2, lat2)
        assertEquals(
            35.0,
            polygon.distanceWithinPolygon(start, end),
            0.05 * 35,
            "Should give the correct length when first point is within the polygon"
        )

        lon1 = toOsmLon(2.333523, 0)
        lat1 = toOsmLat(48.823778, 0)
        lon2 = toOsmLon(2.333432, 0)
        lat2 = toOsmLat(48.824091, 0)
        start = Position(lon1, lat1)
        end = Position(lon2, lat2)
        assertEquals(
            distance(start, end),
            polygon.distanceWithinPolygon(start, end),
            0.05 * distance(start, end),
            "Should give the correct length if the segment overlaps with an edge of the polygon"
        )

        lon1 = toOsmLon(2.333523, 0)
        lat1 = toOsmLat(48.823778, 0)
        lon2 = toOsmLon(2.3334775, 0)
        lat2 = toOsmLat(48.8239345, 0)
        start = Position(lon1, lat1)
        end = Position(lon2, lat2)
        assertEquals(
            distance(start, end),
            polyline.distanceWithinPolygon(start, end),
            0.05 * distance(start, end),
            "Should give the correct length if the segment overlaps with a polyline"
        )
    }

    @BeforeTest
    @Throws(Exception::class)
    fun setUp() {
        polygon = OsmNogoPolygon(true)
        for (i in lons.indices) {
            polygon!!.addVertex(Position(toOsmLon(lons[i], OFFSET_X), toOsmLat(lats[i], OFFSET_Y)))
        }
        polyline = OsmNogoPolygon(false)
        for (i in lons.indices) {
            polyline!!.addVertex(Position(toOsmLon(lons[i], OFFSET_X), toOsmLat(lats[i], OFFSET_Y)))
        }
    }

    companion object {
        const val OFFSET_X: Int = 11000000
        const val OFFSET_Y: Int = 50000000

        var polygon: OsmNogoPolygon? = null
        var polyline: OsmNogoPolygon? = null

        val lons: DoubleArray = doubleArrayOf(1.0, 1.0, 0.5, 0.5, 1.0, 1.0, -1.1, -1.0)
        val lats: DoubleArray = doubleArrayOf(-1.0, -0.1, -0.1, 0.1, 0.1, 1.0, 1.1, -1.0)

        fun toOsmLon(lon: Double, offsetX: Int): Int {
            return ((lon + 180.0) * 1000000.0 + 0.5).toInt() + offsetX // see ServerHandler.readPosition()
        }

        fun toOsmLat(lat: Double, offsetY: Int): Int {
            return ((lat + 90.0) * 1000000.0 + 0.5).toInt() + offsetY
        }
    }
}
