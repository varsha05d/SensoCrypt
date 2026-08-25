package com.sensocrypt.capture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One reading from a single motion sensor, sharing the elapsedRealtimeNanos clock base
 * that SensorEvent.timestamp already uses. Phase 3 (§5.3/§5.4 of the plan) aligns this
 * stream against the visually-estimated angular velocity from the camera frames.
 */
data class SensorReading(
    val timestampNs: Long,
    val x: Float,
    val y: Float,
    val z: Float,
)

/**
 * Wraps SensorManager for the two sensors the liveness engine needs (§2, §5.3 of plan.md):
 * raw gyroscope (used directly, never integrated) and accelerometer (gravity still included;
 * Phase 3 will subtract it via TYPE_LINEAR_ACCELERATION or by rotating out TYPE_GRAVITY).
 *
 * Requested at 200 Hz (5000 us) with batching, per §10.2 -- this is a skeleton reader for
 * on-screen display; the telemetry packer (Phase 4) will read from the same registrations.
 */
class SensorCapture(context: Context) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _gyro = MutableStateFlow(SensorReading(0L, 0f, 0f, 0f))
    val gyro: StateFlow<SensorReading> = _gyro

    private val _accel = MutableStateFlow(SensorReading(0L, 0f, 0f, 0f))
    val accel: StateFlow<SensorReading> = _accel

    val hasGyroscope: Boolean get() = gyroscope != null
    val hasAccelerometer: Boolean get() = accelerometer != null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val reading = SensorReading(event.timestamp, event.values[0], event.values[1], event.values[2])
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> _gyro.value = reading
                Sensor.TYPE_ACCELEROMETER -> _accel.value = reading
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    fun start() {
        // samplingPeriodUs=5_000 -> ~200 Hz; maxReportLatencyUs=50_000 batches for battery (§10.2).
        gyroscope?.let {
            sensorManager.registerListener(listener, it, 5_000, 50_000)
        }
        accelerometer?.let {
            sensorManager.registerListener(listener, it, 5_000, 50_000)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
    }
}
