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

    fun shouldAllowNow(context: Context, packageName: String): Boolean {
        val now = System.currentTimeMillis()
        val cached = CacheManager.read(context, packageName)
        val appContext = context.applicationContext

        if (cached == null) {
            return verifySynchronouslyOnFirstSeen(appContext, packageName)
        }

        val allowNow = when {
            cached.isExpired(now) -> cached.allowed
            else -> cached.allowed
        }

        refreshIfNeededAsync(appContext, packageName, cached, now)
        return allowNow
    }

    private fun verifySynchronouslyOnFirstSeen(context: Context, packageName: String): Boolean {
        return try {
            val token = CacheManager.getOrCreateDeviceToken(context)
            logTokenOnce(packageName, token)
            val result = ApiClient.verify(token, packageName)
            CacheManager.write(context, packageName, result.allowed, result.expiresAtMs)
            AppLog.i("First verify for $packageName: allowed=${result.allowed}")
            result.allowed
        } catch (t: Throwable) {
            AppLog.e("First verify failed for $packageName", t)
            val fallbackExpires = System.currentTimeMillis() + BuildConfig.DEFAULT_CACHE_TTL_MS
            CacheManager.write(context, packageName, BuildConfig.FAIL_OPEN, fallbackExpires)
            BuildConfig.FAIL_OPEN
        }
    }

    private fun refreshIfNeededAsync(
        context: Context,
        packageName: String,
        cached: CacheRecord?,
        nowMs: Long
    ) {
        val needsRefresh = cached == null || cached.isExpired(nowMs)
        if (!needsRefresh) return

        val state = refreshingByPackage.getOrPut(packageName) { AtomicBoolean(false) }
        if (!state.compareAndSet(false, true)) return

        executor.execute {
            try {
                val token = CacheManager.getOrCreateDeviceToken(context)
                logTokenOnce(packageName, token)
                val result = ApiClient.verify(token, packageName)
                CacheManager.write(context, packageName, result.allowed, result.expiresAtMs)
                AppLog.i("Refreshed cache for $packageName: allowed=${result.allowed}")
            } catch (t: Throwable) {
                AppLog.e("Refresh failed for $packageName", t)
                if (cached == null) {
                    val fallbackExpires = System.currentTimeMillis() + BuildConfig.DEFAULT_CACHE_TTL_MS
                    CacheManager.write(context, packageName, BuildConfig.FAIL_OPEN, fallbackExpires)
                }
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
