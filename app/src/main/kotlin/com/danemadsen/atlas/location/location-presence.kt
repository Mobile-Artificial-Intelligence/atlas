package com.danemadsen.atlas.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.danemadsen.atlas.routing.GeoPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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
    data class Active(
        val point: GeoPoint,
        val at_ms: Long,
        val heading_deg: Double? = null,
    ) : LocationPresence

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
     * The catch is load-bearing: the fused stream closes with an
     * exception when the GPS provider is missing outright (a GPS-less
     * device) — an uncaught failure would take down whichever coroutine
     * collects this. Swallowing it just ends the stream early: no more
     * presence, the puck stays at whatever it last showed, and no crash.
     *
     * distinctUntilChanged keeps the map-side collector from redrawing
     * the puck between real changes — ticks differ only in position
     * fractions, and Lost repeats its point forever.
     */
    fun observe(context: Context): Flow<LocationPresence> =
        FusedPositionTracker.fused(context)
            .map(::toPresence)
            .distinctUntilChanged()
            .catch { }
}

/**
 * The [LocationPresence] mapping over the fused position: a fresh fix
 * stream means Active at the fused estimate (the puck leads and glides
 * with dead reckoning), a stale one means Lost at the same point — the
 * puck goes grey but does not jump back to the last raw fix. The wall
 * clock of the LAST FIX rides along in Active.at_ms so the resume
 * re-judge (map-screen) can re-judge staleness after a lifecycle pause,
 * when this flow's loss timer is dead.
 */
internal fun toPresence(pos: PositionFusion.FusedPosition): LocationPresence =
    if (pos.fix_fresh) {
        LocationPresence.Active(pos.point, pos.last_fix_at_ms, pos.heading_deg)
    } else {
        LocationPresence.Lost(pos.point)
    }