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

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Trebuchet-side integration for Launcher Hub.
 *
 * Home settings injection deliberately has multiple independent hook paths. LineageOS Launcher3
 * can change the exact PreferenceFragment lifecycle between point releases, so relying on only
 * LauncherSettingsFragment.onCreatePreferences() is unnecessarily fragile.
 *
 * Config changes are observed from inside the Trebuchet process. Trebuchet then terminates its own
 * process so structural hooks and icon-pack drawables are rebuilt without requiring root access.
 */
public final class LauncherIntegrationHook implements IXposedHookLoadPackage {
    private static final String TARGET = "com.android.launcher3";
    private static final String MODULE = "com.lineageos.launcherenhance";
    private static final String SETTINGS_ACTIVITY = MODULE + ".MainActivity";
    private static final String LAUNCHER_SETTINGS_FRAGMENT =
            "com.android.launcher3.settings.SettingsActivity$LauncherSettingsFragment";
    private static final String PREF_KEY = "launcher_hub_settings";
    private static final String TAG = "LauncherHub/Integration";

    private static final AtomicBoolean OBSERVER_REGISTERED = new AtomicBoolean(false);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final Runnable RESTART = () -> {
        XposedBridge.log(TAG + ": restarting Trebuchet process after config change");
        Process.killProcess(Process.myPid());
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET.equals(lpparam.packageName)) return;

        hookApplicationAttach();
        hookLauncherSettingsFragment(lpparam.classLoader);
        hookPreferenceInflationFallback(lpparam.classLoader);
        hookSettingsActivityFallback(lpparam.classLoader);
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
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Application.attach hook failed: " + t);
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
            XposedBridge.log(TAG + ": config observer registered; rootless self-reload enabled");
        } catch (Throwable t) {
            OBSERVER_REGISTERED.set(false);
            XposedBridge.log(TAG + ": config observer registration failed: " + t);
        }
    }

    private static void scheduleRestart() {
        MAIN.removeCallbacks(RESTART);
        MAIN.postDelayed(RESTART, 220L);
    }

    /** Fast path for the exact LineageOS 23.2 fragment currently in source. */
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
            XposedBridge.log(TAG + ": LauncherSettingsFragment hooks=" + hooks.size());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": LauncherSettingsFragment hook failed: " + t);
        }
    }

    /**
     * Fallback at the AndroidX Preference layer. This survives minor LauncherSettingsFragment
     * lifecycle/signature changes while still being restricted to Trebuchet's settings fragment.
     */
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
            XposedBridge.log(TAG + ": Preference inflation fallback hooks=" + hooks.size());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Preference inflation fallback failed: " + t);
        }
    }

    /**
     * Final fallback: after SettingsActivity creates/commits its fragment, resolve the active
     * support FragmentManager and inject the preference. We retry because FragmentTransaction
     * commit() is asynchronous.
     */
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
            XposedBridge.log(TAG + ": SettingsActivity fallback hook OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": SettingsActivity fallback hook failed: " + t);
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

            // Prefer the currently attached fragment list; this avoids depending on resource IDs.
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
            XposedBridge.log(TAG + ": SettingsActivity injection attempt failed: " + t);
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
                XposedBridge.log(TAG + ": preference screen/context not ready");
                return;
            }

            Object existing = XposedHelpers.callMethod(screen, "findPreference", PREF_KEY);
            if (existing != null) return;

            Context context = (Context) contextObject;
            ClassLoader cl = fragment.getClass().getClassLoader();
            Class<?> preferenceClass = XposedHelpers.findClass("androidx.preference.Preference", cl);
            Object preference = XposedHelpers.newInstance(preferenceClass, context);
            XposedHelpers.callMethod(preference, "setKey", PREF_KEY);
            XposedHelpers.callMethod(preference, "setTitle", (CharSequence) "Launcher Hub");
            XposedHelpers.callMethod(preference, "setSummary",
                    (CharSequence) "图标、应用抽屉、最近任务与 Trebuchet 增强");
            XposedHelpers.callMethod(preference, "setOrder", 900);

            Intent intent = new Intent();
            intent.setComponent(new ComponentName(MODULE, SETTINGS_ACTIVITY));
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            XposedHelpers.callMethod(preference, "setIntent", intent);

            Object result = XposedHelpers.callMethod(screen, "addPreference", preference);
            XposedBridge.log(TAG + ": Launcher Hub preference injected; result=" + result
                    + " fragment=" + fragment.getClass().getName());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": add Home settings entry failed: " + t);
            XposedBridge.log(t);
        }
    }
}
