package com.sensocrypt.capture

/**
 * One reading from a single motion sensor, sharing the elapsedRealtimeNanos clock base
 * that SensorEvent.timestamp already uses (plan.md §5.3/§5.4 aligns this against the
 * visually-estimated angular velocity from camera frames).
 */
data class SensorReading(
    val timestampNs: Long,
    val x: Float,
    val y: Float,
    val z: Float,
)
