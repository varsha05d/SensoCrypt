package com.sensocrypt.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable data class EnrollInitRequest(val device_model: String, val os_version: String)
@Serializable data class EnrollInitResponse(val enroll_id: String, val att_challenge_b64: String)

@Serializable data class EnrollFinishRequest(val enroll_id: String, val cert_chain_b64: List<String>)
@Serializable data class EnrollFinishResponse(val device_id: String)

@Serializable data class ChallengeRequest(val device_id: String)
@Serializable data class ChallengeResponse(val session_id: String, val nonce_b64: String, val server_ts: Long)

@Serializable
data class VerifyRequest(
    val session_id: String,
    val sig_der_b64: String,
    val channel_binding_b64: String? = null,
)
@Serializable data class VerifyResponse(val token: String, val expires_in: Int)

class AuthApiException(val httpCode: Int, val body: String) : Exception("HTTP $httpCode: $body")

/** Talks to the /api/v1/auth endpoints (plan.md §17.1). Default base URL comes from
 * net/BackendConfig.kt. */
class AuthApi(private val baseUrl: String = "$BACKEND_HTTP_SCHEME://$BACKEND_HOST/api/v1/auth") {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private suspend inline fun <reified Req, reified Res> post(path: String, body: Req): Res =
        withContext(Dispatchers.IO) {
            val requestBody = json.encodeToString(body).toRequestBody(jsonMedia)
            val request = Request.Builder().url("$baseUrl$path").post(requestBody).build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw AuthApiException(response.code, text)
                json.decodeFromString(text)
            }
        }

    suspend fun enrollInit(deviceModel: String, osVersion: String): EnrollInitResponse =
        post("/enroll/init", EnrollInitRequest(deviceModel, osVersion))

    suspend fun enrollFinish(enrollId: String, certChainB64: List<String>): EnrollFinishResponse =
        post("/enroll/finish", EnrollFinishRequest(enrollId, certChainB64))

    suspend fun challenge(deviceId: String): ChallengeResponse =
        post("/challenge", ChallengeRequest(deviceId))

    suspend fun verify(sessionId: String, sigDerB64: String, channelBindingB64: String? = null): VerifyResponse =
        post("/verify", VerifyRequest(sessionId, sigDerB64, channelBindingB64))
}
