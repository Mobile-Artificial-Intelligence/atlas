package com.danemadsen.atlas.location

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.danemadsen.atlas.routing.GeoPoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The GPS fix stream for navigation, straight from the platform's
 * [LocationManager] — no Play Services, no fused provider (both would
 * need network-side infrastructure this app deliberately has none of).
 * GPS-only: on a fully-offline device the network provider has nothing
 * to offer anyway.
 *
 * Every fix carries the bearing the camera follow uses; a fix without
 * one (a stationary start) falls back to the route's initial direction
 * at the caller.
 */
object LocationTracker {

    /** 1 Hz fixes; navigation decisions run per-fix. */
    private const val INTERVAL_MS = 1_000L
    private const val MIN_DISTANCE_METERS = 0f

    /**
     * A cold GPS start takes its time; the tracker stays open and emits
     * whenever a fix lands. Caller-side permission is assumed granted —
     * navigation starts from a route that already required a location
     * fix.
     */
    fun fixes(context: Context): Flow<Fix> = callbackFlow {
        val manager = context.getSystemService(LocationManager::class.java)
        val listener = LocationListener { location ->
            trySend(
                Fix(
                    point = GeoPoint(location.longitude, location.latitude),
                    bearing = if (location.hasBearing()) location.bearing.toDouble() else null,
                ),
            )
        }
        runCatching {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                INTERVAL_MS,
                MIN_DISTANCE_METERS,
                listener,
                Looper.getMainLooper(),
            )
        }.onFailure { close(it) }
        awaitClose { runCatching { manager.removeUpdates(listener) } }
    }

    /**
     * One GPS fix: position plus the travel bearing when the chip reports
     * one, and the wall clock it landed — the puck's staleness check reads
     * it after a lifecycle pause (the loss timer does not survive
     * backgrounding, so resume re-judges the last fix by its age).
     */
    data class Fix(
        val point: GeoPoint,
        val bearing: Double?,
        val at_ms: Long = System.currentTimeMillis(),
    )
}