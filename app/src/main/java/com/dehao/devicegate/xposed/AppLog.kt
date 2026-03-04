package com.dehao.devicegate.xposed

import android.util.Log
import com.dehao.devicegate.BuildConfig
import de.robv.android.xposed.XposedBridge

object AppLog {
    private const val TAG = "DeviceGate"

    fun i(message: String) {
        if (!BuildConfig.ENABLE_LOG) return
        Log.i(TAG, message)
        XposedBridge.log("$TAG: $message")
    }

    fun e(message: String, t: Throwable? = null) {
        if (!BuildConfig.ENABLE_LOG) return
        Log.e(TAG, message, t)
        XposedBridge.log("$TAG: $message")
        if (t != null) {
            XposedBridge.log(t)
        }
    }
}
