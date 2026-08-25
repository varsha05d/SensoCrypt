package com.sensocrypt

import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.tooling.preview.Preview as ComposePreview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sensocrypt.call.CallScreen
import com.sensocrypt.challenge.runChallengeFlash
import com.sensocrypt.capture.FrameCapture
import com.sensocrypt.capture.LiveStreamer
import com.sensocrypt.capture.SensorCapture
import com.sensocrypt.capture.SessionRecorder
import com.sensocrypt.capture.Synchronizer
import com.sensocrypt.crypto.KeystoreManager
import com.sensocrypt.crypto.buildAuthMessage
import com.sensocrypt.crypto.deriveSessionKeys
import com.sensocrypt.crypto.encryptTelemetryChunk
import com.sensocrypt.crypto.generateEphemeralKeyPair
import com.sensocrypt.crypto.x25519Agree
import com.sensocrypt.identity.IdentityStore
import com.sensocrypt.net.AuthApi
import com.sensocrypt.net.SessionApi
import com.sensocrypt.net.TelemetrySocket
import com.sensocrypt.net.buildTelemetryChunkJson
import com.sensocrypt.ui.theme.SensoCryptTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Phase 1 skeleton (plan.md §11): camera preview next to live gyro/accel numbers,
 * nothing streamed anywhere yet. This is the "done when" deliverable for Phase 1 --
 * everything else (attestation, telemetry, challenges, WebRTC) builds on top of this.
 */
class MainActivity : ComponentActivity() {

    private lateinit var sensorCapture: SensorCapture

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorCapture = SensorCapture(applicationContext)

        setContent {
            SensoCryptTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(sensorCapture = sensorCapture)
                }
            }
        }
    }
}

@Composable
private fun AppRoot(sensorCapture: SensorCapture) {
    val context = LocalContext.current
    var showCall by remember { mutableStateOf(false) }

    // CameraX (this screen's liveness camera) and WebRTC's own capturer (the call screen)
    // cannot both hold the front camera at once -- explicitly release CameraX's binding
    // before handing the camera to WebRTC. CallScreen's own DisposableEffect releases
    // WebRTC's capturer on the way back out, letting CameraX rebind normally.
    LaunchedEffect(showCall) {
        if (showCall) {
            // .get() blocks, so do that off the main thread -- but unbindAll() itself
            // requires the main thread, so it runs after this function resumes back onto
            // LaunchedEffect's default (main-associated) dispatcher.
            val provider = withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
            provider.unbindAll()
        }
    }

    if (showCall) {
        CallScreen(onExit = { showCall = false })
    } else {
        SensoCryptScreen(sensorCapture = sensorCapture, onStartCall = { showCall = true })
    }
}

@Composable
fun SensoCryptScreen(sensorCapture: SensorCapture, onStartCall: () -> Unit) {
    val context = LocalContext.current
    val synchronizer = remember { Synchronizer() }
    val sessionRecorder = remember { SessionRecorder(context) }
    val liveStreamer = remember { LiveStreamer(context) }
    val frameCapture = remember {
        FrameCapture(synchronizer) { frame ->
            sessionRecorder.onFrame(frame)
            liveStreamer.onFrame(frame)
        }
    }
    var challengeFlashColor by remember { mutableStateOf<Color?>(null) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    DisposableEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
        sensorCapture.start()
        onDispose { sensorCapture.stop() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            CameraPreview(modifier = Modifier.fillMaxSize(), analyzer = frameCapture)
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(context.getString(R.string.camera_permission_rationale))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant camera permission")
                }
            }
        }

        SensorOverlay(sensorCapture = sensorCapture, modifier = Modifier.align(Alignment.BottomStart))
        Column(modifier = Modifier.align(Alignment.TopStart)) {
            IdentityPanel()
            RecordingPanel(sessionRecorder = sessionRecorder)
            Button(onClick = onStartCall) { Text("Video Call") }
        }
        VerifyPanel(
            liveStreamer = liveStreamer,
            onChallengeFlash = { challengeFlashColor = it },
            modifier = Modifier.align(Alignment.Center),
        )

        // Illumination challenge overlay (plan.md §6.1): drawn last so it's on top of
        // everything, including the camera preview -- the screen itself is the light source
        // whose reflection off the user's face the server is scoring, not something the
        // camera "watches".
        challengeFlashColor?.let { c ->
            Box(modifier = Modifier.fillMaxSize().background(c))
        }
    }
}

