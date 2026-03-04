package com.dehao.devicegate.xposed

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.dehao.devicegate.BuildConfig

object ModuleBridge {
    // Authority must match AndroidManifest provider android:authorities
    private val providerUri: Uri = Uri.parse("content://${BuildConfig.APPLICATION_ID}.checkprovider")

    fun check(context: Context, packageName: String): Boolean {
        return try {
            val extras = Bundle().apply {
                putString("package_name", packageName)
            }

            // call() returns null if the provider process is not running or the authority is wrong.
            val result: Bundle? = context.contentResolver.call(providerUri, "check", null, extras)

            if (result == null) {
                // Provider not reachable — module process not running.
                // Log clearly so it shows in XposedBridge log.
                AppLog.e(
                    "ModuleBridge: provider returned null for $packageName. " +
                    "Is the module app process alive? Falling back to FAIL_OPEN=${BuildConfig.FAIL_OPEN}"
                )
                return BuildConfig.FAIL_OPEN
            }

            val allowed = result.getBoolean("allowed", BuildConfig.FAIL_OPEN)
            val error = result.getString("error")
            val token = result.getString("device_token")

            if (!error.isNullOrBlank()) {
                AppLog.e("ModuleBridge provider error for $packageName: $error")
            }
            if (!token.isNullOrBlank()) {
                AppLog.i("Bridge token for $packageName: $token")
            }
            AppLog.i("ModuleBridge result for $packageName: allowed=$allowed")
            allowed
        } catch (t: Throwable) {
            AppLog.e("ModuleBridge.check crashed for $packageName: ${t.javaClass.simpleName}: ${t.message}", t)
            BuildConfig.FAIL_OPEN
        }
    }
}
