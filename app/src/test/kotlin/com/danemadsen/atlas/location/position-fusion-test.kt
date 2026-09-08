package com.danemadsen.atlas.location

import com.danemadsen.atlas.location.PositionFusion.FusionMode
import com.danemadsen.atlas.location.PositionFusion.HeadingSample
import com.danemadsen.atlas.location.PositionFusion.HeadingSource
import com.danemadsen.atlas.nav.metersBetween
import com.danemadsen.atlas.routing.GeoPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The dead-reckoning fusion engine over synthetic fixes and an injected
 * clock: extrapolation between fixes, the hold-then-decay speed
 * profile, mode banding with hysteresis, the blend/snap/outlier rules,
 * step displacement, heading trust, and the loss behavior the presence
 * layer maps Active/Lost from. All times are elapsed-realtime
 * milliseconds, exactly what the platform feeds the engine.
 */
class PositionFusionTest {

    /** Brisbane, where the on-device drills run — keeps cos(lat) honest. */
    private val p0 = GeoPoint(151.0, -27.5)

    @Test
    fun extrapolationAdvancesAtKnownSpeedAndHeading() {
        val engine = PositionFusion(walking_enabled = false)
        // 10 m/s due east from t=0.
        engine.onFix(fix(p0, bearing = 90.0, speed = 10.0, elapsed = 0), now_elapsed_ms = 0)

        val first = engine.tick(0, fix_confirmed = true)
        var prev = first
        var traveled = 0.0
        for (t in 1..10) {
            val pos = engine.tick(t * 100L)
            traveled += metersBetween(prev.point, pos.point)
            prev = pos
        }

        // 10 m east in one second, in ten smooth ~1 m steps, latitude
        // untouched — pure eastward motion.
        assertEquals(10.0, traveled, 0.5)
        assertEquals(p0.lat, prev.point.lat, 1e-6)
        assertTrue(prev.point.lon > p0.lon)
        assertEquals(PositionFusion.FusionMode.DRIVING, first.mode)
        assertEquals(HeadingSource.GPS, first.heading_source)
    }

    @Test
    fun speedHoldsThenDecaysThenFreezes() {
        val engine = PositionFusion(walking_enabled = false)
        engine.onFix(fix(p0, bearing = 0.0, speed = 10.0, elapsed = 0), now_elapsed_ms = 0)

        // Within the hold window the chip speed is trusted as-is.
        assertEquals(10.0, engine.tick(3_000L).speed_mps, 1e-9)
        // One half-life (2 s) past the hold: half the speed.
        assertEquals(5.0, engine.tick(5_000L).speed_mps, 0.1)
        // And by 20 s the position is frozen: nothing moves after DR_MAX.
        val frozen = engine.tick(20_000L).point
        assertEquals(frozen, engine.tick(25_000L).point)
        assertTrue(engine.tick(25_000L).speed_mps < 0.1)
    }

    @Test
    fun modeBandingWithTwoFixHysteresis() {
        val engine = PositionFusion(walking_enabled = true)
        // Start walking (breakout from the initial Stationary is immediate).
        engine.onFix(fix(p0, bearing = 0.0, speed = 1.5, elapsed = 0), now_elapsed_ms = 0)
        assertEquals(PositionFusion.FusionMode.WALKING, engine.tick(0, true).mode)

        // One driving-speed fix does not flip the model...
        engine.onFix(fix(displaced(p0, 3.0), bearing = 0.0, speed = 3.0, elapsed = 1_000), now_elapsed_ms = 1_000)
        assertEquals(PositionFusion.FusionMode.WALKING, engine.tick(1_000, true).mode)
        // ... the second one does.
        engine.onFix(fix(displaced(p0, 6.0), bearing = 0.0, speed = 3.0, elapsed = 2_000), now_elapsed_ms = 2_000)
        assertEquals(PositionFusion.FusionMode.DRIVING, engine.tick(2_000, true).mode)

        // Stationary entry needs a 3 s span of still fixes.
        engine.onFix(fix(displaced(p0, 6.1), bearing = null, speed = 0.2, elapsed = 3_000), now_elapsed_ms = 3_000)
        assertEquals(PositionFusion.FusionMode.DRIVING, engine.tick(3_000, true).mode)
        engine.onFix(fix(displaced(p0, 6.2), bearing = null, speed = 0.2, elapsed = 6_000), now_elapsed_ms = 6_000)
        assertEquals(PositionFusion.FusionMode.STATIONARY, engine.tick(6_000, true).mode)
    }

