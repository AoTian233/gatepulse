package com.dehao.devicegate.xposed

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.dehao.devicegate.BuildConfig

object ModuleBridge {
    private val providerUri: Uri = Uri.parse("content://${BuildConfig.APPLICATION_ID}.checkprovider")

    fun check(context: Context, packageName: String): Boolean {
        // First try: IPC via ContentProvider
        val ipcResult = tryProviderCheck(context, packageName)
        if (ipcResult != null) {
            toast(context, "IPC ok: allowed=$ipcResult")
            return ipcResult
        }

        // Fallback: direct API call from hooked process
        toast(context, "IPC null, trying direct API...")
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
            AppLog.e("ModuleBridge IPC crashed for $packageName: ${t.javaClass.simpleName}: ${t.message}")
            toast(context, "IPC crash: ${t.javaClass.simpleName}")
            null
        }
    }

    private fun tryDirectCheck(context: Context, packageName: String): Boolean {
        return try {
            val token = CacheManager.getOrCreateDeviceToken(context)
            val cached = CacheManager.read(context, packageName)
            if (cached != null && cached.allowed && !cached.isExpired(System.currentTimeMillis())) {
                toast(context, "cache hit: allowed")
                return true
            }
            val result = ApiClient.verify(token, packageName)
            CacheManager.write(context, packageName, result.allowed, result.expiresAtMs)
            toast(context, "API result: allowed=${result.allowed}")
            result.allowed
        } catch (e: ApiError.Network) {
            AppLog.e("ModuleBridge direct network error for $packageName: ${e.message}")
            toast(context, "network error: ${e.message}")
            val shortTtl = System.currentTimeMillis() + 30_000L
            CacheManager.write(context, packageName, BuildConfig.FAIL_OPEN, shortTtl)
            BuildConfig.FAIL_OPEN
        } catch (t: Throwable) {
            AppLog.e("ModuleBridge direct failed for $packageName: ${t.message}")
            toast(context, "direct fail: ${t.javaClass.simpleName}")
            BuildConfig.FAIL_OPEN
        }
    }
}
