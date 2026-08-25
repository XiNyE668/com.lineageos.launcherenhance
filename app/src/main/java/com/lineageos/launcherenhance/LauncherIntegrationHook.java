package com.lineageos.launcherenhance;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Trebuchet-side settings integration and rootless config reload support. */
public final class LauncherIntegrationHook implements IXposedHookLoadPackage {
    private static final String TARGET = "com.android.launcher3";
    private static final String MODULE = "com.lineageos.launcherenhance";
    private static final String SETTINGS_ACTIVITY = MODULE + ".MainActivity";
    private static final String LAUNCHER_SETTINGS_FRAGMENT =
            "com.android.launcher3.settings.SettingsActivity$LauncherSettingsFragment";
    private static final String PREF_KEY = "launcher_hub_settings";
    private static final String LOCK_LAYOUT_KEY = "pref_workspace_lock";
    private static final String TAG = "LauncherHub/Integration";

    private static final AtomicBoolean HOOKS_INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean OBSERVER_REGISTERED = new AtomicBoolean(false);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final Runnable RESTART = () -> {
        log("restarting Trebuchet process after config change");
        Process.killProcess(Process.myPid());
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET.equals(lpparam.packageName)) return;
        install(lpparam.classLoader);
    }

    /**
     * Public installer so a known-working entry point can install this integration in the same
     * Trebuchet process. This removes any dependency on LSPosed loading a fourth legacy entry class.
     */
    public static void install(ClassLoader cl) {
        if (cl == null || !HOOKS_INSTALLED.compareAndSet(false, true)) return;
        log("install() invoked in pid=" + Process.myPid());
        hookApplicationAttach();
        hookPreferenceGroupAnchor(cl);
        hookLauncherSettingsFragment(cl);
        hookPreferenceInflationFallback(cl);
        hookSettingsActivityFallback(cl);
        log("Home settings integration hooks installed");
    }

    private static void hookApplicationAttach() {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context context = (Context) param.args[0];
                            if (context == null || !TARGET.equals(context.getPackageName())) return;
                            registerConfigObserver(context.getApplicationContext());
                        }
                    });
            log("Application.attach hook OK");
        } catch (Throwable t) {
            log("Application.attach hook failed: " + t);
        }
    }

    private static void registerConfigObserver(Context context) {
        if (!OBSERVER_REGISTERED.compareAndSet(false, true)) return;
        try {
            context.getContentResolver().registerContentObserver(
                    ConfigKeys.URI, true, new ContentObserver(MAIN) {
                        @Override
                        public void onChange(boolean selfChange) {
                            scheduleRestart();
                        }

                        @Override
                        public void onChange(boolean selfChange, Uri uri) {
                            scheduleRestart();
                        }
                    });
            log("config observer registered; rootless self-reload enabled");
        } catch (Throwable t) {
            OBSERVER_REGISTERED.set(false);
            log("config observer registration failed: " + t);
        }
    }

    private static void scheduleRestart() {
        MAIN.removeCallbacks(RESTART);
        MAIN.postDelayed(RESTART, 220L);
    }

    /** Primary LineageOS 23.2 path: insert immediately before pref_workspace_lock. */
    private static void hookPreferenceGroupAnchor(ClassLoader cl) {
        try {
            Class<?> preference = XposedHelpers.findClass("androidx.preference.Preference", cl);
            Class<?> preferenceGroup = XposedHelpers.findClass(
                    "androidx.preference.PreferenceGroup", cl);
            XposedHelpers.findAndHookMethod(preferenceGroup, "addPreference", preference,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length == 0 || param.args[0] == null) return;
                            Object incoming = param.args[0];
                            Object keyObject = XposedHelpers.callMethod(incoming, "getKey");
                            if (!LOCK_LAYOUT_KEY.equals(keyObject)) return;

                            Object screen = param.thisObject;
                            Object existing = XposedHelpers.callMethod(
                                    screen, "findPreference", PREF_KEY);
                            if (existing != null) return;

                            Object contextObject = XposedHelpers.callMethod(screen, "getContext");
                            if (!(contextObject instanceof Context)) return;
                            addLauncherHubPreferenceToScreen(
                                    screen,
                                    (Context) contextObject,
                                    incoming.getClass().getClassLoader());
                            log("anchored Launcher Hub before Lock layout");
                        }
                    });
            log("PreferenceGroup anchor hook OK");
        } catch (Throwable t) {
            log("PreferenceGroup anchor hook failed: " + t);
            XposedBridge.log(t);
        }
    }

    private static void hookLauncherSettingsFragment(ClassLoader cl) {
        try {
            Class<?> fragment = XposedHelpers.findClass(LAUNCHER_SETTINGS_FRAGMENT, cl);
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                    fragment, "onCreatePreferences", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            addLauncherHubPreference(param.thisObject);
                        }
                    });
            log("LauncherSettingsFragment hooks=" + hooks.size());
        } catch (Throwable t) {
            log("LauncherSettingsFragment hook failed: " + t);
        }
    }

    private static void hookPreferenceInflationFallback(ClassLoader cl) {
        try {
            Class<?> prefFragment = XposedHelpers.findClass(
                    "androidx.preference.PreferenceFragmentCompat", cl);
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                    prefFragment, "setPreferencesFromResource", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object fragment = param.thisObject;
                            if (fragment == null) return;
                            String name = fragment.getClass().getName();
                            if (LAUNCHER_SETTINGS_FRAGMENT.equals(name)
                                    || name.startsWith("com.android.launcher3.settings.")) {
                                addLauncherHubPreference(fragment);
                            }
                        }
                    });
            log("Preference inflation fallback hooks=" + hooks.size());
        } catch (Throwable t) {
            log("Preference inflation fallback failed: " + t);
        }
    }

    private static void hookSettingsActivityFallback(ClassLoader cl) {
        try {
            Class<?> settingsActivity = XposedHelpers.findClass(
                    "com.android.launcher3.settings.SettingsActivity", cl);
            XposedHelpers.findAndHookMethod(settingsActivity, "onCreate", Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject == null) return;
                            scheduleActivityInjection(param.thisObject, 80L);
                            scheduleActivityInjection(param.thisObject, 300L);
                            scheduleActivityInjection(param.thisObject, 900L);
                        }
                    });
            log("SettingsActivity fallback hook OK");
        } catch (Throwable t) {
            log("SettingsActivity fallback hook failed: " + t);
        }
    }

    private static void scheduleActivityInjection(Object activity, long delayMs) {
        MAIN.postDelayed(() -> injectFromSettingsActivity(activity), delayMs);
    }

    @SuppressWarnings("unchecked")
    private static void injectFromSettingsActivity(Object activity) {
        if (activity == null) return;
        try {
            Object fm = XposedHelpers.callMethod(activity, "getSupportFragmentManager");
            if (fm == null) return;
            Object fragmentsObject = XposedHelpers.callMethod(fm, "getFragments");
            if (fragmentsObject instanceof List) {
                for (Object fragment : (List<Object>) fragmentsObject) {
                    if (isLauncherSettingsFragment(fragment)) {
                        addLauncherHubPreference(fragment);
                        return;
                    }
                }
            }
        } catch (Throwable t) {
            log("SettingsActivity injection attempt failed: " + t);
        }
    }

    private static boolean isLauncherSettingsFragment(Object fragment) {
        if (fragment == null) return false;
        String name = fragment.getClass().getName();
        return LAUNCHER_SETTINGS_FRAGMENT.equals(name)
                || name.startsWith("com.android.launcher3.settings.");
    }

    private static void addLauncherHubPreference(Object fragment) {
        try {
            Object screen = XposedHelpers.callMethod(fragment, "getPreferenceScreen");
            Object contextObject = XposedHelpers.callMethod(fragment, "getContext");
            if (screen == null || !(contextObject instanceof Context)) {
                log("preference screen/context not ready");
                return;
            }
            addLauncherHubPreferenceToScreen(
                    screen,
                    (Context) contextObject,
                    fragment.getClass().getClassLoader());
        } catch (Throwable t) {
            log("add Home settings entry failed: " + t);
            XposedBridge.log(t);
        }
    }

    private static void addLauncherHubPreferenceToScreen(
            Object screen, Context context, ClassLoader cl) {
        try {
            Object existing = XposedHelpers.callMethod(screen, "findPreference", PREF_KEY);
            if (existing != null) return;

            Class<?> preferenceClass = XposedHelpers.findClass("androidx.preference.Preference", cl);
            Object preference = XposedHelpers.newInstance(preferenceClass, context);
            XposedHelpers.callMethod(preference, "setKey", PREF_KEY);
            XposedHelpers.callMethod(preference, "setTitle", (CharSequence) "Launcher Hub");
            XposedHelpers.callMethod(preference, "setSummary",
                    (CharSequence) "图标、应用抽屉、最近任务与 Trebuchet 增强");
            XposedHelpers.callMethod(preference, "setOrder", -10000);
            XposedHelpers.callMethod(preference, "setPersistent", false);

            Intent intent = new Intent();
            intent.setComponent(new ComponentName(MODULE, SETTINGS_ACTIVITY));
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            XposedHelpers.callMethod(preference, "setIntent", intent);

            Object result = XposedHelpers.callMethod(screen, "addPreference", preference);
            log("Launcher Hub preference injected at top; result=" + result);
        } catch (Throwable t) {
            log("add preference to screen failed: " + t);
            XposedBridge.log(t);
        }
    }

    private static void log(String message) {
        Log.i(TAG, message);
        XposedBridge.log(TAG + ": " + message);
    }
}