    @Test
    fun softBlendMovesTheEstimateTowardTheFixWithoutJumping() {
        val engine = PositionFusion(walking_enabled = false)
        engine.onFix(fix(p0, bearing = 90.0, speed = 10.0, accuracy = 15.0, elapsed = 0), now_elapsed_ms = 0)

        // At t=1s the estimate is 10 m east; the fix lands 3 m ahead of
        // it (13 m east). Residual 3 m inside a 30 m budget: the anchor
        // moves a small fraction, not the full residual.
        val expected_e = displaced(p0, 10.0)
        engine.onFix(fix(displaced(p0, 13.0), bearing = 90.0, speed = 10.0, accuracy = 15.0, elapsed = 1_000), now_elapsed_ms = 1_000)
        val blended = engine.tick(1_000, fix_confirmed = true).point

        assertEquals(0.45, metersBetween(blended, expected_e), 0.1) // 0.15 × 3 m
        assertEquals(2.55, metersBetween(blended, displaced(p0, 13.0)), 0.1)
    }

    @Test
    fun largeResidualHardSnapsToTheFix() {
        val engine = PositionFusion(walking_enabled = false)
        engine.onFix(fix(p0, bearing = 90.0, speed = 10.0, accuracy = 15.0, elapsed = 0), now_elapsed_ms = 0)

        // 60 m off the 10 m estimate, well outside the 30 m budget, with
        // decent accuracy: the estimate snaps to the fix outright.
        val far = displaced(p0, 70.0)
        engine.onFix(fix(far, bearing = 90.0, speed = 10.0, accuracy = 15.0, elapsed = 1_000), now_elapsed_ms = 1_000)
        assertEquals(far, engine.tick(1_000, fix_confirmed = true).point)
    }

    @Test
    fun outlierIsQuarantinedThenAcceptedOnSecondStrike() {
        val engine = PositionFusion(walking_enabled = false)
        engine.onFix(fix(p0, bearing = 90.0, speed = 10.0, accuracy = 15.0, elapsed = 0), now_elapsed_ms = 0)

        // First distant fix (200 m off a 90 m budget): dismissed — the
        // estimate keeps dead-reckoning as if it never landed, but the
        // SIGNAL stayed fresh.
        val echo = displaced(p0, 210.0)
        engine.onFix(fix(echo, bearing = 90.0, speed = 10.0, accuracy = 15.0, elapsed = 1_000), now_elapsed_ms = 1_000)
        val after_echo = engine.tick(1_000, fix_confirmed = true)
        assertTrue(metersBetween(after_echo.point, displaced(p0, 10.0)) < 1.0)
        assertTrue(after_echo.fix_fresh)
        assertTrue(after_echo.last_fix_at_ms > 0L)

        // Second distant fix in a row: the user really moved — accept it.
        val truth = displaced(p0, 230.0)
        engine.onFix(fix(truth, bearing = 90.0, speed = 10.0, accuracy = 15.0, elapsed = 2_000), now_elapsed_ms = 2_000)
        assertEquals(truth, engine.tick(2_000, fix_confirmed = true).point)
    }

    @Test
    fun stationaryFixesAreDampedAroundTheAnchor() {
        val engine = PositionFusion(walking_enabled = true)
        // Jitter ±5 m around p0 at zero speed, spaced so the 3 s still
        // span is crossed.
        engine.onFix(fix(p0, bearing = null, speed = 0.0, accuracy = 10.0, elapsed = 0), now_elapsed_ms = 0)
        engine.onFix(fix(displaced(p0, 5.0), bearing = null, speed = 0.0, accuracy = 10.0, elapsed = 1_000), now_elapsed_ms = 1_000)
        engine.onFix(fix(displaced(p0, -5.0 + 360.0 * 0), bearing = null, speed = 0.0, accuracy = 10.0, elapsed = 4_000), now_elapsed_ms = 4_000)
        engine.onFix(fix(displaced(p0, 2.0), bearing = null, speed = 0.0, accuracy = 10.0, elapsed = 5_000), now_elapsed_ms = 5_000)

        val pos = engine.tick(5_000L, fix_confirmed = true)
        assertEquals(PositionFusion.FusionMode.STATIONARY, pos.mode)
        assertNull(pos.heading_deg) // no arrow while still
        assertEquals(0.0, pos.speed_mps, 1e-9)
        // The EMA has pulled the estimate toward the jitter's mean —
        // much closer to the anchor than any single fix's offset.
        assertTrue(metersBetween(pos.point, p0) < 1.5)
    }

