package com.danemadsen.atlas.auto

import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.TravelEstimate
import com.danemadsen.atlas.nav.NavigationProgress
import com.danemadsen.atlas.routing.GeoPoint
import com.danemadsen.atlas.routing.TurnCommand
import com.danemadsen.atlas.routing.TurnPoint
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM coverage of the Snapshot -> car model mapping. */
class CarProgressMappingTest {

    private fun snapshot(
        remainingMeters: Double,
        distanceToTurn: Double,
        nextTurn: TurnPoint?,
    ): NavigationProgress.Snapshot =
        NavigationProgress.Snapshot(
            snapped = GeoPoint(0.0, 0.0),
            nextTurn = nextTurn,
            distanceToNextTurnMeters = distanceToTurn,
            remainingMeters = remainingMeters,
            remainingSeconds = 600,
            bearing = null,
            arrived = false,
            offRoute = false,
        )

    private fun turn(command: TurnCommand, street: String? = null) = TurnPoint(
        command = command,
        lon = 0.0,
        lat = 0.0,
        pointIndex = 1,
        distanceFromPreviousMeters = 0.0,
        streetName = street,
    )

    @Test
    fun stepDistanceUsesMetersUnderAKilometer() {
        val distance = stepDistance(850.0)
        assertEquals(850.0, distance.displayDistance, 1e-6)
        assertEquals(Distance.UNIT_METERS, distance.displayUnit)
    }

    @Test
    fun stepDistanceUsesOneDecimalKmAtOrAboveOneKilometer() {
        val distance = stepDistance(12_400.0)
        assertEquals(12.4, distance.displayDistance, 1e-6)
        assertEquals(Distance.UNIT_KILOMETERS_P1, distance.displayUnit)
    }

    @Test
    fun destinationEstimateArithmeticUsesTheInjectedClock() {
        val now = ZonedDateTime.parse("2026-09-06T10:00:00Z")
        val estimate = destinationEstimate(snapshot(remainingMeters = 5_000.0, distanceToTurn = 0.0, nextTurn = null), now)
        assertEquals(600L, estimate.remainingTimeSeconds)
        assertEquals(5.0, estimate.remainingDistance!!.displayDistance, 1e-6)
        assertEquals(Distance.UNIT_KILOMETERS_P1, estimate.remainingDistance!!.displayUnit)
        assertEquals(
            now.plusSeconds(600).toInstant().toEpochMilli(),
            estimate.arrivalTimeAtDestination!!.timeSinceEpochMillis,
        )
    }

    @Test
    fun currentStepMapsCommandStreetAndDistance() {
        val step = currentStep(snapshot(remainingMeters = 900.0, distanceToTurn = 200.0, nextTurn = turn(TurnCommand.TURN_LEFT, "Main Street")))
        assertNotNull(step)
        // CarText is SpannableString-backed: its contents are unreadable on
        // the plain JVM, so assert presence and maneuver type only.
        assertNotNull(step!!.cue)
        assertNotNull(step.road)
        assertEquals(Maneuver.TYPE_TURN_NORMAL_LEFT, step.maneuver!!.type)
    }

    @Test
    fun currentStepDropsTheRoadWhenTheStreetIsUnnamed() {
        val step = currentStep(snapshot(remainingMeters = 900.0, distanceToTurn = 200.0, nextTurn = turn(TurnCommand.TURN_LEFT)))
        assertNotNull(step)
        assertNull(step!!.road)
        assertEquals(Maneuver.TYPE_TURN_NORMAL_LEFT, step.maneuver!!.type)
    }

    @Test
    fun currentStepIsNullWithoutANextTurn() {
        assertNull(currentStep(snapshot(remainingMeters = 900.0, distanceToTurn = 0.0, nextTurn = null)))
    }

    @Test
    fun buildTripCarriesDestinationAndStepOrLoads() {
        val now = ZonedDateTime.parse("2026-09-06T10:00:00Z")
        assertTrue(buildTrip(null, now).isLoading)
        val trip = buildTrip(
            snapshot(
                remainingMeters = 5_000.0,
                distanceToTurn = 200.0,
                nextTurn = turn(TurnCommand.TURN_LEFT, "Main Street"),
            ),
            now,
        )
        assertFalse(trip.isLoading)
        assertEquals(1, trip.destinations.size)
        assertEquals(1, trip.steps.size)
        val step_estimate = trip.stepTravelEstimates.single()
        assertEquals(200.0, step_estimate.remainingDistance!!.displayDistance, 1e-6)
    }
}