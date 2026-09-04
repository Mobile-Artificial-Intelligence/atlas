package com.danemadsen.atlas.location

import com.danemadsen.atlas.routing.GeoPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The puck's GPS-loss state machine: fixes → Active, silence past the
 * threshold → Lost at the LAST fix, a returning fix → Active again. All
 * timing is virtual (runTest), so the 10 s threshold costs nothing.
 */
class LocationPresenceTest {

    private val lost_threshold_ms = 10_000L

    @Test
    fun streamGoingQuietDegradesToLostAtLastFix() = runTest {
        val fixes = fixesWith(
            LocationTracker.Fix(GeoPoint(1.0, 2.0), null, at_ms = 0) at 0,
            LocationTracker.Fix(GeoPoint(3.0, 4.0), 90.0, at_ms = 3_000) at 3_000,
        )

        val states = fixes.withLossThreshold(lost_threshold_ms).toList()

        assertEquals(
            listOf(
                LocationPresence.Active(GeoPoint(1.0, 2.0), at_ms = 0),
                LocationPresence.Active(GeoPoint(3.0, 4.0), at_ms = 3_000),
                // upstream ends at t=3s, but the last fix's loss timeout
                // still fires at t=13s
                LocationPresence.Lost(GeoPoint(3.0, 4.0)),
            ),
            states,
        )
    }

    @Test
    fun steadyFixesNeverGoLost() = runTest {
        val fixes = flow {
            repeat(6) { i ->
                emit(LocationTracker.Fix(GeoPoint(i.toDouble(), 0.0), null, at_ms = i * 4_000L))
                delay(4_000) // under the threshold every time
            }
        }

        val states = fixes.withLossThreshold(lost_threshold_ms).toList()

        // No Lost in between the fixes; the single trailing Lost is the
        // last fix's timeout firing after upstream completes (the stream
        // ended, which the GPS stream never does while the app lives).
        assertEquals(7, states.size)
        assertTrue(states.dropLast(1).all { it is LocationPresence.Active })
        assertEquals(LocationPresence.Lost(GeoPoint(5.0, 0.0)), states.last())
    }

    @Test
    fun aFixAfterLostReturnsToActive() = runTest {
        val fixes = fixesWith(
            LocationTracker.Fix(GeoPoint(1.0, 2.0), null, at_ms = 0) at 0,
            LocationTracker.Fix(GeoPoint(3.0, 4.0), null, at_ms = 12_000) at 12_000,
        )

        val states = fixes.withLossThreshold(lost_threshold_ms).toList()

        assertEquals(
            listOf(
                LocationPresence.Active(GeoPoint(1.0, 2.0), at_ms = 0),
                LocationPresence.Lost(GeoPoint(1.0, 2.0)), // t=10s
                LocationPresence.Active(GeoPoint(3.0, 4.0), at_ms = 12_000), // t=12s
                LocationPresence.Lost(GeoPoint(3.0, 4.0)), // trailing timeout
            ),
            states,
        )
    }

    /** (fix, emit-at-virtual-ms) pairs → one flow. */
    private infix fun LocationTracker.Fix.at(emit_ms: Long) = this to emit_ms

    private fun fixesWith(vararg schedule: Pair<LocationTracker.Fix, Long>): Flow<LocationTracker.Fix> =
        flow {
            var now = 0L
            for ((fix, at) in schedule) {
                delay(at - now)
                now = at
                emit(fix)
            }
        }
}