@Composable
private fun IdentityPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keystoreManager = remember { KeystoreManager(context) }
    val authApi = remember { AuthApi() }
    val identityStore = remember { IdentityStore(context) }

    var status by remember {
        mutableStateOf(identityStore.deviceId?.let { "Enrolled: $it" } ?: "Not enrolled")
    }
    var busy by remember { mutableStateOf(false) }

    val keyguardLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        status = if (result.resultCode == android.app.Activity.RESULT_OK) {
            "Unlocked -- tap Authenticate again"
        } else {
            "Unlock cancelled"
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(12.dp),
    ) {
        Text(status, color = Color.White, fontSize = 13.sp)
        Row {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        status = try {
                            val init = authApi.enrollInit(Build.MODEL, Build.VERSION.RELEASE)
                            val challenge = Base64.decode(init.att_challenge_b64, Base64.NO_WRAP)
                            val chain = keystoreManager.createAttestedKey(challenge)
                            val chainB64 = chain.map { Base64.encodeToString(it, Base64.NO_WRAP) }
                            val finish = authApi.enrollFinish(init.enroll_id, chainB64)
                            identityStore.deviceId = finish.device_id
                            "Enrolled: ${finish.device_id}"
                        } catch (e: Exception) {
                            "Enroll failed: ${e.message}"
                        }
                        busy = false
                    }
                },
            ) { Text("Enroll") }

            Button(
                enabled = !busy,
                onClick = {
                    val deviceId = identityStore.deviceId
                    if (deviceId == null) {
                        status = "Enroll first"
                        return@Button
                    }
                    busy = true
                    scope.launch {
                        status = try {
                            val chal = authApi.challenge(deviceId)
                            val nonce = Base64.decode(chal.nonce_b64, Base64.NO_WRAP)
                            val pubkeyDer = keystoreManager.publicKeyDer()
                            val message = buildAuthMessage(nonce, chal.session_id, pubkeyDer)
                            val signature = try {
                                keystoreManager.sign(message)
                            } catch (e: UserNotAuthenticatedException) {
                                val keyguard = context.getSystemService(KeyguardManager::class.java)
                                val intent = keyguard.createConfirmDeviceCredentialIntent(
                                    "Unlock SensoCrypt",
                                    "Confirm your screen lock to sign the auth challenge",
                                )
                                if (intent != null) {
                                    keyguardLauncher.launch(intent)
                                    throw Exception("Please unlock, then tap Authenticate again")
                                }
                                throw Exception("Set a screen lock (PIN/pattern/biometric) to use this key")
                            }
                            val sigB64 = Base64.encodeToString(signature, Base64.NO_WRAP)
                            val verify = authApi.verify(chal.session_id, sigB64)
                            "Authenticated -- token expires in ${verify.expires_in}s"
                        } catch (e: Exception) {
                            "Auth failed: ${e.message}"
                        }
                        busy = false
                    }
                },
            ) { Text("Authenticate") }
        }
    }
}

private const val RECORDING_SECONDS = 30

@Composable
private fun RecordingPanel(sessionRecorder: SessionRecorder, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("No session recorded yet") }
    var secondsLeft by remember { mutableStateOf(0) }
    val isRecording = secondsLeft > 0

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(12.dp),
    ) {
        Text(status, color = Color.White, fontSize = 13.sp)
        Button(
            enabled = !isRecording,
            onClick = {
                sessionRecorder.start()
                secondsLeft = RECORDING_SECONDS
                status = "Recording -- move the phone naturally"
                scope.launch {
                    while (secondsLeft > 0) {
                        delay(1_000)
                        secondsLeft -= 1
                    }
                    val summary = sessionRecorder.stop()
                    status = "Saved ${summary.frameCount} frames, ${summary.gyroCount} gyro, " +
                        "${summary.accelCount} accel -> ${summary.dir.name}"
                }
            },
        ) {
            Text(if (isRecording) "Recording... ${secondsLeft}s" else "Record ${RECORDING_SECONDS}s session")
        }
    }
}

private const val CHUNK_INTERVAL_MS = 500L

private const val CAPTURE_DURATION_MS = 7_000L
private const val CLIENT_P_TRUST = 0.35
private const val CLIENT_P_INCONCLUSIVE = 0.15

private enum class VerifyPhase { IDLE, CAPTURING, RESULT }

