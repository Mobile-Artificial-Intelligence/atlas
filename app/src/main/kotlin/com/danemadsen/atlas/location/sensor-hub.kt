package com.danemadsen.atlas.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventCallback
import android.hardware.SensorManager
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Cold flows over the platform sensor APIs. The app is deliberately
 * offline — no Play Services, so the fused provider is off the table
 * and the sensors fill the 1 Hz gap between GPS fixes.
 *
 * Registration rides collection lifecycle exactly like the GPS listener
 * in [LocationTracker]: registration when collected, `awaitClose`
 * unregisters when collection stops (browse mode pauses on background
 * via collectAsStateWithLifecycle; the nav service holds registration
 * for the whole session).
 */
object SensorHub {

    /**
     * Device heading as a cold flow of TRUE-north azimuths: the
     * geomagnetic rotation vector gives magnetic azimuth in one sample;
     * the declination provider (the fusion's last fix, applied by
     * [FusedPositionTracker]) converts it to true heading. Empty when
     * the sensor is absent — the fusion falls back to GPS-only DR.
     */
    fun heading(
        context: Context,
        declination_deg: () -> Double,
    ): Flow<PositionFusion.HeadingSample> = callbackFlow {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        if (sensor == null) {
            close() // no sensor: empty flow, the fusion falls back
            return@callbackFlow
        }
        val listener = object : SensorEventCallback() {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) return
                val rotation = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotation, orientation)
                // getOrientation returns magnetic azimuth (rad, CCW from
                // magnetic north toward the device's -Z axis convention);
                // + declination makes it a TRUE bearing the engine can use.
                val magnetic = Math.toDegrees(orientation[0].toDouble())
                val true_deg = magnetic + declination_deg()
                trySend(
                    PositionFusion.HeadingSample(
                        azimuth_true_deg = true_deg,
                        accuracy_deg = event.accuracy.toFloat(),
                        at_elapsed_ms = event.timestamp / 1_000_000L,
                    ),
                )
            }
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { manager.unregisterListener(listener) }
    }

    /**
     * Step-detector events as a cold flow of step timestamps (ms,
     * elapsed-realtime base). Empty when the sensor is absent or
     * ACTIVITY_RECOGNITION is denied (required on API 29+; requested as
     * an optional grant in the onboarding flow). Registration rides
     * collection lifecycle like the heading flow.
     */
    fun steps(context: Context): Flow<Long> = callbackFlow {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        if (sensor == null) {
            close()
            return@callbackFlow
        }
        if (Build.VERSION.SDK_INT >= 29 &&
            context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            close() // permission gate: walking DR silently degrades
            return@callbackFlow
        }
        val listener = object : SensorEventCallback() {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_STEP_DETECTOR) return
                trySend(event.timestamp / 1_000_000L)
            }
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        awaitClose { manager.unregisterListener(listener) }
    }

    /** Whether walking DR is physically possible on this device. */
    fun hasStepSensor(context: Context): Boolean =
        context.getSystemService(SensorManager::class.java)
            .getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null
}