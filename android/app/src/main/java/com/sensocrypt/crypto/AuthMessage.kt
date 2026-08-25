package com.sensocrypt.crypto

import java.security.MessageDigest

/**
 * Must byte-for-byte match backend/app/core/crypto.py::build_auth_message (plan.md §4.3):
 *   m = H("SC-AUTH-v1" || nonce || session_id || H(pubkey) || EM)
 *
 * channelBinding (EM) defaults to empty to match the backend's current no-TLS-in-front-of-it
 * dev setup -- see the comment on the Python side before changing either independently.
 */
fun buildAuthMessage(
    nonce: ByteArray,
    sessionId: String,
    pubkeyDer: ByteArray,
    channelBinding: ByteArray = ByteArray(0),
): ByteArray {
    val sha256 = MessageDigest.getInstance("SHA-256")
    val pubkeyHash = sha256.digest(pubkeyDer)

    val parts = "SC-AUTH-v1".toByteArray(Charsets.UTF_8) +
        nonce +
        sessionId.toByteArray(Charsets.UTF_8) +
        pubkeyHash +
        channelBinding

    return MessageDigest.getInstance("SHA-256").digest(parts)
}
