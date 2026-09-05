package com.danemadsen.atlas.nav

import com.danemadsen.atlas.routing.GeoPoint
import com.danemadsen.atlas.routing.RouteProfile
import com.danemadsen.atlas.routing.RouteResult
import com.danemadsen.atlas.routing.TurnCommand
import com.danemadsen.atlas.routing.TurnPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-fixture coverage for what the shade renders: the progress
 * fraction the bar is driven from and the status-line precedence
 * (arrived > recalculating > no-fix > remaining/eta/turn).
 */
class NavigationNotificationTest {

    /** A straight meridian line, 1 113 m — see NavigationProgressTest. */
    private fun route(): RouteResult {
        val points = (0..10).map { GeoPoint(0.0, it * 0.001) }
        return RouteResult(
            profile = RouteProfile.CAR,
            origin = points.first(),
            destination = points.last(),
            distanceMeters = 1_113,
            durationSeconds = 600,
            ascendMeters = 0,
            points = points,
        )
    }

    private fun snapshot(remainingMeters: Double): NavigationProgress.Snapshot =
        NavigationProgress.Snapshot(
            snapped = GeoPoint(0.0, 0.0),
            nextTurn = null,
            distanceToNextTurnMeters = 0.0,
            remainingMeters = remainingMeters,
            remainingSeconds = 60,
            bearing = null,
            arrived = false,
            offRoute = false,
        )

    // ---- routeProgressFraction ----

    @Test
    fun fractionIsNullBeforeTheFirstFix() {
        assertNull(routeProgressFraction(route(), null))
    }

    @Test
    fun fractionIsZeroAtTheOrigin() {
        val fraction = routeProgressFraction(route(), snapshot(remainingMeters = 1_113.0))
        assertEquals(0.0, fraction!!, 1e-9)
    }

    @Test
    fun fractionIsOneAtArrival() {
        val fraction = routeProgressFraction(route(), snapshot(remainingMeters = 0.0))
        assertEquals(1.0, fraction!!, 1e-9)
    }

    @Test
    fun fractionIsAboutHalfAtTheMidpoint() {
        val fraction = routeProgressFraction(route(), snapshot(remainingMeters = 556.5))
        assertEquals(0.5, fraction!!, 0.001)
    }

    @Test
    fun fractionIsClampedIntoZeroOne() {
        // GPS jitter can push remainingMeters past the route total.
        assertTrue(routeProgressFraction(route(), snapshot(remainingMeters = -5.0))!! <= 1.0)
        assertTrue(routeProgressFraction(route(), snapshot(remainingMeters = 2_000.0))!! >= 0.0)
    }

    // ---- navigationStatusText ----

    @Test
    fun arrivalWinsOverEverything() {
        val text = navigationStatusText(snapshot(remainingMeters = 500.0), arrived = true, recalculating = true)
        assertEquals("Arrived at your destination", text)
    }

    @Test
    fun recalculatingBeatsTheStatusLine() {
        val text = navigationStatusText(snapshot(remainingMeters = 500.0), arrived = false, recalculating = true)
        assertEquals("Recalculating route…", text)
    }

    @Test
    fun noFixFallsBackToThePlainLine() {
        val text = navigationStatusText(null, arrived = false, recalculating = false)
        assertEquals("Navigating to your destination", text)
    }

    @Test
    fun statusLineCarriesRemainingEtaAndTurn() {
        val turn = NavigationProgress.Snapshot(
            snapped = GeoPoint(0.0, 0.0),
            nextTurn = com.danemadsen.atlas.routing.TurnPoint(
                command = TurnCommand.TURN_LEFT,
                lon = 0.0,
                lat = 0.003,
                pointIndex = 3,
                distanceFromPreviousMeters = 300.0,
                streetName = "Main Street",
            ),
            distanceToNextTurnMeters = 200.0,
            remainingMeters = 900.0,
            remainingSeconds = 120,
            bearing = null,
            arrived = false,
            offRoute = false,
        )
        val text = navigationStatusText(turn, arrived = false, recalculating = false)
        assertEquals("900 m • 2 min — Turn left onto Main Street", text)
    }

    @Test
    fun arrivingTurnIsMarkedWithoutAStreet() {
        val arrive = NavigationProgress.Snapshot(
            snapped = GeoPoint(0.0, 0.0),
            nextTurn = com.danemadsen.atlas.routing.TurnPoint(
                command = TurnCommand.ARRIVE,
                lon = 0.0,
                lat = 0.010,
                pointIndex = 10,
                distanceFromPreviousMeters = 0.0,
                streetName = null,
            ),
            distanceToNextTurnMeters = 10.0,
            remainingMeters = 10.0,
            remainingSeconds = 5,
            bearing = null,
            arrived = false,
            offRoute = false,
        )
        val text = navigationStatusText(arrive, arrived = false, recalculating = false)
        assertEquals("10 m • 1 min — arrive", text)
    }
}