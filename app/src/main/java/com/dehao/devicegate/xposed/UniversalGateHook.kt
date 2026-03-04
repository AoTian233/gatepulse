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

        XposedHelpers.findAndHookMethod(
            "android.app.Instrumentation",
            null,
            "callApplicationOnCreate",
            Application::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val app = param.args.firstOrNull() as? Application ?: return
                    val pkg = app.packageName
                    if (PackageFilter.shouldSkip(pkg)) {
                        return
                    }

                    val allow = DeviceVerifier.shouldAllowNow(app, pkg)
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
