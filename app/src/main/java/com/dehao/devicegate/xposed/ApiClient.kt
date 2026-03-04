package com.dehao.devicegate.xposed

import com.dehao.devicegate.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class VerifyResult(
    val allowed: Boolean,
    val expiresAtMs: Long
)

/** Typed errors so callers can decide fail-open vs fail-closed per error class. */
sealed class ApiError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Network/DNS/timeout — transient, may succeed next launch */
    class Network(message: String, cause: Throwable? = null) : ApiError(message, cause)
    /** 4xx — bad request, won't self-heal */
    class Client(val code: Int, message: String) : ApiError(message)
    /** 5xx / empty body — server-side, transient */
    class Server(val code: Int, message: String) : ApiError(message)
    /** HMAC mismatch — tamper or key mismatch */
    class InvalidSignature(message: String) : ApiError(message)
}

object ApiClient {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // Cold-start on Vercel Free can take 5-8 s; 10 s connect + 15 s read is safe.
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * @throws ApiError.Network  on IO / timeout
     * @throws ApiError.Client   on 4xx
     * @throws ApiError.Server   on 5xx / empty body
     * @throws ApiError.InvalidSignature on HMAC mismatch
     */
    fun verify(deviceToken: String, packageName: String): VerifyResult {
        val reqJson = JSONObject()
            .put("device_token", deviceToken)
            .put("package_name", packageName)

        val request = Request.Builder()
            .url(BuildConfig.API_BASE_URL)
            .post(reqJson.toString().toRequestBody(jsonMediaType))
            .addHeader("Content-Type", "application/json")
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw ApiError.Network("Network error: ${e.message}", e)
        }

        response.use { resp ->
            val code = resp.code
            when {
                code in 400..499 -> throw ApiError.Client(code, "HTTP $code")
                code in 500..599 -> throw ApiError.Server(code, "HTTP $code")
                !resp.isSuccessful -> throw ApiError.Server(code, "HTTP $code")
            }

            val bodyStr = resp.body?.string()
                ?: throw ApiError.Server(code, "Empty response body")

            val json = try {
                JSONObject(bodyStr)
            } catch (e: Exception) {
                throw ApiError.Server(code, "Invalid JSON: ${e.message}")
            }

            val allowed = json.optBoolean("allowed", BuildConfig.FAIL_OPEN)
            val expiresAtMs = json.optLong(
                "expires_at",
                System.currentTimeMillis() + BuildConfig.DEFAULT_CACHE_TTL_MS
            )
            val signature = json.optString("signature", "")

            if (BuildConfig.ENFORCE_SIGNATURE) {
                if (signature.isBlank()) {
                    throw ApiError.InvalidSignature("Missing signature")
                }
                val payload = SignatureUtils.buildPayload(deviceToken, packageName, allowed, expiresAtMs)
                val expected = SignatureUtils.signHex(payload, BuildConfig.RESPONSE_SIGNING_KEY)
                if (!SignatureUtils.equalsConstantTime(expected, signature)) {
                    throw ApiError.InvalidSignature("HMAC mismatch")
                }
            }

            return VerifyResult(allowed = allowed, expiresAtMs = expiresAtMs)
        }
    }
}
