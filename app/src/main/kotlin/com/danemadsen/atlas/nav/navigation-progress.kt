package com.danemadsen.atlas.nav

import com.danemadsen.atlas.routing.GeoPoint
import com.danemadsen.atlas.routing.RouteResult
import com.danemadsen.atlas.routing.TurnCommand
import com.danemadsen.atlas.routing.TurnPoint
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Completed fraction of [route] (0..1), or null before the first fix —
 * the notification progress bar's driver. [Snapshot.remainingMeters] is
 * measured along the route and [RouteResult.distanceMeters] is the same
 * polyline total the engine uses, so the fraction is exact, snaps to 1.0
 * at arrival, and a re-route's fresh engine resets it honestly to the
 * new route's 0.
 */
internal fun routeProgressFraction(route: RouteResult, snapshot: NavigationProgress.Snapshot?): Double? =
    snapshot?.let {
        (1.0 - it.remainingMeters / max(route.distanceMeters.toDouble(), 1.0)).coerceIn(0.0, 1.0)
    }

/**
 * The pure, fix-driven half of navigation mode: given a route and a
 * stream of GPS fixes, every [update] returns the full progress snapshot
 * plus the events the caller must act on (TTS announcements, re-route,
 * arrival). No Android types — the whole state machine is unit-tested
 * against synthetic fixes (the on-device acceptance test drives it with
 * `adb emu geo fix`).
 *
 * A re-route after going off-course is a fresh instance over the new
 * [RouteResult]; this class owns no cross-route state.
 *
 * All distances are meters on a flat local-plane approximation: urban
 * navigation legs are far too short for geodesic error to matter
 * against 15–40 m decision thresholds.
 */
class NavigationProgress(route: RouteResult) {

    /** The caller-facing progress for one fix (or the initial state). */
    data class Snapshot(
        /** The snapped position on the route; null before the first fix. */
        val snapped: GeoPoint?,
        /** The maneuver the banner shows (the next announced turn). */
        val nextTurn: TurnPoint?,
        val distanceToNextTurnMeters: Double,
        val remainingMeters: Double,
        /** ETA at the route's average speed, scaled by remaining distance. */
        val remainingSeconds: Int,
        /** The GPS chip's travel bearing, when it reports one. */
        val bearing: Double?,
        val arrived: Boolean,
        /** True while the user is straying (before the re-route fires). */
        val offRoute: Boolean,
    )

    /** What [update] asks the caller to do beyond updating the snapshot. */
    data class Events(
        /** Sentences to speak, in order (empty when the fix triggers none). */
        val announcements: List<String> = emptyList(),
        val recalculate: Boolean = false,
        val arrived: Boolean = false,
        /**
         * Maneuvers consumed by this fix (the fix crossed their 15 m
         * pass boundary) — the turn-now cue fires once per entry. The
         * arrival hint is excluded: "You have arrived." is its cue.
         */
        val turnsConsumed: List<TurnPoint> = emptyList(),
    )

    data class Step(val snapshot: Snapshot, val events: Events)

    private val points: List<GeoPoint> = route.points
    private val turns: List<TurnPoint> = route.turns
    private val total_meters: Double = max(route.distanceMeters.toDouble(), 1.0)
    private val total_seconds: Int = route.durationSeconds

