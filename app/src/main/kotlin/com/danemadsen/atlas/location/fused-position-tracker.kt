package com.danemadsen.atlas.location

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import android.os.Build
import android.os.SystemClock
import android.hardware.GeomagneticField
import com.danemadsen.atlas.routing.GeoPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * The single position source for the whole app: GPS fixes, sensor
 * heading, and step events merged through one [PositionFusion] engine
 * and ticked at 10 Hz. Cold flow — collectors own their sensor
 * registrations, so backgrounding browse mode tears them down exactly
 * like the GPS listener, while the nav service's collector holds them
 * for the whole session (screen off included).
 */
object FusedPositionTracker {

    /** Puck/camera tick rate — smooth enough for a moving dot, cheap. */
    const val TICK_MS = 100L

    /**
     * The fused position stream. Nothing is emitted before the first
     * fix — there is no estimate without an anchor. Upstreams can emit
     * concurrently, but merge's downstream lambda runs one element at a
     * time, so the engine's mutations stay sequential.
     */
    fun fused(context: Context): Flow<PositionFusion.FusedPosition> = flow {
        val now = { SystemClock.elapsedRealtime() }
        val fusion = PositionFusion(walking_enabled = walkingEnabled(context))

        // Declination at the last fix: the rotation vector reports
        // magnetic azimuth; GeomagneticField is the offline declination
        // source (a pure framework model, no network). Recomputed per
        // fix — it drifts slowly, and fixes land continuously.
        var last_fix_point: GeoPoint? = null
        var last_fix_at_wall_ms = 0L
        val declination = {
            val point = last_fix_point
            if (point == null) 0.0 else GeomagneticField(
                point.lat.toFloat(),
                point.lon.toFloat(),
                0f, // altitude: negligible for declination at street level
                last_fix_at_wall_ms,
            ).declination.toDouble()
        }

        merge(
            LocationTracker.fixes(context).map { FusionEvent.Fix(it) },
            SensorHub.heading(context) { declination() }.map { FusionEvent.Heading(it) },
            SensorHub.steps(context).map { FusionEvent.Step(it) },
            flow {
                while (true) {
                    emit(FusionEvent.Tick)
                    delay(TICK_MS)
                }
            },
        ).collect { event ->
            when (event) {
                is FusionEvent.Fix -> {
                    fusion.onFix(event.fix, now())
                    last_fix_point = event.fix.point
                    last_fix_at_wall_ms = event.fix.at_ms
                    // Fix-confirmed emission: nav paces on these; ticks
                    // only move the camera/puck. The same estimate feeds
                    // the debug line — log what the app actually holds.
                    val estimate = fusion.tick(now(), fix_confirmed = true)
                    logFusion(context, event.fix, estimate)
                    emit(estimate)
                }
                is FusionEvent.Heading -> fusion.onHeading(event.sample)
                is FusionEvent.Step -> fusion.onStep(event.at)
                is FusionEvent.Tick -> emit(fusion.tick(now()))
            }
        }
    }

    /** Whether the full walking DR pipeline is available right now. */
    private fun walkingEnabled(context: Context): Boolean =
        SensorHub.hasStepSensor(context) && stepPermissionHeld(context)

    /** ACTIVITY_RECOGNITION is only enforced from API 29. */
    private fun stepPermissionHeld(context: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    /** One debug log line per fix — the on-device tuning surface. */
    private fun logFusion(
        context: Context,
        fix: LocationTracker.Fix,
        estimate: PositionFusion.FusedPosition,
    ) {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        Log.i(
            "PositionFusion",
            "fix acc=${fix.accuracy_m ?: "?"} speed=${fix.speed_mps ?: "?"} " +
                "bearing=${fix.bearing ?: "?"} | est: $estimate",
        )
    }
}

/** Internal merge events — the single consumer mutates the engine. */
private sealed interface FusionEvent {
    data class Fix(val fix: LocationTracker.Fix) : FusionEvent
    data class Heading(val sample: PositionFusion.HeadingSample) : FusionEvent
    data class Step(val at: Long) : FusionEvent
    data object Tick : FusionEvent
}