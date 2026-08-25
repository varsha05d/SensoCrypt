package com.sensocrypt

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import com.sensocrypt.crypto.KeystoreManager
import com.sensocrypt.identity.IdentityStore
import com.sensocrypt.net.AuthApi
import com.sensocrypt.ui.theme.SensoCryptTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // CameraX (this screen's preview) and WebRTC's own capturer (the call screen) cannot
    // both hold the front camera at once -- explicitly release CameraX's binding before
    // handing the camera to WebRTC. CallScreen's own DisposableEffect releases WebRTC's
    // capturer on the way back out, letting CameraX rebind normally.
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
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
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

/**
 * The app's only real action: start a video call. Liveness verification happens
 * automatically and continuously once a call connects (both sides score themselves and
 * broadcast the result to each other, see CallScreen) -- that's the actual threat model
 * (is the OTHER person on the call real), so there's no separate "verify yourself" step
 * to trigger on this screen.
 */
@Composable
private fun VerifiedHomeScreen(onStartCall: () -> Unit) {
    val context = LocalContext.current

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
            CameraPreview(modifier = Modifier.fillMaxSize())
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
                    .padding(horizontal = 20.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Button(
                    onClick = onStartCall,
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Icon(Icons.Filled.Videocam, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Start Video Call",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
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

@Composable
private fun CameraPreview(modifier: Modifier = Modifier) {
    val lifecycleOwner = LocalLifecycleOwner.current

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
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
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
