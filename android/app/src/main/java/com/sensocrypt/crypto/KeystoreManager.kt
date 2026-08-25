package com.sensocrypt.crypto

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Generates and uses the hardware-bound device key (plan.md §4.2, §10.1). The key never
 * leaves the TEE/StrongBox; enrollment sends the server only the certificate chain, which
 * proves possession without exposing the private key.
 *
 * Auth gating: uses a validity-duration window rather than a per-signature BiometricPrompt
 * CryptoObject flow (plan.md's stricter alternative) -- simpler to wire up, still requires a
 * fresh device unlock before the key usable, at the cost of not cryptographically binding
 * the specific auth event to the specific signature. Worth tightening past prototype stage.
 */
class KeystoreManager(private val context: Context) {
    companion object {
        private const val ALIAS = "sensocrypt_device_key_v1"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val AUTH_VALIDITY_SECONDS = 300
    }

    fun hasKey(): Boolean {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return ks.containsAlias(ALIAS)
    }

    /** Returns the certificate chain (leaf-first) to send to /auth/enroll/finish. */
    fun createAttestedKey(challenge: ByteArray): List<ByteArray> {
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        val specBuilder = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
            .setInvalidatedByBiometricEnrollment(true)
            .setUnlockedDeviceRequired(true)

        if (hasStrongBox()) {
            specBuilder.setIsStrongBoxBacked(true)
        }

        kpg.initialize(specBuilder.build())
        kpg.generateKeyPair()

        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return ks.getCertificateChain(ALIAS).map { it.encoded }
    }

    /** Throws UserNotAuthenticatedException if the device hasn't been unlocked recently
     * enough -- catch it and prompt KeyguardManager.createConfirmDeviceCredentialIntent. */
    fun sign(message: ByteArray): ByteArray {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val key = ks.getKey(ALIAS, null) as PrivateKey
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(message)
            sign()
        }
    }

    fun publicKeyDer(): ByteArray {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return ks.getCertificate(ALIAS).publicKey.encoded
    }

    private fun hasStrongBox(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
}
