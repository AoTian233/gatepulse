package com.dehao.devicegate.xposed

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.dehao.devicegate.BuildConfig

object ModuleBridge {
    private val providerUri: Uri = Uri.parse("content://${BuildConfig.APPLICATION_ID}.checkprovider")

    fun check(context: Context, packageName: String): Boolean {
        // First try: IPC via ContentProvider (preferred — module process does the network call)
        val ipcResult = tryProviderCheck(context, packageName)
        if (ipcResult != null) {
            return ipcResult
        }

        // Fallback: Provider not reachable (module process not running).
        // Call the API directly from the hooked process.
        AppLog.e("ModuleBridge: provider null for $packageName, falling back to direct API call")
        return tryDirectCheck(context, packageName)
    }

    private fun tryProviderCheck(context: Context, packageName: String): Boolean? {
        return try {
            val extras = Bundle().apply { putString("package_name", packageName) }
            val result: Bundle? = context.contentResolver.call(providerUri, "check", null, extras)
            if (result == null) {
                AppLog.e("ModuleBridge: provider returned null for $packageName")
                return null
            }
            val allowed = result.getBoolean("allowed", BuildConfig.FAIL_OPEN)
            val error = result.getString("error")
            val token = result.getString("device_token")
            if (!error.isNullOrBlank()) AppLog.e("ModuleBridge provider error: $error")
            if (!token.isNullOrBlank()) AppLog.i("Bridge token for $packageName: $token")
            AppLog.i("ModuleBridge IPC result for $packageName: allowed=$allowed")
            allowed
        } catch (t: Throwable) {
            AppLog.e("ModuleBridge IPC crashed for $packageName: ${t.message}")
            null
        }
    }

    private fun tryDirectCheck(context: Context, packageName: String): Boolean {
        return try {
            val token = CacheManager.getOrCreateDeviceToken(context)
            AppLog.i("ModuleBridge direct check token for $packageName: $token")

            // Check denied cache first — if cached allowed, return immediately
            val cached = CacheManager.read(context, packageName)
            if (cached != null && cached.allowed && !cached.isExpired(System.currentTimeMillis())) {
                AppLog.i("ModuleBridge direct: cache hit allowed for $packageName")
                return true
            }

            val result = ApiClient.verify(token, packageName)
            CacheManager.write(context, packageName, result.allowed, result.expiresAtMs)
            AppLog.i("ModuleBridge direct API result for $packageName: allowed=${result.allowed}")
            result.allowed
        } catch (e: ApiError.Network) {
            AppLog.e("ModuleBridge direct network error for $packageName: ${e.message}")
            // Network transient — write short TTL so we retry on next launch
            val shortTtl = System.currentTimeMillis() + 30_000L
            CacheManager.write(context, packageName, BuildConfig.FAIL_OPEN, shortTtl)
            BuildConfig.FAIL_OPEN
        } catch (t: Throwable) {
            AppLog.e("ModuleBridge direct failed for $packageName: ${t.message}")
            BuildConfig.FAIL_OPEN
        }
    }
}
