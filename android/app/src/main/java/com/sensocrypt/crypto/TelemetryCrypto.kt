package com.sensocrypt.crypto

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * plan.md §4.5 wire format, minus the varint(len) prefix -- a WebSocket message is already
 * a discrete, length-delimited frame, so that prefix (meant for a raw byte stream) is
 * unnecessary here:
 *   nonce12 = seq (8B, big-endian) || 0x00000000
 *   aad     = "SC-TEL-v1" || session_id || seq
 *   ct      = AES-256-GCM(k_tel, nonce12, plaintext, aad)
 * Returns nonce12 || ct, exactly what backend/app/api/telemetry.py expects per message.
 */
fun encryptTelemetryChunk(kTel: ByteArray, sessionId: String, seq: Long, plaintext: ByteArray): ByteArray {
    val nonce = ByteBuffer.allocate(12).putLong(seq).putInt(0).array()
    val aad = "SC-TEL-v1".toByteArray(Charsets.UTF_8) +
        sessionId.toByteArray(Charsets.UTF_8) +
        ByteBuffer.allocate(8).putLong(seq).array()

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kTel, "AES"), GCMParameterSpec(128, nonce))
    cipher.updateAAD(aad)
    val ciphertext = cipher.doFinal(plaintext)
    return nonce + ciphertext
}
