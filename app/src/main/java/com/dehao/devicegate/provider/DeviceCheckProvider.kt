package com.dehao.devicegate.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.dehao.devicegate.xposed.AppLog
import com.dehao.devicegate.xposed.CacheManager
import com.dehao.devicegate.xposed.DeviceVerifier

class DeviceCheckProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return Bundle().apply { putBoolean("allowed", false) }
        if (method != "check") return Bundle().apply { putBoolean("allowed", false) }

        val packageName = extras?.getString("package_name").orEmpty().trim()
        if (packageName.isBlank()) {
            return Bundle().apply {
                putBoolean("allowed", false)
                putString("error", "package_name is required")
            }
        }

        return try {
            val allowed = DeviceVerifier.shouldAllowNow(ctx, packageName)
            val token = CacheManager.getOrCreateDeviceToken(ctx)
            AppLog.i("Provider check for $packageName result=$allowed")
            Bundle().apply {
                putBoolean("allowed", allowed)
                putString("device_token", token)
            }
        } catch (t: Throwable) {
            AppLog.e("Provider crash for $packageName: ${t.message}", t)
            Bundle().apply {
                putBoolean("allowed", false)
                putString("error", "provider_crash: ${t.message}")
            }
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
