package com.sensocrypt.call

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.sensocrypt.capture.LiveStreamer
import com.sensocrypt.capture.Synchronizer
import com.sensocrypt.challenge.runChallengeFlash
import com.sensocrypt.crypto.KeystoreManager
import com.sensocrypt.crypto.encryptTelemetryChunk
import com.sensocrypt.identity.IdentityStore
import com.sensocrypt.identity.authenticateAndKex
import com.sensocrypt.net.AuthApi
import com.sensocrypt.net.SessionApi
import com.sensocrypt.net.SignalSocket
import com.sensocrypt.net.TelemetrySocket
import com.sensocrypt.net.buildTelemetryChunkJson
import com.sensocrypt.ui.theme.LocalSensoStatusColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer

private const val TELEMETRY_CHUNK_INTERVAL_MS = 500L
private const val VERDICT_BROADCAST_INTERVAL_MS = 3_000L
private const val CLIENT_P_TRUST = 0.35
private const val PEER_BAD_STREAK_THRESHOLD = 3

private fun generateCallId(): String = (100_000..999_999).random().toString()

/**
 * Real-time "is the person I'm calling actually human" (plan.md §11 Phase 6): a live 1:1
 * video call where each side continuously scores its OWN liveness (egomotion + illumination,
 * same pipeline validated in Phases 3-5) and broadcasts the result to the other side over
 * the signaling channel, shown as a banner on their video. No second camera session is
 * needed -- WebRtcFrameSink taps the same camera frames WebRTC is already capturing for the
 * call itself.
 */
