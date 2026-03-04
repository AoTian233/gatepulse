package com.dehao.devicegate.xposed

object PackageFilter {
    private val exactBlocked = setOf(
        "android",
        "com.android.systemui",
        "com.google.android.gms",
        HookConstants.MODULE_PACKAGE_ID
    )

    private val prefixBlocked = listOf(
        "com.android.",
        "androidx.",
        "com.qualcomm.",
        "com.miui.",
        "com.xiaomi.",
        "com.samsung.",
        "com.coloros.",
        "com.oplus.",
        "com.vivo.",
        "com.huawei.",
        "com.honor."
    )

    fun shouldSkip(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return true
        if (exactBlocked.contains(packageName)) return true
        return prefixBlocked.any { packageName.startsWith(it) }
    }
}
