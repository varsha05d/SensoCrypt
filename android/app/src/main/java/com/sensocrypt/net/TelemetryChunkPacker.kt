package com.sensocrypt.net

import android.util.Base64
import com.sensocrypt.capture.SensorReading
import com.sensocrypt.capture.TelemetryChunkData
import com.sensocrypt.capture.grayToJpeg
import org.json.JSONArray
import org.json.JSONObject

/** Plaintext chunk JSON, matching backend/app/api/telemetry.py's expected shape (plan.md §4.5,
 * trimmed to what Phase 4 needs -- the challenge field arrives in Phase 5). */
fun buildTelemetryChunkJson(chunk: TelemetryChunkData): ByteArray {
    val framesArr = JSONArray()
    for (frame in chunk.frames) {
        framesArr.put(
            JSONObject().apply {
                put("t_ns", frame.timestampNs)
                put("jpeg_b64", Base64.encodeToString(grayToJpeg(frame), Base64.NO_WRAP))
            },
        )
    }
    val gyroArr = JSONArray()
    for (g: SensorReading in chunk.gyro) {
        gyroArr.put(JSONArray().apply { put(g.timestampNs); put(g.x.toDouble()); put(g.y.toDouble()); put(g.z.toDouble()) })
    }
    val root = JSONObject().apply {
        put("seq", chunk.seq)
        put("frames", framesArr)
        put("imu", JSONObject().apply { put("gyro", gyroArr) })
    }
    return root.toString().toByteArray(Charsets.UTF_8)
}
