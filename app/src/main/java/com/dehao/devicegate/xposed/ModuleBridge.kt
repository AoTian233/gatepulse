package com.dehao.devicegate.xposed

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Called from a BACKGROUND thread (UniversalGateHook wraps the call).
 * So it is safe to do synchronous IPC and network here.
 */
object ModuleBridge {
    private val providerUri: Uri = Uri.parse("content://${HookConstants.PROVIDER_AUTHORITY}")

    fun check(context: Context, packageName: String): Boolean {
        // 1. Try IPC to Provider (runs in module process, has INTERNET)
        val ipcResult = tryProviderCheck(context, packageName)
        if (ipcResult != null) {
            toast(context, "IPC ok: allowed=$ipcResult")
            return ipcResult
        }

        // 2. Provider unavailable — direct API call (we're already on a background thread)
        toast(context, "IPC fail, direct API...")
        return tryDirectCheck(context, packageName)
    }

    private fun toast(context: Context, msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, "[DeviceGate] $msg", Toast.LENGTH_LONG).show()
        }
    }

    private fun tryProviderCheck(context: Context, packageName: String): Boolean? {
        return try {
            val extras = Bundle().apply { putString("package_name", packageName) }
            val result: Bundle? = context.contentResolver.call(providerUri, "check", null, extras)
            if (result == null) {
                AppLog.e("Provider returned null for $packageName")
                return null
            }
            val allowed = result.getBoolean("allowed", HookConstants.FAIL_OPEN)
            val error = result.getString("error")
            val token = result.getString("device_token")
            if (!error.isNullOrBlank()) {
                AppLog.e("Provider error: $error")
                toast(context, "Provider err: $error")
            }
            if (!token.isNullOrBlank()) AppLog.i("Token for $packageName: $token")
            allowed
        } catch (t: Throwable) {
            AppLog.e("Provider IPC crash: ${t.javaClass.simpleName}: ${t.message}")
            toast(context, "IPC: ${t.javaClass.simpleName}: ${t.message?.take(50)}")
            null
        }
    }

    private fun tryDirectCheck(context: Context, packageName: String): Boolean {
        return try {
            // Check cache first
            val cached = CacheManager.read(context, packageName)
            if (cached != null && cached.allowed && !cached.isExpired(System.currentTimeMillis())) {
                toast(context, "cache: allowed")
                return true
            }

            val token = CacheManager.getOrCreateDeviceToken(context)
            AppLog.i("Direct verify for $packageName, token=$token")
            val result = ApiClient.verify(token, packageName)
            CacheManager.write(context, packageName, result.allowed, result.expiresAtMs)
            toast(context, "API: allowed=${result.allowed}")
            result.allowed
        } catch (e: ApiError.Network) {
            AppLog.e("Network error for $packageName: ${e.message}")
            toast(context, "net err: ${e.message?.take(50)}")
            val shortTtl = System.currentTimeMillis() + 30_000L
            CacheManager.write(context, packageName, HookConstants.FAIL_OPEN, shortTtl)
            HookConstants.FAIL_OPEN
        } catch (t: Throwable) {
            AppLog.e("Direct check failed for $packageName: ${t.message}")
            toast(context, "err: ${t.javaClass.simpleName}: ${t.message?.take(40)}")
            HookConstants.FAIL_OPEN
        }
    }
}
