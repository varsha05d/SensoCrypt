package com.sensocrypt.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** /ws/telemetry/{session_id} (plan.md §4.5, §10.3): sends encrypted chunks, receives live
 * verdict JSON back on the same connection. */
class TelemetrySocket(
    private val sessionId: String,
    private val quick: Boolean = false,
    private val baseWsUrl: String = "ws://$BACKEND_HOST",
) {
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null

    private val _lastVerdict = MutableStateFlow("not connected")
    val lastVerdict: StateFlow<String> = _lastVerdict

    fun connect() {
        val suffix = if (quick) "?quick=1" else ""
        val request = Request.Builder().url("$baseWsUrl/ws/telemetry/$sessionId$suffix").build()
        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    _lastVerdict.value = text
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _lastVerdict.value = "socket error: ${t.message}"
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    _lastVerdict.value = "closed ($code): $reason"
                }
            },
        )
    }

    fun send(bytes: ByteArray) {
        webSocket?.send(ByteString.of(*bytes))
    }

    fun close() {
        webSocket?.close(1000, "done")
        webSocket = null
    }
}
