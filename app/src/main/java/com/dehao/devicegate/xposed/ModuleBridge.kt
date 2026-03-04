package com.dehao.devicegate.xposed

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.dehao.devicegate.BuildConfig
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object ModuleBridge {
    private val providerUri: Uri = Uri.parse("content://${BuildConfig.APPLICATION_ID}.checkprovider")
    private val executor = Executors.newSingleThreadExecutor()

    fun check(context: Context, packageName: String): Boolean {
        // First try: IPC via ContentProvider (runs in module process, has INTERNET)
        val ipcResult = tryProviderCheck(context, packageName)
        if (ipcResult != null) {
            return ipcResult
        }

        // Fallback: direct API call — must run on background thread
        toast(context, "IPC unavailable, trying direct API...")
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
                AppLog.e("ModuleBridge: provider returned null for $packageName")
                return null
            }
            val allowed = result.getBoolean("allowed", BuildConfig.FAIL_OPEN)
            val error = result.getString("error")
            val token = result.getString("device_token")
            if (!error.isNullOrBlank()) AppLog.e("ModuleBridge provider error: $error")
            if (!token.isNullOrBlank()) AppLog.i("Bridge token for $packageName: $token")
            toast(context, "IPC ok: allowed=$allowed token=${token?.take(8)}")
            allowed
        } catch (t: Throwable) {
            AppLog.e("ModuleBridge IPC crashed: ${t.javaClass.simpleName}: ${t.message}")
            toast(context, "IPC crash: ${t.javaClass.simpleName}: ${t.message?.take(40)}")
            null
        }
    }

    private fun tryDirectCheckBlocking(context: Context, packageName: String): Boolean {
        // Network must happen on a background thread — submit and block with timeout
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
                toast(context, "API ok: allowed=${result.allowed} token=${token.take(8)}")
                result.allowed
            } catch (e: ApiError.Network) {
                toast(context, "network error: ${e.message?.take(40)}")
                val shortTtl = System.currentTimeMillis() + 30_000L
                CacheManager.write(context, packageName, BuildConfig.FAIL_OPEN, shortTtl)
                BuildConfig.FAIL_OPEN
            } catch (t: Throwable) {
                toast(context, "direct fail: ${t.javaClass.simpleName}: ${t.message?.take(30)}")
                AppLog.e("ModuleBridge direct failed: ${t.message}")
                BuildConfig.FAIL_OPEN
            }
        }
        return try {
            future.get(20, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            toast(context, "direct timeout: ${t.javaClass.simpleName}")
            BuildConfig.FAIL_OPEN
        }
    }
}
