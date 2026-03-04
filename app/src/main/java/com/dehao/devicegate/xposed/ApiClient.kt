package com.dehao.devicegate.xposed

import com.dehao.devicegate.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class VerifyResult(
    val allowed: Boolean,
    val expiresAtMs: Long
)

object ApiClient {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .build()

    fun verify(deviceToken: String, packageName: String): VerifyResult {
        val reqJson = JSONObject()
            .put("device_token", deviceToken)
            .put("package_name", packageName)

        val request = Request.Builder()
            .url(BuildConfig.API_BASE_URL)
            .post(reqJson.toString().toRequestBody(jsonMediaType))
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IllegalStateException("Empty response")
            val json = JSONObject(body)
            val allowed = json.optBoolean("allowed", BuildConfig.FAIL_OPEN)
            val expiresAtMs = json.optLong(
                "expires_at",
                System.currentTimeMillis() + BuildConfig.DEFAULT_CACHE_TTL_MS
            )
            val signature = json.optString("signature", "")

            if (BuildConfig.ENFORCE_SIGNATURE) {
                if (signature.isBlank()) {
                    throw IllegalStateException("Missing signature")
                }
                val payload = SignatureUtils.buildPayload(deviceToken, packageName, allowed, expiresAtMs)
                val expected = SignatureUtils.signHex(payload, BuildConfig.RESPONSE_SIGNING_KEY)
                if (!SignatureUtils.equalsConstantTime(expected, signature)) {
                    throw IllegalStateException("Invalid signature")
                }
            }

            return VerifyResult(
                allowed = allowed,
                expiresAtMs = expiresAtMs
            )
        }
    }
}
