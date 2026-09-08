package com.danemadsen.atlas.location

import com.danemadsen.atlas.nav.metersBetween
import com.danemadsen.atlas.routing.GeoPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The dead-reckoning fusion engine: GPS fixes, rotation-vector heading
 * samples, and step-detector events in; a smooth high-rate position
 * estimate out. Pure JVM — no `android.*` imports — so the whole
 * algorithm is unit-testable the way [com.danemadsen.atlas.nav.NavigationProgress]
 * is, over synthetic inputs with an injected clock.
 *
 * Between fixes the estimate advances by dead reckoning: driving
 * extrapolates the last GPS chip speed along the heading, walking
 * advances per detected step. A fresh fix soft-blends into the estimate
 * — drift is corrected, motion stays continuous — and hard-snaps only
 * when the GPS has genuinely corrected us (tunnel exit, long outage).
 * "Signal" is still defined by fresh *fixes* (`fix_fresh`): the puck
 * goes grey at the 10 s threshold exactly as before, while dead
 * reckoning keeps the estimate advancing through a tunnel for
 * [DR_MAX_MS], then freezes. The presence layer maps [FusedPosition] to
 * Active/Lost; nothing downstream decides liveness on its own.
 */
class PositionFusion(
    /** Whether step-based walking DR is available (sensor + permission). */
    private val walking_enabled: Boolean,
) {

    /**
     * The model the estimate is currently integrating under. The step
     * detector gives walking its own displacement model; driving is
     * speed-along-heading; stationary damps the standing-still GPS cloud
     * instead of extrapolating noise into a drift.
     */
    enum class FusionMode { DRIVING, WALKING, STATIONARY }

    /**
     * Where the current heading estimate came from — debug/telemetry
     * only, but the on-device log should show exactly which signal owned
     * the heading when the puck points the wrong way.
     */
    enum class HeadingSource { GPS, SENSOR, NONE }

    /**
     * The engine's output at a moment: position, heading, speed, mode,
     * and the fix-stream liveness the presence layer maps Active/Lost
     * from. [fix_confirmed] marks the emission that immediately follows
     * a real fix — the nav service advances its engine per fix, and only
     * these emissions pace it. [emissions_since_fix] counts ticks since
     * that fix; 0 on the fix-confirmed emission itself.
     */
    data class FusedPosition(
        val point: GeoPoint,
        val heading_deg: Double?,
        val speed_mps: Double,
        val mode: FusionMode,
        val emissions_since_fix: Int,
        val accuracy_m: Double,
        val at_elapsed_ms: Long,
        val fix_confirmed: Boolean,
        val last_fix_at_ms: Long,
        val fix_fresh: Boolean,
        val heading_source: HeadingSource,
    ) {
        /** For debug logging only. */
        override fun toString(): String {
            val p = "%.5f, %.5f".format(point.lon, point.lat)
            return "FusedPosition($p mode=$mode speed=%.2f".format(speed_mps) +
                " heading=$heading_deg($heading_source) acc=$accuracy_m " +
                "fix_fresh=$fix_fresh confirmed=$fix_confirmed)"
        }
    }

    /**
     * A sensor heading sample, already converted to TRUE-north bearing
     * (declination applied) by [SensorHub] — the engine stays Android-free.
     */
    data class HeadingSample(
        val azimuth_true_deg: Double,
        val accuracy_deg: Float,
        val at_elapsed_ms: Long,
    )

    // ----- engine state -------------------------------------------------

    /** Best estimate position, valid at [anchor_elapsed_ms]. */
    private var anchor = GeoPoint(0.0, 0.0)
    private var anchor_elapsed_ms = 0L
    private var anchor_valid = false

    /** Speed the driving extrapolator integrates at (set per fix). */
    private var speed_base_mps = 0.0

    /** Heading estimate in degrees TRUE, clockwise from north. */
    private var heading_est_deg: Double? = null
    private var heading_source = HeadingSource.NONE

    private var mode = FusionMode.STATIONARY

    /** Previous fix, for speed derivation when the chip reports none. */
    private var prev_fix_point: GeoPoint? = null
    private var prev_fix_elapsed_ms = 0L

    /** 2-fix hysteresis between the moving bands. */
    private var candidate_mode: FusionMode? = null
    private var candidate_streak = 0

    /** Consecutive still fixes — Stationary entry needs a 3 s span. */
    private var still_streak = 0
    private var still_streak_start_ms = 0L

    /** Steps since the last fix; two while Stationary break out. */
    private var step_count = 0
    private var breakout_steps = 0

    /** Adaptive step length, learned from GPS distance per step (EMA). */
    private var step_length_m = STEP_LENGTH_DEFAULT_M

    /** Outlier quarantine: first distant fix dismissed, second accepted. */
    private var outlier_strikes = 0

    /**
     * Fix-stream liveness, computed per tick from receipt times. −1 is
     * the no-fix-yet sentinel — 0 is a legitimate receipt time (a fix at
     * boot), so a plain 0 default would read "no fix" for "fix at t=0".
     */
    private var last_fix_received_elapsed_ms = -1L
    private var last_fix_wall_ms = 0L
    private var last_fix_accuracy_m = DEFAULT_ACCURACY_M

    /** Stationary position damping — the EMA of fixes while still. */
    private var stationary_ema: GeoPoint? = null

    // ----- inputs -------------------------------------------------------

    /**
     * A GPS fix. The first one initializes the anchor; every later one is
     * blended against the extrapolation at the fix's own measurement time
     * (fixes land up to a second stale — blending against receipt time
     * would corrupt the residual).
     */
    fun onFix(fix: LocationTracker.Fix, now_elapsed_ms: Long) {
        if (!anchor_valid) {
            initFromFirstFix(fix, now_elapsed_ms)
            return
        }

        val fix_elapsed = if (fix.at_elapsed_ms != 0L) fix.at_elapsed_ms else now_elapsed_ms
        val e = extrapolateTo(fix_elapsed)
        val residual = metersBetween(e, fix.point)

        // Liveness: any fix received keeps the signal "fresh" even if its
        // position is dismissed as an outlier — the eyes and the nav
        // watchdog agree that signal liveness != position quality.
        last_fix_received_elapsed_ms = now_elapsed_ms
        last_fix_wall_ms = fix.at_ms

        val accuracy = fix.accuracy_m?.toDouble() ?: DEFAULT_ACCURACY_M
        last_fix_accuracy_m = accuracy

        // Outlier quarantine: a fix far outside the estimate's error
        // budget (multipath, a tunnel echo) is dismissed on the first
        // strike; a second distant fix in a row is the truth — the user
        // really was relocated (GPS toggled, walked indoors and out).
        if (residual > OUTLIER_FACTOR * accuracy + OUTLIER_BASE_M && outlier_strikes == 0) {
            outlier_strikes = 1
            return
        }
        outlier_strikes = 0

        val speed = fixSpeed(fix)
        // The extrapolator's speed is per-fix, not first-fix: DR holds the
        // LAST accepted fix's chip speed through the hold window. (Leaving
        // it at the first fix's speed made DR dead weight forever after.)
        speed_base_mps = speed
        updateMode(speed, fix_elapsed)
        blendOrSnap(e, fix.point, residual, accuracy, fix_elapsed)
        applyHeading(fix.bearing, speed)
        learnStepLength(fix.point, speed)

        prev_fix_point = fix.point
        prev_fix_elapsed_ms = fix_elapsed
        step_count = 0
    }

    /**
     * A rotation-vector heading sample (TRUE bearing, declination
     * already applied). Between fixes the sensor owns the heading —
     * including while driving, where the chip's course-over-ground wins
     * it back at the next fix. A sample arriving before the first fix is
     * dropped: the engine anchors heading to a real position.
     */
    fun onHeading(sample: HeadingSample) {
        if (!anchor_valid) return
        heading_est_deg = normalizeBearing(sample.azimuth_true_deg)
        heading_source = HeadingSource.SENSOR
    }

    /**
     * A step-detector event. While Walking each step advances the
     * estimate by the learned step length along the heading; while
     * Driving steps are ignored — the step detector fires on some
     * devices in a moving car, and GPS speed is the authority there.
     *
     * While Stationary a pair of steps only breaks out of the still
     * damping when the fix stream has gone silent (≥ [SIGNAL_LOST_MS]
     * since the last fix receipt): fresh still fixes are the authority
     * that the vibration driving the steps is not the user walking — a
     * bus, a parked-car idle. But in a dead zone (airplane mode, a
     * tunnel on foot) steps DO break out, so a walking user is not
     * pinned to a fading fix for the whole outage.
     */
    fun onStep(at_elapsed_ms: Long) {
        if (!anchor_valid) return
        when (mode) {
            FusionMode.STATIONARY -> {
                val fixes_silent =
                    last_fix_received_elapsed_ms < 0L ||
                        at_elapsed_ms - last_fix_received_elapsed_ms >= SIGNAL_LOST_MS
                if (fixes_silent) {
                    breakout_steps++
                    if (breakout_steps >= 2 && walking_enabled) {
                        mode = FusionMode.WALKING
                        stationary_ema = null
                        still_streak = 0
                        breakout_steps = 0
                        advanceByStep(at_elapsed_ms)
                    }
                }
            }
            FusionMode.WALKING -> advanceByStep(at_elapsed_ms)
            FusionMode.DRIVING -> Unit
        }
    }

    /**
     * The estimate for [now_elapsed_ms]: extrapolated from the anchor,
     * with `fix_fresh` judged from the receipt clock — the presence
     * layer's Active/Lost falls directly out of this.
     */
    fun tick(
        now_elapsed_ms: Long,
        fix_confirmed: Boolean = false,
    ): FusedPosition {
        if (fix_confirmed) tick_emissions_since_fix = 0 else tick_emissions_since_fix++
        val point = extrapolateTo(now_elapsed_ms)
        val fix_fresh =
            last_fix_received_elapsed_ms >= 0L &&
                now_elapsed_ms - last_fix_received_elapsed_ms < SIGNAL_LOST_MS
        return FusedPosition(
            point = point,
            heading_deg = if (mode == FusionMode.STATIONARY) null else heading_est_deg,
            // dt past the ANCHOR, not the absolute clock — the absolute
            // elapsed time is hours since boot, and speedAt would decay
            // everything to zero. (Tests anchored at t=0 masked this.)
            speed_mps = speedAt(now_elapsed_ms - anchor_elapsed_ms),
            mode = mode,
            emissions_since_fix = if (fix_confirmed) 0 else tick_emissions_since_fix,
            accuracy_m = last_fix_accuracy_m,
            at_elapsed_ms = now_elapsed_ms,
            fix_confirmed = fix_confirmed,
            last_fix_at_ms = last_fix_wall_ms,
            fix_fresh = fix_fresh,
            heading_source = heading_source,
        )
    }

    /** Ticks since the last fix — the fused flow maintains this counter. */
    private var tick_emissions_since_fix = 0

    // ----- private engine pieces ----------------------------------------

    /** First fix: anchor at the fix, no blending, mode from the chip speed. */
    private fun initFromFirstFix(fix: LocationTracker.Fix, now_elapsed_ms: Long) {
        val fix_elapsed = if (fix.at_elapsed_ms != 0L) fix.at_elapsed_ms else now_elapsed_ms
        anchor = fix.point
        anchor_elapsed_ms = fix_elapsed
        anchor_valid = true
        speed_base_mps = fixSpeed(fix)
        last_fix_received_elapsed_ms = now_elapsed_ms
        last_fix_wall_ms = fix.at_ms
        last_fix_accuracy_m = fix.accuracy_m?.toDouble() ?: DEFAULT_ACCURACY_M
        val speed = speed_base_mps
        if (speed >= GPS_BEARING_TRUST_MPS && fix.bearing != null) {
            heading_est_deg = normalizeBearing(fix.bearing)
            heading_source = HeadingSource.GPS
        }
        mode = bandOf(speed)
        prev_fix_point = fix.point
        prev_fix_elapsed_ms = fix_elapsed
    }

    /**
     * The estimate at [t]: anchor plus the dead-reckoned displacement.
     * The integral is clamped to [DR_MAX_MS] past the anchor — beyond
     * that the estimate freezes instead of driving the puck into a wall
     * on a fiction.
     */
    private fun extrapolateTo(t: Long): GeoPoint {
        if (!anchor_valid) return anchor
        val dt = (t - anchor_elapsed_ms).coerceAtLeast(0L)
        val distance = when (mode) {
            FusionMode.DRIVING -> speedIntegral(dt)
            FusionMode.WALKING -> 0.0
            FusionMode.STATIONARY -> 0.0
        }
        return displaced(anchor, distance)
    }

    /**
     * ∫speed dt from the anchor to [dt] later, with the hold-then-decay
     * profile: GPS speed is trusted as-is for [SPEED_HOLD_MS] (best
     * tunnel behavior — keep moving at the last known pace), then decays
     * exponentially so a long outage converges to a standstill.
     */
    private fun speedIntegral(dt: Long): Double {
        val capped = dt.coerceAtMost(DR_MAX_MS)
        val hold = SPEED_HOLD_MS
        if (capped <= hold) return speed_base_mps * capped / 1_000.0
        var meters = speed_base_mps * hold / 1_000.0
        val decay_ms = (capped - hold).toDouble()
        // ∫ v·2^(−t/HL) dt from 0 to T = v·(HL_s/ln2)·(1 − 2^(−T/HL)), with HL_s the
        // half-life in seconds — t is in milliseconds throughout this file.
        meters += speed_base_mps * (SPEED_DECAY_HALF_LIFE_MS / 1_000.0) / LN2 *
            (1 - 2.0.pow(-decay_ms / SPEED_DECAY_HALF_LIFE_MS))
        return meters
    }

    /**
     * Speed output at [dt] past the anchor — the same hold-then-decay
     * profile the displacement integrates, without the integral.
     */
    private fun speedAt(dt: Long): Double {
        val capped = dt.coerceAtMost(DR_MAX_MS)
        if (capped <= SPEED_HOLD_MS) return speed_base_mps
        val decay_ms = (capped - SPEED_HOLD_MS).toDouble()
        return speed_base_mps * 2.0.pow(-decay_ms / SPEED_DECAY_HALF_LIFE_MS)
    }

    /**
     * Fold a fresh fix into the anchor. Within the acceptance radius the
     * blend fraction scales with the residual: a small residual (drift)
     * moves the estimate a small fraction of the way — continuous
     * motion, no visible jump every second — and a residual near the
     * budget converges faster. Beyond the budget the GPS has genuinely
     * corrected us and the estimate hard-snaps. Poor fixes (null or huge
     * accuracy) blend, but softly — they never snap and never fully own
     * the position.
     */
    private fun blendOrSnap(
        e: GeoPoint,
        fix_point: GeoPoint,
        residual: Double,
        accuracy: Double,
        fix_elapsed: Long,
    ) {
        if (mode == FusionMode.STATIONARY) {
            // Stationary damping: a slow EMA of fixes, not per-fix blends —
            // the standing-still GPS cloud must not walk the dot around.
            val ema = stationary_ema ?: e
            stationary_ema = GeoPoint(
                lon = ema.lon + STATIONARY_EMA_ALPHA * (fix_point.lon - ema.lon),
                lat = ema.lat + STATIONARY_EMA_ALPHA * (fix_point.lat - ema.lat),
            )
            anchor = stationary_ema!!
            anchor_elapsed_ms = fix_elapsed
            return
        }

        val accept_m = maxOf(ACC_BLEND_FACTOR * accuracy, MIN_ACCEPT_M)
        val poor_scale = if (accuracy > ACCURACY_POOR_M) ACCURACY_POOR_M / accuracy else 1.0
        if (residual <= accept_m) {
            val f = (residual / accept_m).coerceIn(BLEND_MIN_F, 1.0) * poor_scale
            anchor = GeoPoint(
                lon = e.lon + f * (fix_point.lon - e.lon),
                lat = e.lat + f * (fix_point.lat - e.lat),
            )
        } else if (accuracy <= ACCURACY_POOR_M) {
            // Hard snap: trust the fix outright (fresh fix, decent
            // accuracy, extrapolation went wrong — tunnel exit, outage).
            anchor = fix_point
        } else {
            val f = (residual / accept_m).coerceAtMost(1.0) * poor_scale
            anchor = GeoPoint(
                lon = e.lon + f * (fix_point.lon - e.lon),
                lat = e.lat + f * (fix_point.lat - e.lat),
            )
        }
        anchor_elapsed_ms = fix_elapsed
    }

    /**
     * Mode selection per fix. Stationary needs [STATIONARY_FREEZE_MS] of
     * consecutive still fixes; breaking out of it is immediate (a fix at
     * walking speed must move the dot at once). Between Walking and
     * Driving there is a 2-fix hysteresis so a jogger hovering around
     * the band edge doesn't flip the displacement model every second.
     */
    private fun updateMode(speed: Double, fix_elapsed: Long) {
        if (speed < STILL_MPS) {
            still_streak++
            if (still_streak == 1) still_streak_start_ms = fix_elapsed
            if (mode != FusionMode.STATIONARY &&
                fix_elapsed - still_streak_start_ms >= STATIONARY_FREEZE_MS
            ) {
                mode = FusionMode.STATIONARY
                stationary_ema = anchor
                candidate_mode = null
                candidate_streak = 0
            }
            return
        }

        still_streak = 0
        if (mode == FusionMode.STATIONARY) {
            // Breakout: immediate, no hysteresis on leaving stillness.
            mode = bandOf(speed)
            stationary_ema = null
            return
        }

        val band = bandOf(speed)
        if (band == mode) {
            candidate_mode = null
            candidate_streak = 0
        } else {
            if (candidate_mode == band) candidate_streak++ else {
                candidate_mode = band
                candidate_streak = 1
            }
            if (candidate_streak >= 2) {
                mode = band
                candidate_mode = null
                candidate_streak = 0
            }
        }
    }

    /** The displacement model a speed belongs to. */
    private fun bandOf(speed: Double): FusionMode = when {
        speed < STILL_MPS -> FusionMode.STATIONARY
        speed < WALK_MAX_MPS && walking_enabled -> FusionMode.WALKING
        else -> FusionMode.DRIVING
    }

    /**
     * Heading update from a fix. GPS course-over-ground is the truth at
     * speed; below the trust threshold the chip bearing is noise (a
     * standing GPS wanders ±180°) and the rotation vector keeps the
     * estimate — a chip bearing that disagrees wildly with it at low
     * speed must not rotate it.
     */
    private fun applyHeading(bearing: Double?, speed: Double) {
        if (bearing == null) return
        if (speed >= GPS_BEARING_TRUST_MPS) {
            heading_est_deg = normalizeBearing(bearing)
            heading_source = HeadingSource.GPS
        } else if (heading_est_deg != null && speed > 0.0) {
            val delta = signedBearingDelta(heading_est_deg!!, bearing)
            if (abs(delta) > LOW_SPEED_BEARING_REJECT_DEG) return
            // Plausible agreement at low speed: nudge toward the chip.
            heading_est_deg = normalizeBearing(heading_est_deg!! + 0.3 * delta)
            heading_source = HeadingSource.GPS
        }
    }

    /**
     * One walking step: the anchor advances [step_length_m] along the
     * heading. Step length is adapted on each fresh fix from the GPS
     * distance actually covered per step (EMA, clamped), so it tracks
     * the user's pace instead of guessing it.
     */
    private fun advanceByStep(at_elapsed_ms: Long) {
        anchor = displaced(anchor, step_length_m)
        anchor_elapsed_ms = at_elapsed_ms
        step_count++
    }

    /** Speed for mode banding: the chip's, else displacement over time. */
    private fun fixSpeed(fix: LocationTracker.Fix): Double {
        fix.speed_mps?.let { return it.toDouble() }
        val prev = prev_fix_point ?: return 0.0
        val fix_elapsed = if (fix.at_elapsed_ms != 0L) fix.at_elapsed_ms else return 0.0
        val dt_s = (fix_elapsed - prev_fix_elapsed_ms) / 1_000.0
        if (dt_s <= 0.0) return 0.0
        return metersBetween(prev, fix.point) / dt_s
    }

    /**
     * The point [meters] along [heading_deg_true] from [p] on a flat
     * local plane — the same flat-plane approximation metersBetween uses.
     */
    private fun displaced(from: GeoPoint, meters: Double): GeoPoint {
        if (meters == 0.0) return from
        val heading = heading_est_deg ?: return from
        val east = meters * sin(Math.toRadians(heading))
        val north = meters * cos(Math.toRadians(heading))
        return GeoPoint(
            lon = from.lon + east / (METERS_PER_DEGREE * cos(Math.toRadians(from.lat))),
            lat = from.lat + north / METERS_PER_DEGREE,
        )
    }

    private fun normalizeBearing(deg: Double): Double {
        val r = deg % 360.0
        return if (r < 0.0) r + 360.0 else r
    }

    /** Signed b−a difference wrapped to (−180, 180]. */
    private fun signedBearingDelta(a: Double, b: Double): Double {
        var r = (b - a) % 360.0
        if (r <= -180.0) r += 360.0
        if (r > 180.0) r -= 360.0
        return r
    }

    /**
     * Adapts [step_length_m] toward the GPS-observed meters per step
     * over the interval the last fix closed — an EMA so it follows a
     * user whose pace changes, clamped so a nonsense ratio (phone left
     * on a table while steps still fire) cannot take over.
     */
    private fun learnStepLength(fix_point: GeoPoint, speed: Double) {
        if (mode != FusionMode.WALKING) return
        val prev = prev_fix_point ?: return
        if (step_count <= 0) return
        if (speed < STILL_MPS || speed >= WALK_MAX_MPS) return
        val learned = metersBetween(prev, fix_point) / step_count
        if (learned < STEP_LENGTH_MIN_M || learned > STEP_LENGTH_MAX_M) return
        step_length_m += STEP_EMA_ALPHA * (learned - step_length_m)
    }

    companion object {
        /** Below this speed (m/s) the device counts as not moving. */
        const val STILL_MPS = 0.3

        /** Above this (≈8 km/h) the walking model gives way to driving. */
        const val WALK_MAX_MPS = 2.2

        /** GPS course-over-ground is trusted at or above this speed. */
        const val GPS_BEARING_TRUST_MPS = 1.5

        /** GPS speed is held constant this long past the last fix. */
        const val SPEED_HOLD_MS = 3_000L

        /** After the hold, speed decays with this half-life. */
        const val SPEED_DECAY_HALF_LIFE_MS = 2_000L

        /** Extrapolation stops entirely this long past the last fix. */
        const val DR_MAX_MS = 20_000L

        /** Default step length until the EMA learns the user's pace. */
        const val STEP_LENGTH_DEFAULT_M = 0.72

        /** EMA weight of the step-length learning. */
        const val STEP_EMA_ALPHA = 0.2

        /** Learned step length is clamped to a plausible human range. */
        const val STEP_LENGTH_MIN_M = 0.4
        const val STEP_LENGTH_MAX_M = 1.2

        /** Fixes blend inside max(2×accuracy, this floor) of the estimate. */
        const val ACC_BLEND_FACTOR = 2.0
        const val MIN_ACCEPT_M = 10.0

        /** The soft blend moves the estimate at least this fraction. */
        const val BLEND_MIN_F = 0.15

        /** Fix accuracy worse than this never hard-snaps and blends softly. */
        const val ACCURACY_POOR_M = 50.0

        /** Accuracy assumed when the chip reports none. */
        const val DEFAULT_ACCURACY_M = 30.0

        /** First-strike outlier budget: 4× accuracy + 30 m of slack. */
        const val OUTLIER_FACTOR = 4.0
        const val OUTLIER_BASE_M = 30.0

        /** Still fixes over this span switch the mode to Stationary. */
        const val STATIONARY_FREEZE_MS = 3_000L

        /** Damping weight of the stationary fix EMA. */
        const val STATIONARY_EMA_ALPHA = 0.3

        /** Low-speed chip bearings that disagree this much are rejected. */
        const val LOW_SPEED_BEARING_REJECT_DEG = 90.0

        /** Mirror of LocationPresenceTracker.SIGNAL_LOST_MS. */
        const val SIGNAL_LOST_MS = 10_000L

        private const val LN2 = 0.6931471805599453
        private const val METERS_PER_DEGREE = 111_320.0
    }

    /** Test-visible step length — asserted by the fusion tests. */
    internal fun stepLengthForTest(): Double = step_length_m
}