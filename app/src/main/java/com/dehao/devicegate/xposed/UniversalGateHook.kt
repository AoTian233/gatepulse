package com.dehao.devicegate.xposed

import android.app.Application
import android.os.Process
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class UniversalGateHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (PackageFilter.shouldSkip(lpparam.packageName)) {
            return
        }

        // Hook Application.onCreate instead of Instrumentation.callApplicationOnCreate.
        // The Instrumentation method is patched by HyperOS/MIUI and causes crashes when hooked.
        // Application.onCreate is called immediately after and is stable across all ROM variants.
        XposedHelpers.findAndHookMethod(
            Application::class.java,
            "onCreate",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val app = param.thisObject as? Application ?: return
                    val pkg = app.packageName
                    if (PackageFilter.shouldSkip(pkg)) {
                        return
                    }

                    val allow = ModuleBridge.check(app, pkg)
                    if (!allow) {
                        AppLog.i("Blocked package: $pkg, killing process.")
                        Process.killProcess(Process.myPid())
                        System.exit(0)
                    } else {
                        AppLog.i("Allowed package: $pkg")
                    }
                }
            }
        )
    }
}
