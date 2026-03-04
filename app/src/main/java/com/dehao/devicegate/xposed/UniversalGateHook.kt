package com.dehao.devicegate.xposed

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.widget.Toast
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Single-file Xposed module. No ContentProvider, no IPC, no BuildConfig.
 * Hook runs in target app process, does network on a background thread.
 *
 * Uses java.net.HttpURLConnection so we don't depend on OkHttp being on the classpath
 * in the target app process (Xposed may or may not share the module's dex with target).
 */
class UniversalGateHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "DeviceGate"

        // ── All config hardcoded ────────────────────────────────────────────
        private const val API_URL = "https://gatepulse-omega.vercel.app/api/device/check"
        private const val SIGNING_KEY = "b2b1fe307bf15758d31c58748fbd9886bb7713c62476e6f51841aaaa3636f9ed"
        private const val ENFORCE_SIGNATURE = true
        private const val FAIL_OPEN = false
        private const val CACHE_TTL_MS = 900_000L
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MODULE_PKG = "com.dehao.devicegate"

        private const val PREFS_NAME = "device_gate_cache"
        private const val KEY_DEVICE_TOKEN = "device_token"

        private val SKIP_EXACT = setOf(
            "android", "com.android.systemui", "com.google.android.gms", MODULE_PKG
        )
        private val SKIP_PREFIX = listOf(
            "com.android.", "androidx.", "com.qualcomm.", "com.miui.", "com.xiaomi.",
            "com.samsung.", "com.coloros.", "com.oplus.", "com.vivo.", "com.huawei.", "com.honor."
        )
    }

    private val executor = Executors.newSingleThreadExecutor()

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (shouldSkip(lpparam.packageName)) return

        XposedHelpers.findAndHookMethod(
            Application::class.java, "onCreate",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val app = param.thisObject as? Application ?: return
                    val pkg = app.packageName
                    if (shouldSkip(pkg)) return

                    val allow = try {
                        executor.submit<Boolean> { checkAllowed(app, pkg) }
                            .get(25, TimeUnit.SECONDS)
                    } catch (t: Throwable) {
                        log("Timeout/crash for $pkg: ${t.message}")
                        toast(app, "timeout")
                        FAIL_OPEN
                    }

                    if (!allow) {
                        log("Blocked: $pkg")
                        toast(app, "Blocked")
                        Thread.sleep(300)
                        Process.killProcess(Process.myPid())
                        System.exit(0)
                    } else {
                        log("Allowed: $pkg")
                    }
                }
            }
        )
    }

    // ── Core logic ──────────────────────────────────────────────────────────

    private fun checkAllowed(ctx: Context, pkg: String): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Read cache
        val cachedAllowed = if (prefs.contains("${pkg}_allowed")) prefs.getBoolean("${pkg}_allowed", FAIL_OPEN) else null
        val cachedExpires = prefs.getLong("${pkg}_expires_at", 0L)
        val now = System.currentTimeMillis()

        // If cached allowed and not expired, return immediately
        if (cachedAllowed == true && now < cachedExpires) {
            log("Cache hit allowed for $pkg")
            return true
        }

        // If denied or no cache or expired: always call API (so admin approval is instant)
        val token = getOrCreateToken(prefs)
        log("Verifying $pkg, token=${token.take(8)}...")

        return try {
            val result = callApi(token, pkg)
            writeCache(prefs, pkg, result.allowed, result.expiresAt)
            toast(ctx, if (result.allowed) "allowed" else "denied")
            log("API result for $pkg: allowed=${result.allowed}")
            result.allowed
        } catch (t: Throwable) {
            log("API error for $pkg: ${t.javaClass.simpleName}: ${t.message}")
            toast(ctx, "err: ${t.message?.take(30)}")
            // On network error, use short TTL so we retry next launch
            writeCache(prefs, pkg, FAIL_OPEN, now + 30_000L)
            FAIL_OPEN
        }
    }

    // ── Network (java.net only, no OkHttp dependency) ───────────────────────

    private data class ApiResult(val allowed: Boolean, val expiresAt: Long)

    private fun callApi(deviceToken: String, packageName: String): ApiResult {
        val body = """{"device_token":"$deviceToken","package_name":"$packageName"}"""
        val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }

        try {
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(body) }

            val code = conn.responseCode
            if (code != 200) throw RuntimeException("HTTP $code")

            val respBody = BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8))
                .use { it.readText() }

            // Minimal JSON parsing without org.json (which may not exist in target process)
            val allowed = respBody.contains("\"allowed\":true")
            val expiresAt = extractLong(respBody, "expires_at")
                ?: (System.currentTimeMillis() + CACHE_TTL_MS)

            if (ENFORCE_SIGNATURE) {
                val signature = extractString(respBody, "signature") ?: ""
                if (signature.isBlank()) throw RuntimeException("Missing signature")
                val payload = "$deviceToken|$packageName|${if (allowed) "1" else "0"}|$expiresAt"
                val expected = hmacSha256(payload, SIGNING_KEY)
                if (!constantTimeEquals(expected, signature)) throw RuntimeException("HMAC mismatch")
            }

            return ApiResult(allowed, expiresAt)
        } finally {
            conn.disconnect()
        }
    }

    // ── Crypto ──────────────────────────────────────────────────────────────

    private fun hmacSha256(data: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        return MessageDigest.isEqual(
            a.toByteArray(StandardCharsets.UTF_8),
            b.toByteArray(StandardCharsets.UTF_8)
        )
    }

    // ── Minimal JSON helpers (no dependency on org.json) ────────────────────

    private fun extractLong(json: String, key: String): Long? {
        val pattern = "\"$key\"\\s*:\\s*(-?\\d+)".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun extractString(json: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*\"([^\"]+)\"".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
    }

    // ── Cache ───────────────────────────────────────────────────────────────

    private fun getOrCreateToken(prefs: android.content.SharedPreferences): String {
        val current = prefs.getString(KEY_DEVICE_TOKEN, null)
        if (!current.isNullOrBlank()) return current
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_TOKEN, generated).apply()
        return generated
    }

    private fun writeCache(
        prefs: android.content.SharedPreferences,
        pkg: String,
        allowed: Boolean,
        expiresAt: Long
    ) {
        prefs.edit()
            .putBoolean("${pkg}_allowed", allowed)
            .putLong("${pkg}_expires_at", expiresAt)
            .apply()
    }

    // ── Util ────────────────────────────────────────────────────────────────

    private fun shouldSkip(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return true
        if (SKIP_EXACT.contains(pkg)) return true
        return SKIP_PREFIX.any { pkg.startsWith(it) }
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        runCatching {
            Class.forName("de.robv.android.xposed.XposedBridge")
                .getMethod("log", String::class.java)
                .invoke(null, "$TAG: $msg")
        }
    }

    private fun toast(ctx: Context, msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx.applicationContext, "[DG] $msg", Toast.LENGTH_SHORT).show()
        }
    }
}
