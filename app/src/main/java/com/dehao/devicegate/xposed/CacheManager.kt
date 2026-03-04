package com.dehao.devicegate.xposed

import android.content.Context
import com.dehao.devicegate.BuildConfig

data class CacheRecord(
    val allowed: Boolean,
    val expiresAtMs: Long
) {
    fun isExpired(nowMs: Long): Boolean = nowMs >= expiresAtMs
}

object CacheManager {
    private const val SP_NAME = "device_gate_cache"
    private const val KEY_DEVICE_TOKEN = "device_token"

    private fun sp(context: Context) = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    fun read(context: Context, packageName: String): CacheRecord? {
        val allowedKey = "${packageName}_allowed"
        val expiresKey = "${packageName}_expires_at"
        val prefs = sp(context)
        if (!prefs.contains(allowedKey) || !prefs.contains(expiresKey)) return null
        return CacheRecord(
            allowed = prefs.getBoolean(allowedKey, BuildConfig.FAIL_OPEN),
            expiresAtMs = prefs.getLong(expiresKey, 0L)
        )
    }

    fun write(context: Context, packageName: String, allowed: Boolean, expiresAtMs: Long) {
        val allowedKey = "${packageName}_allowed"
        val expiresKey = "${packageName}_expires_at"
        sp(context).edit()
            .putBoolean(allowedKey, allowed)
            .putLong(expiresKey, expiresAtMs)
            .apply()
    }

    /** Removes cached result for a specific package (e.g., for testing). */
    fun clearCache(context: Context, packageName: String) {
        sp(context).edit()
            .remove("${packageName}_allowed")
            .remove("${packageName}_expires_at")
            .apply()
    }

    fun getOrCreateDeviceToken(context: Context): String {
        val prefs = sp(context)
        val current = prefs.getString(KEY_DEVICE_TOKEN, null)
        if (!current.isNullOrBlank()) {
            return current
        }
        val generated = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_TOKEN, generated).apply()
        return generated
    }
}
