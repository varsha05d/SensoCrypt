package com.sensocrypt.identity

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import androidx.activity.result.ActivityResultLauncher
import com.sensocrypt.crypto.KeystoreManager
import com.sensocrypt.crypto.buildAuthMessage
import com.sensocrypt.crypto.deriveSessionKeys
import com.sensocrypt.crypto.generateEphemeralKeyPair
import com.sensocrypt.crypto.x25519Agree
import com.sensocrypt.net.AuthApi
import com.sensocrypt.net.ChallengeResponse
import com.sensocrypt.net.SessionApi

data class HandshakeResult(val challenge: ChallengeResponse, val kTel: ByteArray)

/**
 * Runs /auth/challenge -> sign -> /auth/verify -> /session/kex (plan.md §4.3, §4.4).
 * Shared by every flow that needs a fresh authenticated session key (the one-shot verify
 * check and the in-call liveness check) so the crypto protocol is implemented in exactly
 * one place.
 */
suspend fun authenticateAndKex(
    context: Context,
    deviceId: String,
    keystoreManager: KeystoreManager,
    authApi: AuthApi,
    sessionApi: SessionApi,
    keyguardLauncher: ActivityResultLauncher<Intent>,
): HandshakeResult {
    val chal = authApi.challenge(deviceId)
    val nonce = Base64.decode(chal.nonce_b64, Base64.NO_WRAP)
    val pubkeyDer = keystoreManager.publicKeyDer()
    val message = buildAuthMessage(nonce, chal.session_id, pubkeyDer)
    val signature = try {
        keystoreManager.sign(message)
    } catch (e: UserNotAuthenticatedException) {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val intent = keyguard.createConfirmDeviceCredentialIntent("Unlock SensoCrypt", "Confirm your screen lock")
        if (intent != null) keyguardLauncher.launch(intent)
        throw Exception("Please unlock, then try again")
    }
    authApi.verify(chal.session_id, Base64.encodeToString(signature, Base64.NO_WRAP))

    val ephemeral = generateEphemeralKeyPair()
    val kexResp = sessionApi.kex(chal.session_id, Base64.encodeToString(ephemeral.publicRaw, Base64.NO_WRAP))
    val epkS = Base64.decode(kexResp.epk_s_b64, Base64.NO_WRAP)
    val shared = x25519Agree(ephemeral.private, epkS)
    val (kTel, _) = deriveSessionKeys(shared, chal.session_id)

    return HandshakeResult(chal, kTel)
}
