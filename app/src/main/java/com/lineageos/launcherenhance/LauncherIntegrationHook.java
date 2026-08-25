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

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Trebuchet-side integration for Launcher Hub.
 *
 * 1) Adds an explicit Launcher Hub entry to Home settings without requiring a launcher icon.
 * 2) Watches Launcher Hub's config URI from inside the Trebuchet process. When a setting changes,
 *    Trebuchet kills only its own process. This is a normal self-restart and requires no root or
 *    FORCE_STOP_PACKAGES privilege. Android immediately recreates the default home process when it
 *    is needed, so icon-pack and structural hooks bind against fresh Launcher3 views and models.
 */
public final class LauncherIntegrationHook implements IXposedHookLoadPackage {
    private static final String TARGET = "com.android.launcher3";
    private static final String MODULE = "com.lineageos.launcherenhance";
    private static final String SETTINGS_ACTIVITY = MODULE + ".MainActivity";
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
        hookHomeSettings(lpparam.classLoader);
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
        // notifyChange may dispatch both ContentObserver overloads; collapse them into one restart.
        MAIN.removeCallbacks(RESTART);
        MAIN.postDelayed(RESTART, 220L);
    }

    private static void hookHomeSettings(ClassLoader cl) {
        try {
            Class<?> fragment = XposedHelpers.findClass(
                    "com.android.launcher3.settings.SettingsActivity$LauncherSettingsFragment", cl);
            XposedHelpers.findAndHookMethod(fragment, "onCreatePreferences",
                    Bundle.class, String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            addLauncherHubPreference(param.thisObject, cl);
                        }
                    });
            XposedBridge.log(TAG + ": Home settings hook OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Home settings hook failed: " + t);
            XposedBridge.log(t);
        }
    }

    private static void addLauncherHubPreference(Object fragment, ClassLoader cl) {
        try {
            Object screen = XposedHelpers.callMethod(fragment, "getPreferenceScreen");
            Object contextObject = XposedHelpers.callMethod(fragment, "getContext");
            if (screen == null || !(contextObject instanceof Context)) return;

            Object existing = XposedHelpers.callMethod(screen, "findPreference", PREF_KEY);
            if (existing != null) return;

            Context context = (Context) contextObject;
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
            XposedHelpers.callMethod(screen, "addPreference", preference);
            XposedBridge.log(TAG + ": Launcher Hub preference added to Home settings");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": add Home settings entry failed: " + t);
            XposedBridge.log(t);
        }
    }
}
