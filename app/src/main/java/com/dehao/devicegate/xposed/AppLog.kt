package com.dehao.devicegate.xposed

import android.util.Log

object AppLog {
    private const val TAG = "DeviceGate"

    fun i(message: String) {
        if (!HookConstants.ENABLE_LOG) return
        Log.i(TAG, message)
        logToXposed("$TAG: $message")
    }

    fun e(message: String, t: Throwable? = null) {
        if (!HookConstants.ENABLE_LOG) return
        Log.e(TAG, message, t)
        logToXposed("$TAG: $message")
        if (t != null) logToXposedThrowable(t)
    }

    private fun logToXposed(message: String) {
        runCatching {
            val clz = Class.forName("de.robv.android.xposed.XposedBridge")
            clz.getMethod("log", String::class.java).invoke(null, message)
        }
    }

    private fun logToXposedThrowable(t: Throwable) {
        runCatching {
            val clz = Class.forName("de.robv.android.xposed.XposedBridge")
            clz.getMethod("log", Throwable::class.java).invoke(null, t)
        }
    }
}
