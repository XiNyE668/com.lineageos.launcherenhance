package com.lineageos.launcherenhance;

import android.content.Context;
import android.os.Bundle;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Minimal Trebuchet wallpaper-parallax control modeled on crDroid's Launcher3 implementation.
 *
 * When scrolling is disabled, only WallpaperOffsetInterpolator.wallpaperOffsetForScroll() is
 * short-circuited. Workspace scrolling and every other Launcher animation remain stock.
 */
public final class WallpaperScrollHook implements IXposedHookLoadPackage {
    private static final String TARGET = "com.android.launcher3";
    private static final String CLASS_NAME =
            "com.android.launcher3.util.WallpaperOffsetInterpolator";
    private static final String TAG = "LauncherHub/Wallpaper";

    // Requested default: keep the wallpaper fixed while Workspace pages move.
    private static volatile boolean sAllowWallpaperScrolling = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET.equals(lpparam.packageName)) return;
        try {
            Class<?> cls = XposedHelpers.findClass(CLASS_NAME, lpparam.classLoader);

            XposedBridge.hookAllConstructors(cls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object context = XposedHelpers.getObjectField(param.thisObject, "mContext");
                        if (context instanceof Context) refreshConfig((Context) context);
                    } catch (Throwable t) {
                        log("constructor config refresh skipped: " + t);
                    }
                }
            });

            XposedHelpers.findAndHookMethod(cls, "wallpaperOffsetForScroll",
                    int.class, int.class, int[].class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (sAllowWallpaperScrolling) return;
                            try {
                                int[] out = (int[]) param.args[2];
                                if (out == null || out.length < 2) return;

                                // Exact crDroid semantic: when parallax is disabled, pin to the
                                // default edge (LTR=0, RTL=1) and return before scroll math runs.
                                boolean rtl = XposedHelpers.getBooleanField(
                                        param.thisObject, "mIsRtl");
                                out[1] = 1;
                                out[0] = rtl ? 1 : 0;
                                param.setResult(null);
                            } catch (Throwable t) {
                                log("wallpaper offset interception failed: " + t);
                            }
                        }
                    });
            log("wallpaper scrolling hook installed");
        } catch (Throwable t) {
            log("hook registration skipped: " + t);
            XposedBridge.log(t);
        }
    }

    private static void refreshConfig(Context context) {
        try {
            Bundle b = context.getContentResolver().call(
                    ConfigKeys.URI, ConfigKeys.METHOD_GET_CONFIG, null, null);
            if (b != null) {
                sAllowWallpaperScrolling = b.getBoolean(
                        ConfigKeys.ALLOW_WALLPAPER_SCROLLING, false);
                log("allowWallpaperScrolling=" + sAllowWallpaperScrolling);
            }
        } catch (Throwable t) {
            // Keep the requested fixed-wallpaper default if the provider is temporarily unavailable.
            log("config read skipped: " + t);
        }
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }
}
