package com.sensocrypt.call

import com.sensocrypt.capture.GrayFrame
import com.sensocrypt.capture.Synchronizer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * Taps the WebRTC local video track to feed the SAME liveness pipeline the standalone
 * verify flow uses, instead of running a second CameraX session that would fight WebRTC's
 * Camera2Capturer for exclusive camera access. Converts each frame to the same 160x120
 * grayscale format FrameCapture (CameraX path) produces, so downstream code (LiveStreamer,
 * the JPEG packer, the backend engine) doesn't need to know which capture path a frame
 * came from.
 */
class WebRtcFrameSink(
    private val synchronizer: Synchronizer,
    private val onFrame: (GrayFrame) -> Unit,
) : VideoSink {
    companion object {
        private const val TARGET_W = 160
        private const val TARGET_H = 120
        private const val MIN_FRAME_INTERVAL_NS = 1_000_000_000L / 15
    }

    private var lastEmittedNs = 0L

    override fun onFrame(frame: VideoFrame) {
        val rawTs = frame.timestampNs
        synchronizer.observe(rawTs)
        if (rawTs - lastEmittedNs < MIN_FRAME_INTERVAL_NS) return
        lastEmittedNs = rawTs

        val i420 = frame.buffer.toI420() ?: return
        try {
            val gray = downscaleLuma(i420, TARGET_W, TARGET_H)
            val deviceTs = if (synchronizer.isCalibrated) synchronizer.toDeviceClock(rawTs) else rawTs
            onFrame(GrayFrame(deviceTs, TARGET_W, TARGET_H, gray))
        } finally {
            i420.release()
        }
    }

    private fun downscaleLuma(i420: VideoFrame.I420Buffer, dstW: Int, dstH: Int): ByteArray {
        val srcW = i420.width
        val srcH = i420.height
        val yPlane = i420.dataY
        val rowStride = i420.strideY
        val bytes = ByteArray(yPlane.remaining())
        yPlane.get(bytes)

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
