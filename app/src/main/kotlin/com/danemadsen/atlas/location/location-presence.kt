package com.danemadsen.atlas.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.danemadsen.atlas.routing.GeoPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest

/**
 * The user's location as the map sees it: every fix becomes
 * [LocationPresence.Active], and a stream that goes quiet past the loss
 * threshold degrades to [LocationPresence.Lost] at the last fix — the
 * puck's cue to go grey and stop pulsing. The threshold matches
 * NavigationService's GPS watchdog (its GPS_DISCONNECTED cue), so the
 * eyes and the ears always agree about the signal.
 *
 * Before the first fix there is no presence at all: no dot is drawn for a
 * location the app never had. Permission gating stays with the caller —
 * this stream is only collected while the FINE location permission holds
 * ([hasFineLocationPermission]; the GPS provider refuses COARSE-only
 * listeners, so a COARSE grant must not reach the collector at all).
 */
sealed interface LocationPresence {

    /** A fresh fix landed; the puck is blue and pulsing. */
    data class Active(val point: GeoPoint, val at_ms: Long) : LocationPresence

    /** The stream went quiet; the puck goes grey at the last fix. */
    data class Lost(val point: GeoPoint) : LocationPresence
}

object LocationPresenceTracker {

    /** No fix for this long → Lost (mirrors NavigationService's watchdog). */
    const val SIGNAL_LOST_MS = 10_000L

    fun hasFineLocationPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * The catch is load-bearing: [LocationTracker.fixes] closes with an
     * exception when the GPS provider is missing outright (a GPS-less
     * device) — an uncaught failure would take down whichever coroutine
     * collects this. Swallowing it just ends the stream early: no more
     * presence, the puck stays at whatever it last showed, and no crash.
     */
    fun observe(context: Context): Flow<LocationPresence> =
        LocationTracker.fixes(context)
            .withLossThreshold(SIGNAL_LOST_MS)
            .catch { }
}

/**
 * The [LocationPresence] state machine over any fix stream, top-level for
 * the unit tests: each fix re-arms a loss timeout at its own position, so
 * the timeout that fires is always the newest one's. The last fix's
 * timeout outlives upstream completion — a stream that ends emits Lost
 * exactly once more.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<LocationTracker.Fix>.withLossThreshold(
    lostMs: Long,
): Flow<LocationPresence> =
    transformLatest { fix ->
        emit(LocationPresence.Active(fix.point, fix.at_ms))
        emitAll(
            flow {
                delay(lostMs)
                emit(LocationPresence.Lost(fix.point))
            }
        )
    }