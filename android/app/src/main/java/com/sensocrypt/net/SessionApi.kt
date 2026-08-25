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

@Serializable data class KexRequest(val session_id: String, val epk_c_b64: String)
@Serializable data class KexResponse(val epk_s_b64: String)

/** /api/v1/session/kex (plan.md §4.4, §17.1). */
class SessionApi(private val baseUrl: String = "$BACKEND_HTTP_SCHEME://$BACKEND_HOST/api/v1/session") {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun kex(sessionId: String, epkCB64: String): KexResponse =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(KexRequest(sessionId, epkCB64)).toRequestBody(jsonMedia)
            val request = Request.Builder().url("$baseUrl/kex").post(body).build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw AuthApiException(response.code, text)
                json.decodeFromString(text)
            }
        }
}
