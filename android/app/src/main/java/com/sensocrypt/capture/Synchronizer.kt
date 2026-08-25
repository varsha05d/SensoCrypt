package com.sensocrypt.capture

import android.os.SystemClock

/**
 * Camera frames (ImageProxy.imageInfo.timestamp, SENSOR_TIMESTAMP) and sensor events
 * (SensorEvent.timestamp) are both nanosecond clocks, but on devices where
 * SENSOR_INFO_TIMESTAMP_SOURCE != REALTIME they're offset from each other by an unknown
 * constant. Calibrate that offset once from the first N frames and lock it -- letting it
 * drift per-frame would itself be an attack surface (plan.md §5.4).
 */
class Synchronizer(private val calibrationSamples: Int = 20) {
    private val offsetSamplesNs = mutableListOf<Long>()
    private var offsetNs = 0L
    var isCalibrated = false
        private set

    fun observe(cameraTimestampNs: Long) {
        if (isCalibrated) return
        offsetSamplesNs.add(SystemClock.elapsedRealtimeNanos() - cameraTimestampNs)
        if (offsetSamplesNs.size >= calibrationSamples) {
            offsetNs = offsetSamplesNs.sorted()[offsetSamplesNs.size / 2] // median, robust to outliers
            isCalibrated = true
        }
    }

    /** Maps a raw camera SENSOR_TIMESTAMP onto the elapsedRealtimeNanos clock that
     * SensorEvent.timestamp already uses, so frame and IMU timestamps are comparable. */
    fun toDeviceClock(cameraTimestampNs: Long): Long = cameraTimestampNs + offsetNs
}