/**
 * The app's actual purpose, end to end (plan.md §4.4, §4.5, §6.1, §8, §10.3): tap once,
 * hold/move the phone for a few guided seconds, get one clear answer. Deliberately a
 * bounded one-shot check rather than an open-ended live session with a continuously
 * updating trust banner -- that shape kept getting stuck in stale states and needed
 * constant threshold-chasing to feel right; a fresh short capture per check sidesteps that
 * entirely and matches what this is actually for (verifying a person once, not monitoring
 * a call indefinitely -- that's future work, see plan.md Phase 6/WebRTC).
 *
 * Server-side this still runs the full live pipeline (egomotion Channel A + illumination
 * Channel B, per-session axis/lag calibration) -- the client just takes the best evidence
 * seen during the capture window instead of trusting a single live FSM state.
 */
@Composable
private fun VerifyPanel(
    liveStreamer: LiveStreamer,
    onChallengeFlash: (Color?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keystoreManager = remember { KeystoreManager(context) }
    val authApi = remember { AuthApi() }
    val sessionApi = remember { SessionApi() }
    val identityStore = remember { IdentityStore(context) }

    var phase by remember { mutableStateOf(VerifyPhase.IDLE) }
    var instruction by remember { mutableStateOf("") }
    var secondsLeft by remember { mutableStateOf(0) }
    var resultGood by remember { mutableStateOf(false) }
    var resultTitle by remember { mutableStateOf("") }
    var resultDetail by remember { mutableStateOf("") }

    val keyguardLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) {
            phase = VerifyPhase.IDLE
        }
    }

    fun runVerification() {
        val deviceId = identityStore.deviceId
        if (deviceId == null) {
            phase = VerifyPhase.RESULT
            resultGood = false
            resultTitle = "Enroll first"
            resultDetail = ""
            return
        }
        phase = VerifyPhase.CAPTURING
        instruction = "Starting..."
        scope.launch {
            var ws: TelemetrySocket? = null
            try {
                val chal = authApi.challenge(deviceId)
                val nonce = Base64.decode(chal.nonce_b64, Base64.NO_WRAP)
                val pubkeyDer = keystoreManager.publicKeyDer()
                val message = buildAuthMessage(nonce, chal.session_id, pubkeyDer)
                val signature = try {
                    keystoreManager.sign(message)
                } catch (e: UserNotAuthenticatedException) {
                    val keyguard = context.getSystemService(KeyguardManager::class.java)
                    val intent = keyguard.createConfirmDeviceCredentialIntent(
                        "Unlock SensoCrypt",
                        "Confirm your screen lock to verify",
                    )
                    if (intent != null) keyguardLauncher.launch(intent)
                    throw Exception("Please unlock, then try again")
                }
                authApi.verify(chal.session_id, Base64.encodeToString(signature, Base64.NO_WRAP))

                val ephemeral = generateEphemeralKeyPair()
                val kexResp = sessionApi.kex(
                    chal.session_id,
                    Base64.encodeToString(ephemeral.publicRaw, Base64.NO_WRAP),
                )
                val epkS = Base64.decode(kexResp.epk_s_b64, Base64.NO_WRAP)
                val shared = x25519Agree(ephemeral.private, epkS)
                val (kTel, _) = deriveSessionKeys(shared, chal.session_id)

                ws = TelemetrySocket(chal.session_id, quick = true).also { it.connect() }
                liveStreamer.start()

                var bestPTrust = 0.0
                var sawTrusted = false
                var sawIllumOk = false

                val startMs = System.currentTimeMillis()
                while (System.currentTimeMillis() - startMs < CAPTURE_DURATION_MS) {
                    val elapsedMs = System.currentTimeMillis() - startMs
                    instruction = when {
                        elapsedMs < 1_500 -> "Hold steady..."
                        elapsedMs < 2_500 -> "Watch for a brief flash..."
                        else -> "Now move your phone naturally"
                    }
                    secondsLeft = ((CAPTURE_DURATION_MS - elapsedMs) / 1000).toInt() + 1

                    delay(CHUNK_INTERVAL_MS)
                    val chunk = liveStreamer.drainChunk()
                    if (chunk.frames.isNotEmpty()) {
                        val plaintext = buildTelemetryChunkJson(chunk)
                        val encrypted = encryptTelemetryChunk(kTel, chal.session_id, chunk.seq, plaintext)
                        ws.send(encrypted)
                    }

                    val json = try { JSONObject(ws.lastVerdict.value) } catch (e: Exception) { null }
                    if (json != null) {
                        val p = json.optDouble("p_trust", -1.0)
                        if (p >= 0.0) bestPTrust = maxOf(bestPTrust, p)
                        if (json.optString("trust_state") == "TRUSTED") sawTrusted = true
                        json.optJSONObject("new_challenge")?.let { challenge ->
                            launch { runChallengeFlash(challenge, onChallengeFlash) }
                        }
                        json.optJSONObject("S_illum")?.let { illum ->
                            if (illum.optString("verdict") == "ok" && illum.optDouble("S_illum", 0.0) > 0.3) {
                                sawIllumOk = true
                            }
                        }
                    }
                }

                liveStreamer.stop()
                ws.close()

                phase = VerifyPhase.RESULT
                when {
                    sawTrusted || bestPTrust >= CLIENT_P_TRUST -> {
                        resultGood = true
                        resultTitle = "Verified: Real Human"
                        resultDetail = "Score %.2f".format(bestPTrust) +
                            if (sawIllumOk) " -- illumination check passed" else ""
                    }
                    bestPTrust >= CLIENT_P_INCONCLUSIVE -> {
                        resultGood = false
                        resultTitle = "Inconclusive"
                        resultDetail = "Score %.2f -- try moving the phone more".format(bestPTrust)
                    }
                    else -> {
                        resultGood = false
                        resultTitle = "Could not verify liveness"
                        resultDetail = "Score %.2f -- try better lighting and movement".format(bestPTrust)
                    }
                }
            } catch (e: Exception) {
                liveStreamer.stop()
                ws?.close()
                phase = VerifyPhase.RESULT
                resultGood = false
                resultTitle = "Verification failed"
                resultDetail = e.message ?: "unknown error"
            }
        }
    }

    Box(modifier = modifier.padding(24.dp)) {
        when (phase) {
            VerifyPhase.IDLE -> {
                Button(onClick = { runVerification() }) {
                    Text("Verify I'm Human", fontSize = 16.sp)
                }
            }
            VerifyPhase.CAPTURING -> {
                Column(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(20.dp),
                ) {
                    Text(instruction, color = Color(0xFFFFD54F), fontSize = 16.sp)
                    Text("${secondsLeft}s remaining", color = Color.White, fontSize = 13.sp)
                }
            }
            VerifyPhase.RESULT -> {
                Column(
                    modifier = Modifier
                        .background(if (resultGood) Color(0xFF1B7A3D) else Color(0xFFB3261E))
                        .padding(20.dp),
                ) {
                    Text(resultTitle, color = Color.White, fontSize = 18.sp)
                    Text(resultDetail, color = Color.White, fontSize = 12.sp)
                    Button(onClick = { phase = VerifyPhase.IDLE }) {
                        Text("Verify Again")
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(modifier: Modifier = Modifier, analyzer: ImageAnalysis.Analyzer) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, analyzer) }

                // Front camera: this is the camera the liveness engine will reason about.
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

@Composable
private fun SensorOverlay(sensorCapture: SensorCapture, modifier: Modifier = Modifier) {
    val gyro by sensorCapture.gyro.collectAsStateWithLifecycle()
    val accel by sensorCapture.accel.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(12.dp),
    ) {
        Text("GYRO  (rad/s)", color = Color.White, fontSize = 12.sp)
        Text(formatVector(gyro.x, gyro.y, gyro.z), color = Color(0xFF3DDC97), fontSize = 16.sp)
        Text("ACCEL (m/s²)", color = Color.White, fontSize = 12.sp)
        Text(formatVector(accel.x, accel.y, accel.z), color = Color(0xFF3DDC97), fontSize = 16.sp)
        if (!sensorCapture.hasGyroscope || !sensorCapture.hasAccelerometer) {
            Text(
                "Missing required sensor(s) -- egomotion correlation needs both.",
                color = Color(0xFFFF6B6B),
                fontSize = 12.sp,
            )
        }
    }
}

private fun formatVector(x: Float, y: Float, z: Float): String =
    "x=% .3f  y=% .3f  z=% .3f".format(x, y, z)

@ComposePreview(showBackground = true)
@Composable
fun SensoCryptScreenPreview() {
    SensoCryptTheme {
        Text("SensoCrypt -- camera preview appears on-device")
    }
}
