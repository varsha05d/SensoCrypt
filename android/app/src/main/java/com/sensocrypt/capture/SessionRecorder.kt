package com.sensocrypt.capture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SessionSummary(val dir: File, val frameCount: Int, val gyroCount: Int, val accelCount: Int)

/**
 * Records a genuine session to disk for Phase 3's offline liveness-engine development
 * (plan.md §11 Phase 3, §17.5: "do Phase 3 entirely offline ... debugging optical flow
 * through a WebSocket is misery"). Frames are stored raw (uncompressed grayscale), not the
 * JPEG-q40 the eventual wire format uses (§4.5) -- fidelity matters more than bandwidth
 * while the correlation algorithm itself is still being built and validated.
 *
 * Output layout, one folder per session under getExternalFilesDir():
 *   frames.bin  -- concatenated fixed-size records: 8 bytes t_ns (big-endian) + 19200 bytes
 *                  (160x120 grayscale), i.e. record size 19208 bytes. Trivially readable with
 *                  numpy.fromfile + a structured dtype.
 *   gyro.csv    -- t_ns,x,y,z (rad/s)
 *   accel.csv   -- t_ns,x,y,z (m/s^2)
 */
class SessionRecorder(private val context: Context) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var isRecording = false

    private val frames = mutableListOf<GrayFrame>()
    private val gyroSamples = mutableListOf<SensorReading>()
    private val accelSamples = mutableListOf<SensorReading>()

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!isRecording) return
            val reading = SensorReading(event.timestamp, event.values[0], event.values[1], event.values[2])
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> gyroSamples.add(reading)
                Sensor.TYPE_ACCELEROMETER -> accelSamples.add(reading)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    fun start() {
        frames.clear()
        gyroSamples.clear()
        accelSamples.clear()
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(listener, it, 5_000, 0)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(listener, it, 5_000, 0)
        }
        isRecording = true
    }

    /** Called from the camera analyzer thread; safe to call whether or not recording is active. */
    fun onFrame(frame: GrayFrame) {
        if (isRecording) frames.add(frame)
    }

    fun stop(): SessionSummary {
        isRecording = false
        sensorManager.unregisterListener(listener)

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(context.getExternalFilesDir(null), "sessions/$stamp").apply { mkdirs() }

        RandomAccessFile(File(dir, "frames.bin"), "rw").use { raf ->
            for (frame in frames) {
                val header = java.nio.ByteBuffer.allocate(8).putLong(frame.timestampNs).array()
                raf.write(header)
                raf.write(frame.pixels)
            }
        }
        File(dir, "gyro.csv").writeText(
            "t_ns,x,y,z\n" + gyroSamples.joinToString("\n") { "${it.timestampNs},${it.x},${it.y},${it.z}" },
        )
        File(dir, "accel.csv").writeText(
            "t_ns,x,y,z\n" + accelSamples.joinToString("\n") { "${it.timestampNs},${it.x},${it.y},${it.z}" },
        )

        return SessionSummary(dir, frames.size, gyroSamples.size, accelSamples.size)
    }
}