    /**
     * Cumulative route distance at every point index (index 0 is 0).
     * Precomputed once: a metro route holds thousands of points and every
     * fix derives positions along this ladder.
     */
    private val cumulative_meters: DoubleArray = run {
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) {
            out[i] = out[i - 1] + metersBetween(points[i - 1], points[i])
        }
        out
    }

    /** The route's along-distance for each turn, indexed like [turns]. */
    private val turn_along_meters: DoubleArray =
        DoubleArray(turns.size) { i ->
            if (turns[i].pointIndex in cumulative_meters.indices) {
                cumulative_meters[turns[i].pointIndex]
            } else {
                0.0
            }
        }

    private val destination = route.destination

    // ---- per-session state, mutated by update() ----

    /** Along-route distance of the latest snapped fix. */
    private var along_meters = 0.0

    private var has_fix = false

    /** The latest fix's bearing; the camera follow reads it. */
    private var last_bearing: Double? = null

    /** Which [turns] entry the banner shows. */
    private var next_turn_index = 0

    /** The thresholds already spoken for the CURRENT next turn. */
    private val spoken_thresholds = mutableSetOf<Int>()

    /** Whether the current turn's behind-us thresholds are seeded yet. */
    private var turn_seeded = false

    /** Consecutive fixes farther than [OFF_ROUTE_LIMIT_METERS] from the route. */
    private var off_route_streak = 0

    private var arrived = false

    /** The banner state before any fix: nothing snapped, first turn shown. */
    fun initial(): Snapshot = snapshot()

    /**
     * Consumes one GPS fix. The fix is snapped to the nearest point on
     * the route polyline; the snapshot and events follow from the
     * snapped position (a fix 300 m off the route with 40 m of GPS
     * jitter still tracks progress correctly while the streak decides
     * whether the user actually left the route).
     */
    fun update(fix: GeoPoint, bearing: Double? = null): Step {
        val (snap_along, snap_point, snap_distance) = projectOntoRoute(fix)
        along_meters = snap_along
        last_bearing = bearing
        has_fix = true
        off_route_streak = if (snap_distance > OFF_ROUTE_LIMIT_METERS) {
            off_route_streak + 1
        } else {
            0
        }

        val announcements = ArrayList<String>(2)
        val before_index = next_turn_index
        advanceTurns()
        val consumed = turns.subList(before_index, next_turn_index)
            .filter { it.command != TurnCommand.ARRIVE }
        if (consumed.size >= 2) {
            // Two maneuvers inside one 15 m pass window: the second was
            // never the banner turn, so no threshold can ever announce it.
            // Speak it now, unprefixed — the driver is mid-cluster and the
            // sentence needs no distance.
            announcements.add(turnInstruction(consumed.last().command, consumed.last().streetName))
        }
        if (!arrived) {
            announcements.addAll(thresholdAnnouncements(turn_distance(next_turn_index)))
        }

        // Arrival is a matter of ROUTE progress, not straight-line distance
        // to the pin: the polyline ends at the router's crosspoint, which
        // can sit hundreds of meters from the raw destination (or, on a
        // loop route, right next to the origin — where a raw-distance gate
        // would "arrive" before the user has moved at all).
        val remaining = (total_along() - along_meters).coerceAtLeast(0.0)
        val transitioned = remaining <= ARRIVAL_METERS && !arrived
        if (transitioned) {
            arrived = true
            announcements.add("You have arrived.")
        }

        return Step(
            snapshot = snapshot(),
            events = Events(
                announcements = announcements,
                recalculate = !arrived && off_route_streak >= OFF_ROUTE_LIMIT,
                arrived = transitioned,
                turnsConsumed = consumed,
            ),
        )
    }

    private fun snapshot(): Snapshot {
        val remaining = (total_along() - along_meters).coerceAtLeast(0.0)
        val next_turn = turns.getOrNull(next_turn_index)
        return Snapshot(
            snapped = if (has_fix) snapPointAt(along_meters) else null,
            nextTurn = next_turn,
            distanceToNextTurnMeters = turn_distance(next_turn_index),
            remainingMeters = remaining,
            remainingSeconds = remainingSecondsFor(remaining),
            bearing = last_bearing,
            arrived = arrived,
            offRoute = off_route_streak > 0,
        )
    }

    private fun total_along(): Double = cumulative_meters.lastOrNull() ?: 0.0

    /** ETA at the route's average speed, scaled by the remaining fraction. */
    private fun remainingSecondsFor(remaining: Double): Int {
        val fraction = (remaining / total_meters).coerceIn(0.0, 1.0)
        return (total_seconds * fraction).toInt()
    }

    /**
     * Advances the banner turn once the fix is within [TURN_PASS_METERS]
     * of it — closer than that the maneuver is "here", and the banner must
     * already show what comes next.
     */
    private fun advanceTurns() {
        while (next_turn_index < turns.size &&
            turn_distance(next_turn_index) <= TURN_PASS_METERS
        ) {
            next_turn_index++
            spoken_thresholds.clear()
            turn_seeded = false
        }
    }

    private fun turn_distance(index: Int): Double {
        val along = turn_along_meters.getOrNull(index) ?: return 0.0
        return (along - along_meters).coerceAtLeast(0.0)
    }

    /**
     * The TTS cadence: each of [TTS_THRESHOLDS_METERS] is spoken at most
     * once per turn, measured along the route — not straight-line, or a
     * winding approach announces too late.
     *
     * A threshold only ever announces when CROSSED from above. The first
     * sighting of a turn (session start, a turn advance, a re-route) seeds
     * every threshold already behind the fix as spoken: picking up the
     * session 334 m from a turn must not speak "In 500 meters" — it stays
     * quiet until the 200 m crossing, which is the first thing it can say
     * truthfully.
     */
    private fun thresholdAnnouncements(distance_to_turn: Double): List<String> {
        val turn = turns.getOrNull(next_turn_index) ?: return emptyList()
        if (!turn_seeded) {
            for (threshold in TTS_THRESHOLDS_METERS) {
                if (threshold > distance_to_turn) spoken_thresholds.add(threshold)
            }
            turn_seeded = true
        }
        val out = ArrayList<String>(1)
        for (threshold in TTS_THRESHOLDS_METERS) {
            if (distance_to_turn <= threshold && threshold !in spoken_thresholds) {
                spoken_thresholds.add(threshold)
                out.add(announcementFor(turn, threshold))
            }
        }
        return out
    }

    /**
     * The nearest point of the polyline to [fix], as
     * (along-route meters, snapped point, meters off the route).
     */
    private fun projectOntoRoute(fix: GeoPoint): Triple<Double, GeoPoint, Double> {
        if (points.size < 2) {
            val only = points.firstOrNull() ?: fix
            return Triple(0.0, only, metersBetween(fix, only))
        }
        var best_along = 0.0
        var best_point = points[0]
        var best_distance = Double.MAX_VALUE
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val segment_length = metersBetween(a, b)
            // The projection parameter is a RATIO: the dot product and its
            // denominator must share units. Both in degrees here (the
            // flat-plane cos-scale of metersBetween cancels in the ratio);
            // mixing a degrees² dot product with a meters² length — the
            // obvious way to write this — collapses t to ~0 and snaps
            // every fix to a route vertex.
            val ab_lon = b.lon - a.lon
            val ab_lat = b.lat - a.lat
            val t = if (segment_length > 0.0) {
                val raw = (
                    (fix.lon - a.lon) * ab_lon +
                        (fix.lat - a.lat) * ab_lat
                    ) / (ab_lon * ab_lon + ab_lat * ab_lat)
                raw.coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val snapped = GeoPoint(
                lon = a.lon + (b.lon - a.lon) * t,
                lat = a.lat + (b.lat - a.lat) * t,
            )
            val distance = metersBetween(fix, snapped)
            if (distance < best_distance) {
                best_distance = distance
                best_point = snapped
                best_along = cumulative_meters[i] + segment_length * t
            }
        }
        return Triple(best_along, best_point, best_distance)
    }

    private fun snapPointAt(along: Double): GeoPoint {
        if (points.isEmpty()) return destination
        if (points.size == 1) return points[0]
        var i = 1
        while (i < points.size && cumulative_meters[i] < along) i++
        val segment = (cumulative_meters[i] - cumulative_meters[i - 1]).coerceAtLeast(1e-9)
        val t = ((along - cumulative_meters[i - 1]) / segment).coerceIn(0.0, 1.0)
        val a = points[i - 1]
        val b = points[i]
        return GeoPoint(
            lon = a.lon + (b.lon - a.lon) * t,
            lat = a.lat + (b.lat - a.lat) * t,
        )
    }

    companion object {
        /** TTS fires at each of these approach distances, once per turn. */
        val TTS_THRESHOLDS_METERS = intArrayOf(500, 200, 50)

        /** Fewer than this from the turn: the banner shows the next one. */
        const val TURN_PASS_METERS = 15.0

        /** Off the route by more than this for [OFF_ROUTE_LIMIT] fixes → re-route. */
        const val OFF_ROUTE_LIMIT_METERS = 40.0
        const val OFF_ROUTE_LIMIT = 3

        const val ARRIVAL_METERS = 15.0
    }
}

/** Flat-plane meters between two positions — see the class doc. */
internal fun metersBetween(a: GeoPoint, b: GeoPoint): Double {
    val d_lon = (b.lon - a.lon) * METERS_PER_DEGREE * cosApprox(a.lat)
    val d_lat = (b.lat - a.lat) * METERS_PER_DEGREE
    return sqrt(d_lon * d_lon + d_lat * d_lat)
}

private const val METERS_PER_DEGREE = 111_320.0

private fun cosApprox(lat: Double): Double = kotlin.math.cos(Math.toRadians(lat))

/**
 * The announcement sentence for a turn at a threshold distance — the
 * banner shows the same turn without the distance prefix.
 */
fun announcementFor(turn: TurnPoint, atMeters: Int): String {
    val instruction = turnInstruction(turn.command, turn.streetName)
    return if (atMeters > 0) "In $atMeters meters, $instruction" else instruction
}