@Composable
fun CallScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keystoreManager = remember { KeystoreManager(context) }
    val authApi = remember { AuthApi() }
    val sessionApi = remember { SessionApi() }
    val identityStore = remember { IdentityStore(context) }

    var callId by remember { mutableStateOf(generateCallId()) }
    var joined by remember { mutableStateOf(false) }
    var myVerdictGood by remember { mutableStateOf(false) }
    var peerVerdictText by remember { mutableStateOf("Verifying the other person…") }
    var peerVerdictGood by remember { mutableStateOf<Boolean?>(null) }
    // A single weak/noisy reading must not flip the banner -- a person calmly talking
    // produces occasional low-confidence windows that are ambiguous, not evidence of
    // fakery. Only a SUSTAINED run of bad readings (PEER_BAD_STREAK_THRESHOLD in a row,
    // ~9s at the 3s broadcast interval) escalates to the warning.
    var peerBadStreak by remember { mutableStateOf(0) }
    var challengeFlashColor by remember { mutableStateOf<Color?>(null) }

    val eglBase = remember { EglBase.create() }
    val synchronizer = remember { Synchronizer() }
    val liveStreamer = remember { LiveStreamer(context) }
    val webRtcSession = remember { WebRtcSession(context, eglBase) }
    var signalSocket by remember { mutableStateOf<SignalSocket?>(null) }
    var reportingJob by remember { mutableStateOf<Job?>(null) }
    var signalReady by remember { mutableStateOf(false) }
    var localSetupDone by remember { mutableStateOf(false) }
    var offerSent by remember { mutableStateOf(false) }

    // localRenderer is NOT init()'d here -- WebRtcSession.start() does that internally.
    val localRenderer = remember { SurfaceViewRenderer(context) }
    val remoteRenderer = remember {
        SurfaceViewRenderer(context).apply { init(eglBase.eglBaseContext, null) }
    }

    // Holds a reference to whatever should resume after a successful unlock -- set inside
    // startContinuousLivenessReporting() itself (self-reference is fine; keyguardLauncher
    // must be declared before that function, so it can't call it directly by name).
    var retryAfterUnlock by remember { mutableStateOf<(() -> Unit)?>(null) }

    val keyguardLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            retryAfterUnlock?.invoke()
        }
    }

    fun sendSignal(json: JSONObject) {
        signalSocket?.send(json.toString())
    }

    fun sendOfferOnce() {
        if (offerSent) return
        offerSent = true
        webRtcSession.createOffer { offer ->
            sendSignal(JSONObject().apply { put("type", "offer"); put("sdp", offer.description) })
        }
    }

    fun startContinuousLivenessReporting() {
        retryAfterUnlock = ::startContinuousLivenessReporting
        val deviceId = identityStore.deviceId ?: return
        reportingJob = scope.launch {
            try {
                val handshake = authenticateAndKex(context, deviceId, keystoreManager, authApi, sessionApi, keyguardLauncher)
                val ws = TelemetrySocket(handshake.challenge.session_id, quick = true)
                ws.connect()

                // Separate collector so the illumination flash (plan.md §6.1) fires the
                // moment a challenge arrives, not just when the 3s broadcast tick happens
                // to sample it -- missing this was why S_illum stayed 0 in-call: the
                // server was scheduling challenges but nothing ever rendered the flash.
                launch {
                    ws.lastVerdict.collect { text ->
                        val json = try { JSONObject(text) } catch (e: Exception) { return@collect }
                        json.optJSONObject("new_challenge")?.let { challenge ->
                            launch { runChallengeFlash(challenge) { challengeFlashColor = it } }
                        }
                    }
                }

                var lastBroadcastMs = 0L
                while (true) {
                    delay(TELEMETRY_CHUNK_INTERVAL_MS)
                    val chunk = liveStreamer.drainChunk()
                    if (chunk.frames.isNotEmpty()) {
                        val plaintext = buildTelemetryChunkJson(chunk)
                        val encrypted =
                            encryptTelemetryChunk(handshake.kTel, handshake.challenge.session_id, chunk.seq, plaintext)
                        ws.send(encrypted)
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastBroadcastMs >= VERDICT_BROADCAST_INTERVAL_MS) {
                        lastBroadcastMs = now
                        val json = try { JSONObject(ws.lastVerdict.value) } catch (e: Exception) { null }
                        val p = json?.optDouble("p_trust", -1.0) ?: -1.0
                        val trustState = json?.optString("trust_state", "")
                        val good = p >= CLIENT_P_TRUST || trustState == "TRUSTED"
                        val displayScore = p.coerceAtLeast(0.0)
                        myVerdictGood = good
                        sendSignal(
                            JSONObject().apply {
                                put("type", "verdict")
                                put("good", good)
                                put("score", displayScore)
                            },
                        )
                    }
                }
            } catch (e: Exception) {
                // Swallow: the peer banner already communicates the important state; a
                // silent stop just means this side stops re-broadcasting, not a crash.
            }
        }
    }

    fun join() {
        val socket = SignalSocket(callId)
        signalSocket = socket

        webRtcSession.onIceCandidate = { candidate ->
            sendSignal(
                JSONObject().apply {
                    put("type", "ice")
                    put("candidate", candidate.sdp)
                    put("sdpMid", candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                },
            )
        }
        webRtcSession.onRemoteVideoTrack = { track -> track.addSink(remoteRenderer) }

        socket.connect()
        joined = true

        scope.launch {
            socket.messages.collect { raw ->
                val json = try { JSONObject(raw) } catch (e: Exception) { return@collect }
                when (json.optString("type")) {
                    "offer" -> {
                        webRtcSession.setRemoteDescription(
                            SessionDescription(SessionDescription.Type.OFFER, json.getString("sdp")),
                        )
                        webRtcSession.createAnswer { answer ->
                            sendSignal(JSONObject().apply { put("type", "answer"); put("sdp", answer.description) })
                        }
                    }
                    "answer" -> {
                        webRtcSession.setRemoteDescription(
                            SessionDescription(SessionDescription.Type.ANSWER, json.getString("sdp")),
                        )
                    }
                    "ready" -> {
                        // Both peers are now in the room. The server assigns exactly one
                        // side as offerer (whoever joined first) -- both sides run
                        // identical code, so without this both would try to offer
                        // simultaneously (WebRTC "glare": each side's incoming offer
                        // collides with its own already-sent local offer, corrupting
                        // negotiation -- verdict messages still flow since that's separate
                        // plain relay, but no video ever connects).
                        if (json.optString("role") == "offerer") {
                            signalReady = true
                            if (localSetupDone) sendOfferOnce()
                        }
                    }
                    "ice" -> {
                        webRtcSession.addIceCandidate(
                            IceCandidate(json.getString("sdpMid"), json.getInt("sdpMLineIndex"), json.getString("candidate")),
                        )
                    }
                    "verdict" -> {
                        val good = json.optBoolean("good", false)
                        if (good) {
                            peerBadStreak = 0
                            peerVerdictGood = true
                            peerVerdictText = "Verified real person"
                        } else {
                            peerBadStreak += 1
                            if (peerBadStreak >= PEER_BAD_STREAK_THRESHOLD) {
                                peerVerdictGood = false
                                peerVerdictText = "Could not verify — be careful, this could be fake"
                            } else if (peerVerdictGood != true) {
                                // Haven't earned a "verified" yet and this reading is also
                                // weak -- stay neutral rather than alarming on ambiguity.
                                peerVerdictText = "Verifying the other person…"
                            }
                            // else: was TRUSTED, this is just one weak blip -- say nothing,
                            // keep showing the verified state until the streak proves otherwise.
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(joined) {
        if (joined) {
            val frameSink = WebRtcFrameSink(synchronizer) { frame -> liveStreamer.onFrame(frame) }
            webRtcSession.start(localRenderer, frameSink)
            liveStreamer.start()
            localSetupDone = true
            if (signalReady) sendOfferOnce()
            startContinuousLivenessReporting()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            reportingJob?.cancel()
            liveStreamer.stop()
            webRtcSession.close()
            signalSocket?.close()
            localRenderer.release()
            remoteRenderer.release()
            eglBase.release()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (!joined) {
            CallLobby(
                callId = callId,
                onCallIdChange = { callId = it },
                onJoin = { join() },
                onExit = onExit,
            )
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { remoteRenderer })

                BackIconButton(onExit, modifier = Modifier.align(Alignment.TopStart).systemBarsPadding().padding(12.dp))

                PeerVerdictBanner(
                    text = peerVerdictText,
                    good = peerVerdictGood,
                    modifier = Modifier.align(Alignment.TopCenter).systemBarsPadding().padding(top = 12.dp),
                )

                AndroidView(
                    modifier = Modifier
                        .size(110.dp, 150.dp)
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(14.dp)),
                    factory = { localRenderer },
                )

                MyVerdictChip(
                    good = myVerdictGood,
                    modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                )
            }
        }

        // Illumination challenge overlay (plan.md §6.1) -- on top of everything, since the
        // screen itself is the light source being scored, not something the camera "reads".
        challengeFlashColor?.let { c ->
            Box(modifier = Modifier.fillMaxSize().background(c))
        }
    }
}

@Composable
private fun BackIconButton(onExit: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onExit,
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f)),
    ) {
        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
    }
}

@Composable
private fun PeerVerdictBanner(text: String, good: Boolean?, modifier: Modifier = Modifier) {
    val statusColors = LocalSensoStatusColors.current
    val bg = when (good) {
        true -> statusColors.success.copy(alpha = 0.92f)
        false -> MaterialTheme.colorScheme.error.copy(alpha = 0.92f)
        null -> Color(0xFF3A3A3A).copy(alpha = 0.85f)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        val icon = when (good) {
            true -> Icons.Filled.CheckCircle
            false -> Icons.Filled.Warning
            null -> Icons.Filled.HourglassTop
        }
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MyVerdictChip(good: Boolean, modifier: Modifier = Modifier) {
    val statusColors = LocalSensoStatusColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = if (good) statusColors.success else Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (good) "You: verified" else "You: checking…",
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun CallLobby(
    callId: String,
    onCallIdChange: (String) -> Unit,
    onJoin: () -> Unit,
    onExit: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    fun shareCode() {
        val message = "Join my SensoCrypt video call: open the app, tap \"Start Video Call\", " +
            "and enter this code: $callId"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }
        context.startActivity(Intent.createChooser(intent, "Share call code"))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        BackIconButton(onExit, modifier = Modifier.align(Alignment.TopStart).systemBarsPadding().padding(12.dp))

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.Videocam,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Start or join a call",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Share this code with the other person, or type theirs in to join their call.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = callId,
                onValueChange = onCallIdChange,
                label = { Text("Call code") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(callId)) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy code", tint = Color.White.copy(alpha = 0.8f))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = { shareCode() },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Share Code", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = onJoin,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text("Join Call", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}
