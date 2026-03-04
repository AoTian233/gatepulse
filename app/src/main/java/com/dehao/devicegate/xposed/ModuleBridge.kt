package com.dehao.devicegate.xposed

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object ModuleBridge {
    private val providerUri: Uri = Uri.parse("content://${HookConstants.PROVIDER_AUTHORITY}")
    private val executor = Executors.newSingleThreadExecutor()

    fun check(context: Context, packageName: String): Boolean {
        // Try IPC first (provider runs in module process which has INTERNET permission)
        val ipcResult = tryProviderCheck(context, packageName)
        if (ipcResult != null) {
            toast(context, "IPC ok: allowed=$ipcResult")
            return ipcResult
        }

        // Provider not reachable — fall back to direct API on background thread
        toast(context, "IPC fail, trying direct API...")
        return tryDirectCheckBlocking(context, packageName)
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
            if (!error.isNullOrBlank()) AppLog.e("Provider error: $error")
            if (!token.isNullOrBlank()) AppLog.i("Token for $packageName: $token")
            allowed
        } catch (t: Throwable) {
            AppLog.e("Provider IPC crash for $packageName: ${t.javaClass.simpleName}: ${t.message}")
            toast(context, "IPC err: ${t.javaClass.simpleName}: ${t.message?.take(50)}")
            null
        }
    }

    private fun tryDirectCheckBlocking(context: Context, packageName: String): Boolean {
        val future = executor.submit<Boolean> {
            try {
                val cached = CacheManager.read(context, packageName)
                if (cached != null && cached.allowed && !cached.isExpired(System.currentTimeMillis())) {
                    toast(context, "cache hit: allowed")
                    return@submit true
                }
                val token = CacheManager.getOrCreateDeviceToken(context)
                val result = ApiClient.verify(token, packageName)
                CacheManager.write(context, packageName, result.allowed, result.expiresAtMs)
                toast(context, "API ok: allowed=${result.allowed}")
                result.allowed
            } catch (e: ApiError.Network) {
                toast(context, "net err: ${e.message?.take(50)}")
                val shortTtl = System.currentTimeMillis() + 30_000L
                CacheManager.write(context, packageName, HookConstants.FAIL_OPEN, shortTtl)
                HookConstants.FAIL_OPEN
            } catch (t: Throwable) {
                toast(context, "api err: ${t.javaClass.simpleName}: ${t.message?.take(40)}")
                HookConstants.FAIL_OPEN
            }
        }
        return try {
            future.get(20, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            toast(context, "timeout: ${t.javaClass.simpleName}")
            HookConstants.FAIL_OPEN
        }
    }
}
