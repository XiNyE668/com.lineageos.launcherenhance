package com.lineageos.launcherenhance;

import android.os.Process;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * First legacy Xposed entry point. Keep this class intentionally tiny: it guarantees that the
 * Home-settings integration is installed in every Trebuchet process before the feature-specific
 * hook classes are registered.
 */
public final class LauncherBootstrapHook implements IXposedHookLoadPackage {
    private static final String TARGET = "com.android.launcher3";
    private static final String TAG = "LauncherHub/Bootstrap";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET.equals(lpparam.packageName)) return;
        String message = "bootstrap pid=" + Process.myPid() + " process=" + lpparam.processName;
        Log.i(TAG, message);
        XposedBridge.log(TAG + ": " + message);
        LauncherIntegrationHook.install(lpparam.classLoader);
    }
}
