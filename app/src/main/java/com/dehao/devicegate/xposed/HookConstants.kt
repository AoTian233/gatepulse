package com.dehao.devicegate.xposed

/**
 * Hardcoded constants for use in the Xposed hook process.
 *
 * CRITICAL: Xposed hook code runs inside the TARGET app's process, NOT the module's process.
 * Therefore com.dehao.devicegate.BuildConfig does NOT exist in the target process classloader.
 * Any reference to BuildConfig will throw NoClassDefFoundError or resolve to the wrong class.
 *
 * All configuration values must be hardcoded here.
 */
object HookConstants {
    const val MODULE_PACKAGE_ID = "com.dehao.devicegate"
    const val PROVIDER_AUTHORITY = "com.dehao.devicegate.checkprovider"

    const val API_BASE_URL = "https://gatepulse-omega.vercel.app/api/device/check"
    const val RESPONSE_SIGNING_KEY = "b2b1fe307bf15758d31c58748fbd9886bb7713c62476e6f51841aaaa3636f9ed"
    const val ENFORCE_SIGNATURE = true
    const val FAIL_OPEN = false
    const val DEFAULT_CACHE_TTL_MS = 900_000L
    const val ENABLE_LOG = true
}
