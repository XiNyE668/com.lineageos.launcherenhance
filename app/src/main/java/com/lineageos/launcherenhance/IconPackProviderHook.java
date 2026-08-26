package com.lineageos.launcherenhance;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Android 16 / LineageOS 23.2 icon-pack bridge.
 *
 * This intentionally follows Launcher3Customizer 1.0.1's important design choice: replace the
 * icon at Launcher3's IconProvider layer, before IconCache turns the Drawable into BitmapInfo.
 * It does NOT replace BubbleTextView drawables after binding and it never restarts Trebuchet.
 */
public final class IconPackProviderHook implements IXposedHookLoadPackage {
    private static final String TARGET = "com.android.launcher3";
    private static final String TAG = "LauncherHub/IconPackProvider";
    private static final long CONFIG_TTL_MS = 750L;

    private static volatile Context sContext;
    private static volatile ClassLoader sClassLoader;
    private static volatile String sPack = "";
    private static volatile long sPackReadAt;
    private static volatile boolean sObserverRegistered;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET.equals(lpparam.packageName)) return;
        sClassLoader = lpparam.classLoader;

        safe("Application.attach", IconPackProviderHook::hookApplicationAttach);
        safe("IconProvider.getIcon", () -> hookIconProvider(lpparam.classLoader));
        safe("IconProvider.getStateForApp", () -> hookFreshnessState(lpparam.classLoader));
    }

    private static void hookApplicationAttach() {
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Context context = (Context) param.args[0];
                        if (context == null || !TARGET.equals(context.getPackageName())) return;
                        sContext = context;
                        invalidateConfig();
                        readPack(context);
                        registerObserver(context);
                    }
                });
    }

    private static void hookIconProvider(ClassLoader cl) {
        Class<?> provider = XposedHelpers.findClass(
                "com.android.launcher3.icons.IconProvider", cl);

        XposedHelpers.findAndHookMethod(provider, "getIcon",
                ComponentInfo.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Context context = sContext;
                            if (context == null) return;
                            String pack = readPack(context);
                            if (pack.isEmpty()) return;

                            ComponentInfo info = (ComponentInfo) param.args[0];
                            if (info == null || info.packageName == null || info.name == null) return;
                            ComponentName component = new ComponentName(info.packageName, info.name);
                            Drawable drawable = IconPackLoader.getProviderDrawable(
                                    context, pack, component);
                            if (drawable != null) param.setResult(drawable);
                        } catch (Throwable t) {
                            log("getIcon fail-soft: " + t);
                        }
                    }
                });

        log("provider-layer icon replacement registered");
    }

    /**
     * LauncherActivityCachingLogic persists IconProvider.getStateForApp() as the icon freshness id.
     * Include the selected icon-pack package so old persistent cache rows become stale naturally
     * when the user changes or disables the pack.
     */
    private static void hookFreshnessState(ClassLoader cl) {
        Class<?> provider = XposedHelpers.findClass(
                "com.android.launcher3.icons.IconProvider", cl);
        XposedHelpers.findAndHookMethod(provider, "getStateForApp", ApplicationInfo.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Context context = sContext;
                            if (context == null) return;
                            String pack = readPack(context);
                            Object current = param.getResult();
                            String state = current == null ? "" : current.toString();
                            param.setResult(state + "|launcherhub-pack=" + pack);
                        } catch (Throwable t) {
                            log("freshness state fail-soft: " + t);
                        }
                    }
                });
    }

    private static void registerObserver(Context context) {
        if (sObserverRegistered) return;
        synchronized (IconPackProviderHook.class) {
            if (sObserverRegistered) return;
            try {
                context.getContentResolver().registerContentObserver(
                        ConfigKeys.URI, true,
                        new ContentObserver(new Handler(Looper.getMainLooper())) {
                            @Override
                            public void onChange(boolean selfChange) {
                                Context c = sContext;
                                if (c == null) return;
                                String before = sPack;
                                invalidateConfig();
                                String after = readPack(c);
                                IconPackLoader.invalidate();
                                if (!before.equals(after)) {
                                    log("icon pack changed: " + before + " -> " + after);
                                    refreshLauncherIcons(c);
                                }
                            }
                        });
                sObserverRegistered = true;
            } catch (Throwable t) {
                log("observer registration failed: " + t);
            }
        }
    }

    private static String readPack(Context context) {
        long now = SystemClock.uptimeMillis();
        if (now - sPackReadAt < CONFIG_TTL_MS) return sPack;
        synchronized (IconPackProviderHook.class) {
            now = SystemClock.uptimeMillis();
            if (now - sPackReadAt < CONFIG_TTL_MS) return sPack;
            try {
                Bundle b = context.getContentResolver().call(
                        ConfigKeys.URI, ConfigKeys.METHOD_GET_CONFIG, null, null);
                String value = b == null ? "" : b.getString(ConfigKeys.ICON_PACK, "");
                sPack = value == null ? "" : value;
            } catch (Throwable t) {
                log("config read failed: " + t);
            }
            sPackReadAt = now;
            return sPack;
        }
    }

    private static void invalidateConfig() {
        sPackReadAt = 0L;
    }

    /**
     * LOS23.2-native refresh path:
     * 1. clear only IconCache's in-memory map on its own worker handler;
     * 2. force LauncherModel reload on the main thread.
     * Persistent DB rows are invalidated by the freshness-id hook above.
     * No process kill/restart is used.
     */
    private static void refreshLauncherIcons(Context context) {
        try {
            ClassLoader cl = sClassLoader;
            if (cl == null) return;
            Class<?> appStateClass = XposedHelpers.findClass(
                    "com.android.launcher3.LauncherAppState", cl);
            Object appState = XposedHelpers.callStaticMethod(
                    appStateClass, "getInstance", context);
            if (appState == null) return;

            Object iconCache = XposedHelpers.callMethod(appState, "getIconCache");
            Object model = XposedHelpers.callMethod(appState, "getModel");
            Object worker = XposedHelpers.getObjectField(iconCache, "workerHandler");

            Runnable reloadModel = () -> {
                try {
                    XposedHelpers.callMethod(model, "forceReload");
                    log("LauncherModel.forceReload requested");
                } catch (Throwable t) {
                    log("forceReload fail-soft: " + t);
                }
            };

            if (worker instanceof Handler) {
                ((Handler) worker).post(() -> {
                    try {
                        XposedHelpers.callMethod(iconCache, "clearMemoryCache");
                        log("IconCache.clearMemoryCache completed");
                    } catch (Throwable t) {
                        log("clearMemoryCache fail-soft: " + t);
                    }
                    new Handler(Looper.getMainLooper()).post(reloadModel);
                });
            } else {
                new Handler(Looper.getMainLooper()).post(reloadModel);
            }
        } catch (Throwable t) {
            log("refresh fail-soft: " + t);
        }
    }

    private interface ThrowingRunnable { void run() throws Throwable; }

    private static void safe(String name, ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable t) {
            log(name + " hook skipped: " + t);
            XposedBridge.log(t);
        }
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }
}
