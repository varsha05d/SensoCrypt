package com.sensocrypt.challenge

import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import org.json.JSONObject

private const val LATE_CHALLENGE_SKIP_MS = 1_000L

/**
 * plan.md §6.1: waits until the server's scheduled start time (in the phone's own
 * elapsedRealtimeNanos, since every timestamp the server works with originated from this
 * phone), then flashes each state's color full-screen for its duration. Shared by the
 * standalone verify flow and the in-call liveness reporting -- both need the exact same
 * flash timing to match what the server scores against.
 */
suspend fun runChallengeFlash(challenge: JSONObject, setColor: (Color?) -> Unit) {
    val startAtNs = challenge.getLong("start_at_ns")
    val waitMs = (startAtNs - SystemClock.elapsedRealtimeNanos()) / 1_000_000
    if (waitMs < -LATE_CHALLENGE_SKIP_MS) return // too late to render meaningfully
    if (waitMs > 0) delay(waitMs)

    val states = challenge.getJSONArray("states")
    for (i in 0 until states.length()) {
        val state = states.getJSONObject(i)
        val rgb = state.getJSONArray("rgb")
        setColor(Color(rgb.getInt(0), rgb.getInt(1), rgb.getInt(2)))
        delay(state.getLong("dur_ms"))
    }
    setColor(null)
}
