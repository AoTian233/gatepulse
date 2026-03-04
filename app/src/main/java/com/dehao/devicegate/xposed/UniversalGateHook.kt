package com.dehao.devicegate.xposed

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.widget.Toast
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class UniversalGateHook : IXposedHookLoadPackage {

    private val executor = Executors.newSingleThreadExecutor()

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (PackageFilter.shouldSkip(lpparam.packageName)) {
            return
        }

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

                    // Run the entire check on a background thread to avoid ANR.
                    // Block the main thread with a timeout — if the check takes too long,
                    // fall back to FAIL_OPEN.
                    val allow = try {
                        val future = executor.submit<Boolean> {
                            ModuleBridge.check(app, pkg)
                        }
                        future.get(25, TimeUnit.SECONDS)
                    } catch (t: Throwable) {
                        AppLog.e("Gate check timed out or crashed for $pkg: ${t.message}")
                        toast(app, "Gate timeout: ${t.javaClass.simpleName}")
                        HookConstants.FAIL_OPEN
                    }

                    if (!allow) {
                        AppLog.i("Blocked package: $pkg, killing process.")
                        toast(app, "Blocked: $pkg")
                        // Small delay so toast can show
                        Thread.sleep(500)
                        Process.killProcess(Process.myPid())
                        System.exit(0)
                    } else {
                        AppLog.i("Allowed package: $pkg")
                        toast(app, "Allowed: $pkg")
                    }
                }
            }
        )
    }

    private fun toast(app: Application, msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(app, "[DeviceGate] $msg", Toast.LENGTH_LONG).show()
        }
    }
}
