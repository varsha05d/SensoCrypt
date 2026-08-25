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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview as ComposePreview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.sensocrypt.call.CallScreen
import com.sensocrypt.challenge.runChallengeFlash
import com.sensocrypt.capture.FrameCapture
import com.sensocrypt.capture.LiveStreamer
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
import com.sensocrypt.ui.theme.LocalSensoStatusColors
import com.sensocrypt.ui.theme.SensoCryptTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SensoCryptTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    var showCall by remember { mutableStateOf(false) }

    // CameraX (this screen's liveness camera) and WebRTC's own capturer (the call screen)
    // cannot both hold the front camera at once -- explicitly release CameraX's binding
    // before handing the camera to WebRTC. CallScreen's own DisposableEffect releases
    // WebRTC's capturer on the way back out, letting CameraX rebind normally.
    LaunchedEffect(showCall) {
        if (showCall) {
            val provider = withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
            provider.unbindAll()
        }
    }

    if (showCall) {
        CallScreen(onExit = { showCall = false })
    } else {
        HomeScreen(onStartCall = { showCall = true })
    }
}

private enum class SetupState { CHECKING, ENROLLING, READY, FAILED }

/**
 * The device's hardware-attested key is created once, automatically, the first time the
 * app runs -- no "Enroll" button for the user to find or understand. If it fails (no screen
 * lock set, no StrongBox, etc.) a plain retry screen explains what to do.
 */
@Composable
fun HomeScreen(onStartCall: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keystoreManager = remember { KeystoreManager(context) }
    val authApi = remember { AuthApi() }
    val identityStore = remember { IdentityStore(context) }

    var setupState by remember {
        mutableStateOf(if (identityStore.deviceId != null) SetupState.READY else SetupState.CHECKING)
    }
    var setupError by remember { mutableStateOf("") }

    fun runEnrollment() {
        setupState = SetupState.ENROLLING
        scope.launch {
            try {
                val init = authApi.enrollInit(Build.MODEL, Build.VERSION.RELEASE)
                val challenge = Base64.decode(init.att_challenge_b64, Base64.NO_WRAP)
                val chain = keystoreManager.createAttestedKey(challenge)
                val chainB64 = chain.map { Base64.encodeToString(it, Base64.NO_WRAP) }
                val finish = authApi.enrollFinish(init.enroll_id, chainB64)
                identityStore.deviceId = finish.device_id
                setupState = SetupState.READY
            } catch (e: Exception) {
                setupError = e.message ?: "Setup failed"
                setupState = SetupState.FAILED
            }
        }
    }

    LaunchedEffect(Unit) {
        if (setupState == SetupState.CHECKING) runEnrollment()
    }

    when (setupState) {
        SetupState.CHECKING, SetupState.ENROLLING -> SetupScreen()
        SetupState.FAILED -> SetupFailedScreen(message = setupError, onRetry = { runEnrollment() })
        SetupState.READY -> VerifiedHomeScreen(onStartCall = onStartCall)
    }
}

@Composable
private fun SetupScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(20.dp))
            Text(
                "Setting up your secure ID…",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "This only happens once.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SetupFailedScreen(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text("Couldn't set up your device", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRetry) { Text("Try Again") }
        }
    }
}

@Composable
private fun VerifiedHomeScreen(onStartCall: () -> Unit) {
    val context = LocalContext.current
    val synchronizer = remember { Synchronizer() }
    val liveStreamer = remember { LiveStreamer(context) }
    val frameCapture = remember { FrameCapture(synchronizer) { frame -> liveStreamer.onFrame(frame) } }
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
        onDispose { }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            CameraPreview(modifier = Modifier.fillMaxSize(), analyzer = frameCapture)
            // Soften the raw camera feed behind the UI so text/buttons stay legible
            // regardless of what's in frame.
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.55f),
                        0.35f to Color.Black.copy(alpha = 0.05f),
                        0.7f to Color.Black.copy(alpha = 0.05f),
                        1f to Color.Black.copy(alpha = 0.65f),
                    ),
                ),
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    context.getString(R.string.camera_permission_rationale),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant camera permission")
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().systemBarsPadding().padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppHeader()
        }

        if (hasCameraPermission) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .systemBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                VerifyPanel(
                    liveStreamer = liveStreamer,
                    onChallengeFlash = { challengeFlashColor = it },
                )
                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick = onStartCall,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Filled.Videocam, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start Video Call", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

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
private fun AppHeader() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(Icons.Filled.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(
            "SensoCrypt",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
        )
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
 * a call indefinitely).
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
    val statusColors = LocalSensoStatusColors.current

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
            resultTitle = "Setup not finished yet"
            resultDetail = "Please wait a moment and try again."
            return
        }
        phase = VerifyPhase.CAPTURING
        instruction = "Starting…"
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
                        elapsedMs < 1_500 -> "Hold steady…"
                        elapsedMs < 2_500 -> "Watch for a brief flash…"
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
                            if (sawIllumOk) " — illumination check passed" else ""
                    }
                    bestPTrust >= CLIENT_P_INCONCLUSIVE -> {
                        resultGood = false
                        resultTitle = "Inconclusive"
                        resultDetail = "Score %.2f — try moving the phone more".format(bestPTrust)
                    }
                    else -> {
                        resultGood = false
                        resultTitle = "Could not verify liveness"
                        resultDetail = "Score %.2f — try better lighting and movement".format(bestPTrust)
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

    AnimatedContent(
        targetState = phase,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        modifier = modifier.fillMaxWidth(),
        label = "verify-panel",
    ) { currentPhase ->
        when (currentPhase) {
            VerifyPhase.IDLE -> {
                Button(
                    onClick = { runVerification() },
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Icon(Icons.Filled.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Verify This Call",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            VerifyPhase.CAPTURING -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(instruction, color = MaterialTheme.colorScheme.tertiary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text("${secondsLeft}s remaining", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                }
            }
            VerifyPhase.RESULT -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (resultGood) statusColors.success.copy(alpha = 0.92f) else MaterialTheme.colorScheme.error.copy(alpha = 0.92f))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        if (resultGood) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(resultTitle, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(resultDetail, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { phase = VerifyPhase.IDLE },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    ) {
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

@ComposePreview(showBackground = true)
@Composable
fun SensoCryptScreenPreview() {
    SensoCryptTheme {
        Text("SensoCrypt -- camera preview appears on-device")
    }
}
