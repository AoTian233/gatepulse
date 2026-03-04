package com.dehao.devicegate.xposed

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.dehao.devicegate.BuildConfig

object ModuleBridge {
    private val providerUri: Uri = Uri.parse("content://${BuildConfig.APPLICATION_ID}.checkprovider")

    fun check(context: Context, packageName: String): Boolean {
        return try {
            val extras = Bundle().apply {
                putString("package_name", packageName)
            }
            val result = context.contentResolver.call(providerUri, "check", null, extras)
            val allowed = result?.getBoolean("allowed", BuildConfig.FAIL_OPEN) ?: BuildConfig.FAIL_OPEN
            val token = result?.getString("device_token")
            if (!token.isNullOrBlank()) {
                AppLog.i("Bridge token for $packageName: $token")
            }
            allowed
        } catch (t: Throwable) {
            AppLog.e("Bridge check failed for $packageName", t)
            BuildConfig.FAIL_OPEN
        }
    }
}
