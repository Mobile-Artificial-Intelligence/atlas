package com.danemadsen.atlas.nav

import com.danemadsen.atlas.routing.*
import org.junit.Test
import kotlin.test.*

class WaypointNavigationTest {
    private fun point(lat: Double, lon: Double = 0.0) = GeoPoint(lon, lat)
    private fun route(points: List<GeoPoint>, indices: List<Int>) = RouteResult(
        profile = RouteProfile.CAR, origin = points.first(), destination = points.last(),
        distanceMeters = 3000, durationSeconds = 600, ascendMeters = 0, points = points,
        waypoints = indices.map { RouteWaypoint(points[it], it) },
    )

    @Test fun outAndBackVisitsStopBeforeFollowingTheSameRoadHome() {
        val origin = point(0.0)
        val via = point(0.01)
        val progress = NavigationProgress(route(listOf(origin, via, origin), listOf(1)))
        assertFalse(progress.update(origin).snapshot.arrived)
        assertTrue(progress.update(point(0.005)).snapshot.remainingMeters > 1600)
        val stop = progress.update(via)
        assertFalse(stop.events.arrived)
        assertEquals(listOf("You have reached stop 1."), stop.events.announcements)
        assertTrue(progress.remainingWaypoints().isEmpty())
        assertTrue(progress.update(point(0.005)).snapshot.remainingMeters in 550.0..560.0)
        assertTrue(progress.update(origin).events.arrived)
        assertFalse(progress.update(origin).events.arrived)
    }

    @Test fun rerouteKeepsUnvisitedStopsInOrder() {
        val points = listOf(point(0.0), point(0.01), point(0.02), point(0.03))
        val progress = NavigationProgress(route(points, listOf(1, 2)))
        assertEquals(points.subList(1, 3), progress.remainingWaypoints())
        progress.update(points[1])
        assertEquals(listOf(points[2]), progress.remainingWaypoints())
        repeat(5) { progress.update(point(0.015, 0.003)) }
        assertEquals(listOf(points[2]), progress.remainingWaypoints())
        assertEquals(1, progress.completedStops)
    }

    @Test fun offRouteFixBeyondStopCannotConsumeItOrArrive() {
        val points = listOf(point(0.0), point(0.01), point(0.02))
        val progress = NavigationProgress(route(points, listOf(1)))
        val step = progress.update(point(0.03, 0.003))
        assertFalse(step.events.arrived)
        assertEquals(0, progress.completedStops)
        assertEquals(listOf(points[1]), progress.remainingWaypoints())
    }

    @Test fun coincidentSnappedStopsRemainTraversable() {
        val points = listOf(point(0.0), point(0.01), point(0.02))
        val progress = NavigationProgress(route(points, listOf(1, 1)))
        assertFalse(progress.update(points[1]).events.arrived)
        assertEquals(2, progress.completedStops)
        assertTrue(progress.update(points.last()).events.arrived)
    }
}
