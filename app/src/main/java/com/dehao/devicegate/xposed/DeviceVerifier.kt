package com.dehao.devicegate.xposed

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object DeviceVerifier {
    private val executor = Executors.newSingleThreadExecutor()
    private val refreshingByPackage = ConcurrentHashMap<String, AtomicBoolean>()
    private val tokenLoggedByPackage = ConcurrentHashMap<String, AtomicBoolean>()

    fun shouldAllowNow(context: Context, packageName: String): Boolean {
        val now = System.currentTimeMillis()
        val cached = CacheManager.read(context, packageName)
        val appContext = context.applicationContext

        if (cached == null || !cached.allowed) {
            return verifySynchronously(appContext, packageName)
        }

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
            AppLog.e("Network error for $packageName (transient): ${e.message}")
            val shortTtl = System.currentTimeMillis() + 30_000L
            CacheManager.write(context, packageName, HookConstants.FAIL_OPEN, shortTtl)
            HookConstants.FAIL_OPEN
        } catch (e: ApiError.InvalidSignature) {
            AppLog.e("Signature error for $packageName: ${e.message}")
            val ttl = System.currentTimeMillis() + HookConstants.DEFAULT_CACHE_TTL_MS
            CacheManager.write(context, packageName, false, ttl)
            false
        } catch (t: Throwable) {
            AppLog.e("Verify failed for $packageName", t)
            val ttl = System.currentTimeMillis() + HookConstants.DEFAULT_CACHE_TTL_MS
            CacheManager.write(context, packageName, HookConstants.FAIL_OPEN, ttl)
            HookConstants.FAIL_OPEN
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
