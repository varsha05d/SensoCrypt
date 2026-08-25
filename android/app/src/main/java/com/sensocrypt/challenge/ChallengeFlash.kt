package com.sensocrypt.challenge

import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import org.json.JSONObject

// Extra tolerance on top of the server's own CHALLENGE_LEAD_S buffer, for occasional
// slower-than-usual hops. Measured 1.48s late over the real internet to a deployed
// backend with the old 1s/1s budget -- every challenge was silently discarded as stale.
private const val LATE_CHALLENGE_SKIP_MS = 2_000L

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
    Log.d("SensoCrypt", "challenge received, waitMs=$waitMs (skip if < -$LATE_CHALLENGE_SKIP_MS)")
    if (waitMs < -LATE_CHALLENGE_SKIP_MS) {
        Log.w("SensoCrypt", "challenge SKIPPED: arrived too late (waitMs=$waitMs)")
        return // too late to render meaningfully
    }
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
