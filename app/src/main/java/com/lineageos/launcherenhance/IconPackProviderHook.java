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

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

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
 * while using use.apk's existing ConfigProvider instead of recreating LauncherService in
 * system_server.
 */
public final class IconPackProviderHook implements IXposedHookLoadPackage {
    private static final String TARGET = "com.android.launcher3";
    private static final String TAG = "LauncherHub/IconPackProvider";
    private static final long CONFIG_TTL_MS = 750L;
    private static final long PACK_RESTART_GUARD_MS = 1200L;

    private static volatile Context sContext;
    private static volatile ClassLoader sClassLoader;
    private static volatile String sPack = "";
    private static volatile long sPackReadAt;
    private static volatile boolean sObserverRegistered;
    private static volatile boolean sProviderLayerReady;
    private static volatile Bundle sConfigSnapshot;
    private static volatile long sSuppressRestartUntil;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET.equals(lpparam.packageName)) return;
        sClassLoader = lpparam.classLoader;

        safe("Application.attach", IconPackProviderHook::hookApplicationAttach);
        safe("provider icon load", () -> hookProviderIconLoad(lpparam.classLoader));
        safe("IconProvider.getStateForApp", () -> hookFreshnessState(lpparam.classLoader));
        safe("disable late BubbleTextView icon path", IconPackProviderHook::hookLegacyLateIconPath);
        safe("use.apk restart guard", IconPackProviderHook::hookUseRestartGuard);
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
                        sConfigSnapshot = readConfig(context);
                        registerObserver(context);
                    }
                });
    }

    /**
     * LOS23.2-native insertion point. IconProvider resolves dynamic Calendar/Clock before calling
     * LauncherIconProviderImpl.loadPackageIcon(), so this replaces ordinary app/activity icons
     * before IconCache creates BitmapInfo while preserving the ROM's dynamic icon path.
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
                                ComponentName component = componentOf((PackageItemInfo) param.args[0]);
                                if (component == null) return;
                                Drawable drawable = IconPackLoader.getProviderDrawable(
                                        context, pack, component);
                                if (drawable != null) param.setResult(drawable);
                            } catch (Throwable t) {
                                log("LauncherIconProviderImpl.loadPackageIcon fail-soft: " + t);
                            }
                        }
                    });
            sProviderLayerReady = true;
            log("LOS23.2 LauncherIconProviderImpl.loadPackageIcon replacement registered");
            return;
        }

        Class<?> provider = XposedHelpers.findClassIfExists(
                "com.android.launcher3.icons.IconProvider", cl);
        if (provider == null) {
            log("IconProvider missing; provider layer not available");
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
        sProviderLayerReady = true;
        log("IconProvider.getIcon compatibility fallback registered");
    }

    /**
     * use.apk originally paints the selected pack onto BubbleTextView after binding. Once the
     * provider layer is active that late path is redundant and can cause inconsistent folder/cache
     * state, so suppress only that private helper. If provider hooking fails, the old path remains.
     */
    private static void hookLegacyLateIconPath() {
        XposedBridge.hookAllMethods(LauncherEnhanceHook.class, "applyIconPack",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (sProviderLayerReady) param.setResult(null);
                    }
                });
    }

    private static ComponentName componentOf(PackageItemInfo info) {
        if (info == null || info.packageName == null || info.packageName.isEmpty()
                || info.name == null || info.name.isEmpty()) return null;
        String className = info.name;
        if (className.charAt(0) == '.') className = info.packageName + className;
        return new ComponentName(info.packageName, className);
    }

    /** Add selected pack to the persistent icon-cache freshness id. */
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

    /**
     * use.apk's baseline LauncherIntegrationHook restarts Trebuchet for generic setting changes.
     * Preserve that behavior for the rest of Launcher Hub, but suppress it when the only changed
     * setting is ICON_PACK because this provider hook can update icons safely in-process.
     */
    private static void hookUseRestartGuard() {
        XposedBridge.hookAllMethods(LauncherIntegrationHook.class, "scheduleRestart",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Context c = sContext;
                            if (c == null) return;
                            Bundle now = readConfig(c);
                            Bundle before = sConfigSnapshot;
                            boolean onlyPack = onlyIconPackChanged(before, now);
                            boolean duplicatePackCallback = SystemClock.uptimeMillis()
                                    < sSuppressRestartUntil && sameConfig(before, now);
                            sConfigSnapshot = now == null ? null : new Bundle(now);
                            if (onlyPack) {
                                sSuppressRestartUntil = SystemClock.uptimeMillis()
                                        + PACK_RESTART_GUARD_MS;
                                param.setResult(null);
                                log("suppressed Trebuchet restart for icon-pack-only change");
                            } else if (duplicatePackCallback) {
                                param.setResult(null);
                            }
                        } catch (Throwable t) {
                            log("restart guard fail-soft: " + t);
                        }
                    }
                });
    }

    private static Bundle readConfig(Context context) {
        try {
            Bundle b = context.getContentResolver().call(
                    ConfigKeys.URI, ConfigKeys.METHOD_GET_CONFIG, null, null);
            return b == null ? new Bundle() : new Bundle(b);
        } catch (Throwable t) {
            log("full config read failed: " + t);
            return null;
        }
    }

    private static boolean onlyIconPackChanged(Bundle before, Bundle after) {
        if (before == null || after == null) return false;
        String oldPack = before.getString(ConfigKeys.ICON_PACK, "");
        String newPack = after.getString(ConfigKeys.ICON_PACK, "");
        if (Objects.equals(oldPack, newPack)) return false;
        Set<String> keys = new HashSet<>(before.keySet());
        keys.addAll(after.keySet());
        keys.remove(ConfigKeys.ICON_PACK);
        for (String key : keys) {
            if (!Objects.equals(before.get(key), after.get(key))) return false;
        }
        return true;
    }

    private static boolean sameConfig(Bundle a, Bundle b) {
        if (a == null || b == null) return false;
        Set<String> keys = new HashSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (String key : keys) {
            if (!Objects.equals(a.get(key), b.get(key))) return false;
        }
        return true;
    }

    private static String readPack(Context context) {
        long now = SystemClock.uptimeMillis();
        if (now - sPackReadAt < CONFIG_TTL_MS) return sPack;
        synchronized (IconPackProviderHook.class) {
            now = SystemClock.uptimeMillis();
            if (now - sPackReadAt < CONFIG_TTL_MS) return sPack;
            try {
                Bundle b = readConfig(context);
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
     * Same lightweight refresh semantics used by LOS23.2 for icon-processor changes:
     * clear in-memory icon cache and ask the active LauncherModel to reload. No startup reload,
     * process kill, root shell, getApp/mApp access, or obsolete DeviceProfile API.
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