    @Test
    fun stepsAdvanceTheWalkingEstimateAndLearnThePace() {
        val engine = PositionFusion(walking_enabled = true)
        // Heading due north (GPS bearing 0° at trusted walking speed).
        engine.onFix(fix(p0, bearing = 0.0, speed = 1.5, accuracy = 10.0, elapsed = 0), now_elapsed_ms = 0)

        // Ten steps advance the anchor by the default 0.72 m each.
        for (i in 1..10) engine.onStep(1_000L + i * 100L)
        val after_steps = engine.tick(2_000L, fix_confirmed = true)
        assertEquals(7.2, metersBetween(after_steps.point, p0), 0.2)
        assertEquals(PositionFusion.FusionMode.WALKING, after_steps.mode)

        // The next fix closes a 9 m GPS segment north over those 10
        // steps -> the learned length is 0.9 m (EMA'd per fix), and ten
        // more steps advance the estimate by it from wherever the blend
        // left the anchor.
        engine.onFix(fix(displacedNorth(p0, 9.0), bearing = 0.0, speed = 1.5, accuracy = 10.0, elapsed = 3_000), now_elapsed_ms = 3_000)
        assertEquals(0.756, engine.stepLengthForTest(), 1e-9)
        val base = engine.tick(3_000L, fix_confirmed = true).point
        for (i in 1..10) engine.onStep(4_000L + i * 100L)
        val learned = engine.tick(5_000L, fix_confirmed = true).point
        assertEquals(0.756 * 10, metersBetween(learned, base), 0.2)
    }

    @Test
    fun stepsAreIgnoredWhileFixesConfirmStillness() {
        val engine = PositionFusion(walking_enabled = true)
        // The bus case: GPS says the phone barely moves (0.2 m/s) while
        // step events fire — the steps are vibration, not walking.
        engine.onFix(fix(p0, bearing = null, speed = 0.0, accuracy = 10.0, elapsed = 0), now_elapsed_ms = 0)
        engine.onFix(fix(displaced(p0, 0.2), bearing = null, speed = 0.0, accuracy = 10.0, elapsed = 1_000), now_elapsed_ms = 1_000)
        assertEquals(PositionFusion.FusionMode.STATIONARY, engine.tick(1_000, true).mode)

        for (i in 1..5) engine.onStep(1_100L + i * 100L)
        val pos = engine.tick(1_600L, fix_confirmed = true)
        assertEquals(PositionFusion.FusionMode.STATIONARY, pos.mode)
        assertTrue(metersBetween(pos.point, displaced(p0, 0.2)) < 1.0)

        // With the fix stream silent, though, steps DO break out of the
        // still damping — a walking user in a dead zone must not freeze.
        for (i in 1..2) engine.onStep(12_000L + i * 500L)
        val breakout = engine.tick(13_000L, fix_confirmed = true)
        assertEquals(PositionFusion.FusionMode.WALKING, breakout.mode)
    }

