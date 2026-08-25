package com.sensocrypt.crypto

import java.security.SecureRandom
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

/**
 * Ephemeral X25519 key agreement and HKDF (plan.md §4.4). Must match
 * backend/app/core/crypto.py::derive_session_keys byte-for-byte -- see that function's
 * docstring for the scoped-down-vs-full-SIGMA-I tradeoff this makes.
 */
data class EphemeralKeyPair(val private: X25519PrivateKeyParameters, val publicRaw: ByteArray)

fun generateEphemeralKeyPair(): EphemeralKeyPair {
    val generator = X25519KeyPairGenerator()
    generator.init(X25519KeyGenerationParameters(SecureRandom()))
    val pair = generator.generateKeyPair()
    val priv = pair.private as X25519PrivateKeyParameters
    val pub = pair.public as X25519PublicKeyParameters
    return EphemeralKeyPair(priv, pub.encoded)
}

fun x25519Agree(privateKey: X25519PrivateKeyParameters, peerPublicRaw: ByteArray): ByteArray {
    val agreement = X25519Agreement()
    agreement.init(privateKey)
    val sharedSecret = ByteArray(agreement.agreementSize)
    agreement.calculateAgreement(X25519PublicKeyParameters(peerPublicRaw, 0), sharedSecret, 0)
    return sharedSecret
}

/** Returns (k_tel, k_chal), matching HKDF(salt=session_id, ikm=Z, info=label) on the server. */
fun deriveSessionKeys(sharedSecret: ByteArray, sessionId: String): Pair<ByteArray, ByteArray> {
    fun hkdf(info: ByteArray): ByteArray {
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(sharedSecret, sessionId.toByteArray(Charsets.UTF_8), info))
        val out = ByteArray(32)
        gen.generateBytes(out, 0, 32)
        return out
    }
    val kTel = hkdf("sensocrypt/telemetry/v1".toByteArray(Charsets.UTF_8))
    val kChal = hkdf("sensocrypt/challenge/v1".toByteArray(Charsets.UTF_8))
    return kTel to kChal
}
