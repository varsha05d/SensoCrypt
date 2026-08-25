package com.sensocrypt.capture

import android.graphics.ImageFormat
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/** A downscaled grayscale frame, timestamped on the device (sensor) clock via Synchronizer. */
data class GrayFrame(val timestampNs: Long, val width: Int, val height: Int, val pixels: ByteArray)

private const val TARGET_W = 160
private const val TARGET_H = 120
private const val TARGET_FPS = 15
private const val MIN_FRAME_INTERVAL_NS = 1_000_000_000L / TARGET_FPS

/**
 * CameraX ImageAnalysis.Analyzer producing the 160x120 grayscale stream plan.md §0.2 calls
 * for: the verifier needs its own copy of what the camera saw, downscaled to keep bandwidth
 * low once this feeds the telemetry channel (Phase 4). Luma-plane-only (Y of YUV_420_888) --
 * no color needed for optical flow.
 */
class FrameCapture(
    private val synchronizer: Synchronizer,
    private val onFrame: (GrayFrame) -> Unit,
) : ImageAnalysis.Analyzer {
    private var lastEmittedNs = 0L

    override fun analyze(image: ImageProxy) {
        try {
            if (image.format != ImageFormat.YUV_420_888) return
            val rawTs = image.imageInfo.timestamp
            synchronizer.observe(rawTs)

            if (rawTs - lastEmittedNs < MIN_FRAME_INTERVAL_NS) return
            lastEmittedNs = rawTs

            val yPlane = image.planes[0]
            val gray = downscaleLuma(yPlane.buffer, yPlane.rowStride, image.width, image.height, TARGET_W, TARGET_H)
            val deviceTs = if (synchronizer.isCalibrated) synchronizer.toDeviceClock(rawTs) else rawTs
            onFrame(GrayFrame(deviceTs, TARGET_W, TARGET_H, gray))
        } finally {
            image.close()
        }
    }

    private fun downscaleLuma(
        buffer: ByteBuffer,
        rowStride: Int,
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int,
    ): ByteArray {
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val out = ByteArray(dstW * dstH)
        for (dy in 0 until dstH) {
            val sy = dy * srcH / dstH
            for (dx in 0 until dstW) {
                val sx = dx * srcW / dstW
                out[dy * dstW + dx] = bytes[sy * rowStride + sx]
            }
        }
        return out
    }
}
