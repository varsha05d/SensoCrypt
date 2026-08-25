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
class SignalSocket(private val callId: String, private val baseWsUrl: String = "$BACKEND_WS_SCHEME://$BACKEND_HOST") {
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

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    // 4409/4410 are this server's own codes (see backend/app/api/signal.py)
                    // for "already two people in this call" and "this call already ended" --
                    // surface them as the same synthetic error message type onFailure uses,
                    // so the UI has one place to handle "couldn't join" for any reason.
                    val message = when (code) {
                        4409 -> "This call already has two people in it"
                        4410 -> "This call has already ended"
                        else -> return
                    }
                    _messages.tryEmit("""{"type":"error","message":"$message"}""")
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
