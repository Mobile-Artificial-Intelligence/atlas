package com.danemadsen.atlas.nav

import com.danemadsen.atlas.routing.GeoPoint
import com.danemadsen.atlas.routing.RouteProfile
import com.danemadsen.atlas.routing.RouteResult
import com.danemadsen.atlas.routing.TurnCommand
import com.danemadsen.atlas.routing.TurnPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic-fix coverage for the pure progress engine: the same class the
 * on-device acceptance test drives with `adb emu geo fix`. The fixture is
 * a straight meridian line (0.001° lat = 111.32 m exactly, per the engine's
 * METERS_PER_DEGREE) with one left turn a third of the way up and the
 * arrival hint at the top.
 */
class NavigationProgressTest {

    private fun fix(lat: Double, lon: Double = 0.0) = GeoPoint(lon, lat)

    private fun route(): RouteResult {
        val points = (0..10).map { fix(it * 0.001) }
        return RouteResult(
            profile = RouteProfile.CAR,
            origin = points.first(),
            destination = points.last(),
            distanceMeters = 1_113,
            durationSeconds = 600,
            ascendMeters = 0,
            points = points,
            turns = listOf(
                TurnPoint(
                    command = TurnCommand.TURN_LEFT,
                    lon = 0.0,
                    lat = 0.003,
                    pointIndex = 3,
                    distanceFromPreviousMeters = 333.96,
                    streetName = "Main Street",
                ),
                TurnPoint(
                    command = TurnCommand.ARRIVE,
                    lon = 0.0,
                    lat = 0.010,
                    pointIndex = 10,
                    distanceFromPreviousMeters = 779.24,
                    streetName = null,
                ),
            ),
        )
    }

    @Test
    fun initialSnapshotHasNoFixAndShowsFirstTurn() {
        val progress = NavigationProgress(route())
        val snapshot = progress.initial()

        assertNull(snapshot.snapped)
        assertEquals(TurnCommand.TURN_LEFT, snapshot.nextTurn?.command)
        // 3 segments * 111.32 m — assert a window, not the exact product.
        assertTrue(snapshot.distanceToNextTurnMeters in 333.0..335.0)
        assertTrue(snapshot.remainingMeters in 1_112.0..1_114.0)
        assertEquals(600, snapshot.remainingSeconds)
        assertFalse(snapshot.arrived)
        assertFalse(snapshot.offRoute)
    }

    @Test
    fun fixSnapsOntoRouteAndCountsDown() {
        val progress = NavigationProgress(route())
        val step = progress.update(fix(0.0005))

        // Snap is the projection onto the first segment: exactly the
        // fix's own latitude (the route runs due north).
        assertEquals(5.0e-4, step.snapshot.snapped!!.lat, 1e-9)
        // Half a segment in: ~55.66 m consumed.
        assertTrue(step.snapshot.remainingMeters in 1_056.0..1_059.0)
        assertTrue(step.snapshot.distanceToNextTurnMeters in 277.0..280.0)
        // ETA scales by the remaining fraction of the route's own figure.
        assertTrue(step.snapshot.remainingSeconds in 560..575)
        // Nothing to announce: 500 m is behind us (seeded), 200 m not yet.
        assertTrue(step.events.announcements.isEmpty())
        assertFalse(step.events.arrived)
        assertFalse(step.events.recalculate)
    }

    @Test
    fun thresholdsAnnounceOnceEachAndOnlyWhenCrossed() {
        val progress = NavigationProgress(route())

        // Origin fix: seeds the 500 m threshold as passed (334 m away).
        assertTrue(progress.update(fix(0.0)).events.announcements.isEmpty())

        // Cross 200 m: announced once…
        val at_200 = progress.update(fix(0.0015)).events.announcements
        assertEquals(listOf("In 200 meters, Turn left onto Main Street"), at_200)
        // …and never again for the same fix distance.
        assertTrue(progress.update(fix(0.0015)).events.announcements.isEmpty())

        // Cross 50 m: the last threshold for this turn.
        val at_50 = progress.update(fix(0.0028)).events.announcements
        assertEquals(listOf("In 50 meters, Turn left onto Main Street"), at_50)

        // Still before the turn: no more announcements exist to give.
        assertTrue(progress.update(fix(0.0029)).events.announcements.isEmpty())
    }

