package com.lineageos.launcherenhance;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageItemInfo;
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
 * Launcher3Customizer 1.0.1 performs icon-pack substitution in Launcher3's icon-provider/cache
 * pipeline rather than repainting BubbleTextView after binding. This port keeps that architecture,
 * but adapts it to LOS23.2's current LauncherIconProviderImpl and LauncherModel APIs.
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
        safe("provider icon load", () -> hookProviderIconLoad(lpparam.classLoader));
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

    /**
     * LOS23.2-native insertion point.
     *
     * IconProvider's private getIcon(...) handles dynamic Calendar/Clock first and only then calls
     * LauncherIconProviderImpl.loadPackageIcon(...). Hooking loadPackageIcon therefore replaces
     * normal application/activity icons before IconCache creates BitmapInfo while leaving the ROM's
     * native dynamic Calendar/Clock path intact. The normal themed-icon post-processing also remains
     * in IconProvider after this method returns.
     *
     * A superclass getIcon hook is retained only as a fail-soft fallback for ROM variants that do
     * not ship LauncherIconProviderImpl.
     */
    private static void hookProviderIconLoad(ClassLoader cl) {
        Class<?> impl = XposedHelpers.findClassIfExists(
                "com.android.launcher3.icons.LauncherIconProviderImpl", cl);
        if (impl != null) {
            XposedHelpers.findAndHookMethod(impl, "loadPackageIcon",
                    PackageItemInfo.class, ApplicationInfo.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Context context = sContext;
                                if (context == null) return;
                                String pack = readPack(context);
                                if (pack.isEmpty()) return;

                                PackageItemInfo info = (PackageItemInfo) param.args[0];
                                ComponentName component = componentOf(info);
                                if (component == null) return;

                                Drawable drawable = IconPackLoader.getProviderDrawable(
                                        context, pack, component);
                                if (drawable != null) param.setResult(drawable);
                            } catch (Throwable t) {
                                log("LauncherIconProviderImpl.loadPackageIcon fail-soft: " + t);
                            }
                        }
                    });
            log("LOS23.2 LauncherIconProviderImpl.loadPackageIcon replacement registered");
            return;
        }

        Class<?> provider = XposedHelpers.findClassIfExists(
                "com.android.launcher3.icons.IconProvider", cl);
        if (provider == null) {
            log("IconProvider missing; icon-pack provider hook skipped");
            return;
        }
        XposedHelpers.findAndHookMethod(provider, "getIcon",
                ComponentInfo.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Context context = sContext;
                            if (context == null) return;
                            String pack = readPack(context);
                            if (pack.isEmpty()) return;
                            ComponentName component = componentOf((PackageItemInfo) param.args[0]);
                            if (component == null) return;
                            Drawable drawable = IconPackLoader.getProviderDrawable(
                                    context, pack, component);
                            if (drawable != null) param.setResult(drawable);
                        } catch (Throwable t) {
                            log("IconProvider.getIcon fallback fail-soft: " + t);
                        }
                    }
                });
        log("IconProvider.getIcon compatibility fallback registered");
    }

    private static ComponentName componentOf(PackageItemInfo info) {
        if (info == null || info.packageName == null || info.packageName.isEmpty()
                || info.name == null || info.name.isEmpty()) return null;
        String className = info.name;
        if (className.charAt(0) == '.') className = info.packageName + className;
        return new ComponentName(info.packageName, className);
    }

    /**
     * LauncherActivityCachingLogic stores IconProvider.getStateForApp() as a freshness id. Adding
     * the selected icon-pack package makes persistent IconCache rows stale automatically whenever
     * the pack changes or is disabled.
     */
    private static void hookFreshnessState(ClassLoader cl) {
        Class<?> provider = XposedHelpers.findClassIfExists(
                "com.android.launcher3.icons.IconProvider", cl);
        if (provider == null) return;
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
     * Use the same refresh semantics LOS23.2 itself uses when its IconProcessorPlugin changes:
     * IconCache.clearMemoryCache() followed by LauncherModel.reloadIfActive() on the icon/model
     * worker. No process restart, SIGKILL, root shell, or startup-time forceReload is involved.
     */
    private static void refreshLauncherIcons(Context context) {
        try {
            ClassLoader cl = sClassLoader;
            if (cl == null) return;
            Class<?> appStateClass = XposedHelpers.findClass(
                    "com.android.launcher3.LauncherAppState", cl);
            Object appState = XposedHelpers.callStaticMethod(appStateClass, "getInstance", context);
            if (appState == null) return;

            Object iconCache = XposedHelpers.callMethod(appState, "getIconCache");
            Object model = XposedHelpers.callMethod(appState, "getModel");
            if (iconCache == null || model == null) return;

            Runnable refresh = () -> {
                try {
                    XposedHelpers.callMethod(iconCache, "clearMemoryCache");
                    log("IconCache.clearMemoryCache completed");
                } catch (Throwable t) {
                    log("clearMemoryCache fail-soft: " + t);
                }
                try {
                    XposedHelpers.callMethod(model, "reloadIfActive");
                    log("LauncherModel.reloadIfActive requested");
                } catch (Throwable t) {
                    log("reloadIfActive fail-soft: " + t);
                }
            };

            try {
                Object worker = XposedHelpers.getObjectField(iconCache, "workerHandler");
                if (worker instanceof Handler) ((Handler) worker).post(refresh);
                else refresh.run();
            } catch (Throwable t) {
                refresh.run();
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
