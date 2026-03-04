package com.dehao.devicegate.xposed

import android.content.Context
import com.dehao.devicegate.BuildConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object DeviceVerifier {
    private val executor = Executors.newSingleThreadExecutor()
    private val refreshingByPackage = ConcurrentHashMap<String, AtomicBoolean>()
    private val tokenLoggedByPackage = ConcurrentHashMap<String, AtomicBoolean>()

    /**
     * Decision matrix:
     * - No cache              → synchronous verify (first-seen)
     * - Cache denied          → synchronous re-verify (so admin approval takes effect on next launch)
     * - Cache allowed+valid   → return true, async refresh if near expiry
     * - Cache allowed+expired → return stale true, async refresh (avoid blocking)
     */
    fun shouldAllowNow(context: Context, packageName: String): Boolean {
        val now = System.currentTimeMillis()
        val cached = CacheManager.read(context, packageName)
        val appContext = context.applicationContext

        // No cache or denied: always re-verify synchronously so approval is instant.
        if (cached == null || !cached.allowed) {
            return verifySynchronously(appContext, packageName)
        }

        // Allowed cache: serve it, and background-refresh if expired.
        if (cached.isExpired(now)) {
            refreshAsync(appContext, packageName)
        }
        return true
    }

    private fun verifySynchronously(context: Context, packageName: String): Boolean {
        return try {
            val token = CacheManager.getOrCreateDeviceToken(context)
            logTokenOnce(packageName, token)
            val result = ApiClient.verify(token, packageName)
            CacheManager.write(context, packageName, result.allowed, result.expiresAtMs)
            AppLog.i("Verified $packageName: allowed=${result.allowed}")
            result.allowed
        } catch (e: ApiError.Network) {
            // Transient network error — fail open so a cold-start timeout does NOT permanently block.
            // Cache with very short TTL so we retry quickly.
            AppLog.e("Network error for $packageName (transient): ${e.message}")
            val shortTtl = System.currentTimeMillis() + 30_000L // retry in 30 s
            CacheManager.write(context, packageName, BuildConfig.FAIL_OPEN, shortTtl)
            BuildConfig.FAIL_OPEN
        } catch (e: ApiError.InvalidSignature) {
            // Possible tampering or key mismatch — hard deny regardless of FAIL_OPEN.
            AppLog.e("Signature error for $packageName: ${e.message}")
            val ttl = System.currentTimeMillis() + BuildConfig.DEFAULT_CACHE_TTL_MS
            CacheManager.write(context, packageName, false, ttl)
            false
        } catch (t: Throwable) {
            AppLog.e("Verify failed for $packageName", t)
            val ttl = System.currentTimeMillis() + BuildConfig.DEFAULT_CACHE_TTL_MS
            CacheManager.write(context, packageName, BuildConfig.FAIL_OPEN, ttl)
            BuildConfig.FAIL_OPEN
        }
    }

    private fun refreshAsync(context: Context, packageName: String) {
        val state = refreshingByPackage.getOrPut(packageName) { AtomicBoolean(false) }
        if (!state.compareAndSet(false, true)) return

        executor.execute {
            try {
                val token = CacheManager.getOrCreateDeviceToken(context)
                logTokenOnce(packageName, token)
                val result = ApiClient.verify(token, packageName)
                CacheManager.write(context, packageName, result.allowed, result.expiresAtMs)
                AppLog.i("Async refresh for $packageName: allowed=${result.allowed}")
            } catch (t: Throwable) {
                AppLog.e("Async refresh failed for $packageName", t)
            } finally {
                state.set(false)
            }
        }
    }

    private fun logTokenOnce(packageName: String, token: String) {
        val logState = tokenLoggedByPackage.getOrPut(packageName) { AtomicBoolean(false) }
        if (logState.compareAndSet(false, true)) {
            AppLog.i("Device token for $packageName: $token")
        }
    }
}