    @Test
    fun gpsBearingOwnsHeadingAtSpeedSensorOwnsItBelow() {
        // At 5 m/s the chip course beats the rotation vector on conflict.
        val driving = PositionFusion(walking_enabled = false)
        driving.onFix(fix(p0, bearing = 0.0, speed = 5.0, accuracy = 10.0, elapsed = 0), now_elapsed_ms = 0)
        driving.onHeading(HeadingSample(30.0, 5f, 500))
        assertEquals(30.0, driving.tick(500).heading_deg!!, 1e-9) // sensor between fixes
        driving.onFix(fix(displaced(p0, 5.0), bearing = 0.0, speed = 5.0, accuracy = 10.0, elapsed = 1_000), now_elapsed_ms = 1_000)
        assertEquals(0.0, driving.tick(1_000, true).heading_deg!!, 1e-9)
        assertEquals(HeadingSource.GPS, driving.tick(1_000, true).heading_source)

        // At 0.8 m/s the chip bearing is noise: a sample disagreeing by
        // 170° is rejected and the sensor heading survives.
        val strolling = PositionFusion(walking_enabled = false)
        strolling.onFix(fix(p0, bearing = null, speed = 0.8, accuracy = 10.0, elapsed = 0), now_elapsed_ms = 0)
        strolling.onHeading(HeadingSample(0.0, 5f, 500))
        strolling.onFix(fix(displaced(p0, 0.8), bearing = 170.0, speed = 0.8, accuracy = 10.0, elapsed = 1_000), now_elapsed_ms = 1_000)
        assertEquals(0.0, strolling.tick(1_000, true).heading_deg!!, 1e-9)
        assertEquals(HeadingSource.SENSOR, strolling.tick(1_000, true).heading_source)
    }

    @Test
    fun lossKeepsDrivingThenFreezesAndFlipsFreshness() {
        val engine = PositionFusion(walking_enabled = false)
        engine.onFix(fix(p0, bearing = 90.0, speed = 10.0, accuracy = 10.0, elapsed = 0), now_elapsed_ms = 0)

        // Fresh within the 10 s threshold...
        assertTrue(engine.tick(9_500L).fix_fresh)
        // ... Lost past it — but dead reckoning still advances the puck
        // through the tunnel, per the invariant that liveness != DR.
        assertTrue(!engine.tick(10_500L).fix_fresh)
        val at_15s = engine.tick(15_000L).point
        assertTrue(metersBetween(at_15s, displaced(p0, 10.0)) > 5.0)
        // Frozen at DR_MAX: nothing moves after 20 s.
        assertEquals(engine.tick(20_000L).point, engine.tick(25_000L).point)
    }

    @Test
    fun presenceMappingKeepsTheLossSpecs() {
        // Active carries the fused point and the LAST FIX's wall clock —
        // the resume re-judge reads exactly that after a lifecycle pause.
        val fresh = PositionFusion.FusedPosition(
            point = GeoPoint(151.1, -27.5),
            heading_deg = 90.0,
            speed_mps = 5.0,
            mode = PositionFusion.FusionMode.DRIVING,
            emissions_since_fix = 3,
            accuracy_m = 10.0,
            at_elapsed_ms = 1_234,
            fix_confirmed = false,
            last_fix_at_ms = 999L,
            fix_fresh = true,
            heading_source = HeadingSource.GPS,
        )
        assertEquals(
            LocationPresence.Active(GeoPoint(151.1, -27.5), at_ms = 999L, heading_deg = 90.0),
            toPresence(fresh),
        )

        val lost = fresh.copy(fix_fresh = false)
        assertEquals(LocationPresence.Lost(GeoPoint(151.1, -27.5)), toPresence(lost))
    }

    // ----- helpers -------------------------------------------------------

    private fun fix(
        at: GeoPoint,
        bearing: Double?,
        speed: Double,
        accuracy: Double = 15.0,
        elapsed: Long,
    ) = LocationTracker.Fix(
        point = at,
        bearing = bearing,
        at_ms = elapsed, // wall clock is irrelevant to the engine
        accuracy_m = accuracy.toFloat(),
        speed_mps = speed.toFloat(),
        at_elapsed_ms = elapsed,
    )

    /** [meters] due east of [base] — lon-only, on the flat local plane. */
    private fun displaced(base: GeoPoint, meters: Double): GeoPoint =
        GeoPoint(base.lon + meters / (111_320.0 * cos(Math.toRadians(base.lat))), base.lat)

    /** [meters] due north of [base] — lat-only, on the flat local plane. */
    private fun displacedNorth(base: GeoPoint, meters: Double): GeoPoint =
        GeoPoint(base.lon, base.lat + meters / 111_320.0)
}