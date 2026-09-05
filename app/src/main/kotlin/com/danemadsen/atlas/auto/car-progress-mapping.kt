package com.danemadsen.atlas.auto

import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.Trip
import androidx.car.app.navigation.model.TravelEstimate
import com.danemadsen.atlas.nav.NavigationProgress
import com.danemadsen.atlas.routing.formatDistance
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/**
 * Pure JVM helpers turning [NavigationProgress.Snapshot] into car model
 * values so template code stays thin and testable.
 */

/** Meters under a km, then one-decimal km — the same shape [formatDistance] renders. */
fun stepDistance(meters: Double): Distance =
    if (meters < 1000.0) {
        Distance.create(meters, Distance.UNIT_METERS)
    } else {
        Distance.create(meters / 1000.0, Distance.UNIT_KILOMETERS_P1)
    }

/** The destination's remaining distance + ETA, [now] injectable for tests. */
fun destinationEstimate(
    snapshot: NavigationProgress.Snapshot,
    now: ZonedDateTime = ZonedDateTime.now(),
): TravelEstimate {
    val arrival = now.plusSeconds(snapshot.remainingSeconds.toLong())
    return TravelEstimate.Builder(stepDistance(snapshot.remainingMeters), arrival)
        .setRemainingTimeSeconds(snapshot.remainingSeconds.toLong())
        .build()
}

/** The banner turn as a car [Step], null before the first turn is known. */
fun currentStep(snapshot: NavigationProgress.Snapshot): Step? =
    snapshot.nextTurn?.let { turn ->
        // The Step's cue is its distance line — the same rendering
        // formatDistance() gives the in-app banner.
        Step.Builder(formatDistance(snapshot.distanceToNextTurnMeters.roundToInt()))
            .setManeuver(maneuver(turn.command))
            .apply { turn.streetName?.let { setRoad(it) } }
            .build()
    }

/** The step's remaining distance, wrapped in a [TravelEstimate]. */
fun stepEstimate(snapshot: NavigationProgress.Snapshot, now: ZonedDateTime): TravelEstimate =
    TravelEstimate.Builder(stepDistance(snapshot.distanceToNextTurnMeters), now).build()

/**
 * The turn-by-turn [Trip] the car host renders: destination + its ETA,
 * the upcoming step, or a loading trip while the fix/recorridor is pending.
 */
fun buildTrip(snapshot: NavigationProgress.Snapshot?, now: ZonedDateTime): Trip {
    val builder = Trip.Builder()
    if (snapshot == null) return builder.setLoading(true).build()
    builder.addDestination(
        Destination.Builder().setName("Destination").build(),
        destinationEstimate(snapshot, now),
    )
    currentStep(snapshot)?.let { step -> builder.addStep(step, stepEstimate(snapshot, now)) }
    return builder.setLoading(false).build()
}