    @Test
    fun turnAdvancesOncePassedAndSeedsTheNextTurn() {
        val progress = NavigationProgress(route())
        progress.update(fix(0.0))

        // Past the 333.96 m turn by more than TURN_PASS_METERS.
        val step = progress.update(fix(0.0035))

        assertEquals(TurnCommand.ARRIVE, step.snapshot.nextTurn?.command)
        // 1113.2 total - 389.62 along.
        assertTrue(step.snapshot.distanceToNextTurnMeters in 722.0..725.0)

        // The arrival turn's thresholds start unspoken: 500 m is still
        // AHEAD, so it must not have been seeded away.
        val crossing = progress.update(fix(0.0060)).events.announcements
        // Along 667.92, arrival 445.28 m ahead: 500 m crossed, 200 not.
        assertEquals(listOf("In 500 meters, Arrive at your destination"), crossing)
    }

    @Test
    fun offRouteStreakNeedsThreeFixesToRecalculate() {
        val progress = NavigationProgress(route())

        // ~1.1 km east of the meridian line: far past the 40 m limit.
        val far_off = fix(lat = 0.005, lon = 0.01)
        val first = progress.update(far_off)
        assertFalse(first.events.recalculate)
        assertTrue(first.snapshot.offRoute)
        assertFalse(progress.update(far_off).events.recalculate)
        // The third consecutive far-off fix is the one that fires.
        assertTrue(progress.update(far_off).events.recalculate)

        // Back on the line resets the streak immediately.
        val back_on = progress.update(fix(0.005))
        assertFalse(back_on.events.recalculate)
        assertFalse(back_on.snapshot.offRoute)
    }

    @Test
    fun arrivalAnnouncesOnceAndLatches() {
        val progress = NavigationProgress(route())
        progress.update(fix(0.0))

        val step = progress.update(fix(0.010))
        assertTrue(step.events.arrived)
        assertTrue(step.snapshot.arrived)
        assertEquals(listOf("You have arrived."), step.events.announcements)

        // A subsequent wandering fix must neither re-announce nor
        // un-arrive: the session is over the moment it fires.
        val after = progress.update(fix(0.0))
        assertFalse(after.events.arrived)
        assertTrue(after.snapshot.arrived)
        assertTrue(after.events.announcements.isEmpty())
    }

    @Test
    fun arrivalIsRouteProgressNotStraightLineToThePin() {
        // The pin sits 11 m from the origin — inside ARRIVAL_METERS — but
        // a kilometer of route must be driven first. Straight-line
        // arrival would fire on the very first fix.
        val pin_close_to_origin = route().copy(
            destination = fix(0.0, lon = 0.0001),
        )
        val progress = NavigationProgress(pin_close_to_origin)
        val step = progress.update(fix(0.0))
        assertFalse(step.events.arrived)
        assertFalse(step.snapshot.arrived)

        // The polyline ends where the router's crosspoint is, which can
        // be far from the raw pin: reaching the END OF THE ROUTE is what
        // arrival means, so this must arrive even 111 m shy of the pin.
        val pin_far_off_route = route().copy(
            destination = fix(0.011, lon = 0.0),
        )
        val progress_far = NavigationProgress(pin_far_off_route)
        progress_far.update(fix(0.0))
        val end_step = progress_far.update(fix(0.010))
        assertTrue(end_step.events.arrived)
        // The still-pending TURN_LEFT is consumed by this fix, but the
        // arrival hint never fires a turn-now cue: "You have arrived."
        // is its cue.
        assertTrue(end_step.events.turnsConsumed.none { it.command == TurnCommand.ARRIVE })
    }

