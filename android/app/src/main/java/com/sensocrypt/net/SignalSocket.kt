package com.sensocrypt.net

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** /ws/signal/{call_id}: relays SDP offer/answer, ICE candidates, and verdict messages
 * between exactly two peers (plan.md §11 Phase 6). */
class SignalSocket(private val callId: String, private val baseWsUrl: String = "ws://$BACKEND_HOST") {
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val messages: SharedFlow<String> = _messages

    fun connect() {
        val request = Request.Builder().url("$baseWsUrl/ws/signal/$callId").build()
        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    _messages.tryEmit(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _messages.tryEmit("""{"type":"error","message":"${t.message}"}""")
                }
            },
        )
    }

    fun send(text: String) {
        webSocket?.send(text)
    }

    fun close() {
        webSocket?.close(1000, "done")
        webSocket = null
    }
}