    @Test
    fun clusterTurnsAreBothConsumedAndTheSkippedOneAnnounced() {
        // Two maneuvers 5.6 m apart: one fix passes both. The second was
        // never the banner turn, so no threshold could announce it — the
        // engine must speak it unprefixed instead.
        val points = listOf(
            fix(0.0),
            fix(0.001),
            fix(0.002),
            fix(0.003),
            fix(0.00305),
            fix(0.00405),
            fix(0.00505),
        )
        val clustered = RouteResult(
            profile = RouteProfile.CAR,
            origin = points.first(),
            destination = points.last(),
            distanceMeters = 557,
            durationSeconds = 300,
            ascendMeters = 0,
            points = points,
            turns = listOf(
                TurnPoint(
                    command = TurnCommand.TURN_LEFT,
                    lon = 0.0,
                    lat = 0.003,
                    pointIndex = 3,
                    distanceFromPreviousMeters = 333.96,
                    streetName = "Main Street",
                ),
                TurnPoint(
                    command = TurnCommand.TURN_RIGHT,
                    lon = 0.0,
                    lat = 0.00305,
                    pointIndex = 4,
                    distanceFromPreviousMeters = 5.57,
                    streetName = "Oak Street",
                ),
            ),
        )
        val progress = NavigationProgress(clustered)
        progress.update(fix(0.0))

        // Past both: 334 m + 5.6 m < 340 m along.
        val step = progress.update(fix(0.00305 + 0.00002))

        assertEquals(2, step.events.turnsConsumed.size)
        assertEquals(TurnCommand.TURN_RIGHT, step.events.turnsConsumed.last().command)
        assertTrue("Turn right onto Oak Street" in step.events.announcements)
        assertNull(step.snapshot.nextTurn)
    }

    @Test
    fun consumedTurnFiresTheTurnNowEvent() {
        val progress = NavigationProgress(route())
        progress.update(fix(0.0))

        // Crossing the 15 m pass boundary consumes the turn.
        val step = progress.update(fix(0.0032))
        assertEquals(listOf(TurnCommand.TURN_LEFT), step.events.turnsConsumed.map { it.command })
    }

    @Test
    fun announcementPrefixOmitsZeroDistance() {
        val turn = route().turns.first()
        assertEquals("Turn left onto Main Street", announcementFor(turn, 0))
        assertEquals("In 200 meters, Turn left onto Main Street", announcementFor(turn, 200))
    }
}

/** The instruction sentences the banner shows and TTS speaks. */
class VoiceHintTextTest {

    @Test
    fun streetAppendedWhenKnown() {
        assertEquals("Turn left onto Main Street", turnInstruction(TurnCommand.TURN_LEFT, "Main Street"))
        assertEquals("Continue straight onto High Street", turnInstruction(TurnCommand.STRAIGHT, "High Street"))
    }

    @Test
    fun noStreetMeansNoDanglingOnto() {
        assertEquals("Turn right", turnInstruction(TurnCommand.TURN_RIGHT, null))
        assertEquals("Turn right", turnInstruction(TurnCommand.TURN_RIGHT, "  "))
    }

    @Test
    fun arrivalNeverTakesAStreet() {
        assertEquals("Arrive at your destination", turnInstruction(TurnCommand.ARRIVE, "Main Street"))
    }

    @Test
    fun everyCommandHasASentence() {
        // Exhaustiveness guard: a new TurnCommand that forgets its verb
        // fails here at compile time (the when in turnInstruction is
        // already exhaustive), and the spot-checks below pin the phrasing.
        assertEquals("Make a U-turn", turnInstruction(TurnCommand.U_TURN, null))
        assertEquals("At the roundabout, go around and exit", turnInstruction(TurnCommand.ROUNDABOUT, null))
        assertEquals("Keep right", turnInstruction(TurnCommand.KEEP_RIGHT, null))
        assertEquals("Take the exit on the left", turnInstruction(TurnCommand.EXIT_LEFT, null))
        assertEquals("Turn slightly left onto Ramp", turnInstruction(TurnCommand.TURN_SLIGHT_LEFT, "Ramp"))
    }
}