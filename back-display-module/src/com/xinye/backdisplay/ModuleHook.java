package com.xinye.backdisplay;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Display;
import android.view.View;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LineageOS 23.2 / Android 16 module with two deliberately narrow features:
 *  1) crDroid-style Back gesture arrow/capsule visibility toggle.
 *  2) Automatic-brightness minimum floor + one-shot ALS refresh when the screen turns on.
 *
 * No predictive-back window animation tuning is performed here.
 */
public final class ModuleHook implements IXposedHookLoadPackage {
    private static final String TAG = "BackArrowBrightness/LOS23";
    private static final Uri SETTINGS_URI = Uri.parse("content://com.xinye.backdisplay.settings");
    private static final long SETTINGS_TTL_MS = 300L;

    private static volatile Context sContext;
    private static volatile Config sConfig = Config.defaults();
    private static volatile long sConfigAt;

    private static final Map<Object, Integer> OLD_DISPLAY_STATES =
            Collections.synchronizedMap(new WeakHashMap<Object, Integer>());

    private interface ThrowingRunnable { void run() throws Throwable; }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if ("com.android.systemui".equals(lpparam.packageName)) {
            safe("SystemUI Back arrow", () -> hookBackArrow(lpparam.classLoader));
        } else if ("android".equals(lpparam.packageName)) {
            safe("Automatic brightness", () -> hookBrightness(lpparam.classLoader));
        }
    }

    /**
     * Mirrors crDroid's implementation conceptually:
     * BackPanelController keeps processing the gesture, but its BackPanel view is GONE when
     * the user disables the arrow. This hides both the arrow and its pill/capsule background
     * without disabling Back navigation or predictive-back dispatch.
     */
    private static void hookBackArrow(ClassLoader cl) {
        Class<?> controller = XposedHelpers.findClassIfExists(
                "com.android.systemui.navigationbar.gestural.BackPanelController", cl);
        if (controller == null) {
            XposedBridge.log(TAG + ": BackPanelController not found; Back arrow hook skipped");
            return;
        }

        XposedBridge.hookAllConstructors(controller, cacheContextHook());
        XposedBridge.hookAllMethods(controller, "updateArrowState", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (config().showBackArrow) return;
                    Object panel = XposedHelpers.getObjectField(param.thisObject, "mView");
                    if (panel instanceof View) {
                        ((View) panel).setVisibility(View.GONE);
                    } else if (panel != null) {
                        XposedHelpers.callMethod(panel, "setVisibility", View.GONE);
                    }
                } catch (Throwable t) {
                    log("hide BackPanel", t);
                }
            }
        });

        XposedBridge.log(TAG + ": crDroid-style Back arrow visibility hook registered");
    }

    private static void hookBrightness(ClassLoader cl) {
        final Class<?> abc = XposedHelpers.findClassIfExists(
                "com.android.server.display.AutomaticBrightnessController", cl);
        if (abc == null) {
            XposedBridge.log(TAG + ": AutomaticBrightnessController not found; brightness hook skipped");
            return;
        }

        XposedBridge.hookAllConstructors(abc, cacheContextHook());

        // Apply the floor at the source of Android's automatic-brightness recommendation.
        // This covers both AutomaticBrightnessStrategy and AutomaticBrightnessStrategy2 callers.
        XposedBridge.hookAllMethods(abc, "getAutomaticScreenBrightness", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Config c = config();
                    if (!c.brightnessFloorEnabled) return;

                    int autoState = XposedHelpers.getIntField(param.thisObject, "mState");
                    int displayState = XposedHelpers.getIntField(param.thisObject, "mDisplayState");
                    if (autoState != 1 || displayState != Display.STATE_ON) return;

                    Object result = param.getResult();
                    float value = result instanceof Number
                            ? ((Number) result).floatValue()
                            : Float.NaN;

                    if (Float.isNaN(value) || value < c.brightnessFloor) {
                        param.setResult(Float.valueOf(c.brightnessFloor));
                    }
                } catch (Throwable t) {
                    log("automatic brightness floor", t);
                }
            }
        });

        // Track OFF/DOZE -> ON transition. We re-arm the ALS only once per transition.
        XposedBridge.hookAllMethods(abc, "configure", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    OLD_DISPLAY_STATES.put(param.thisObject,
                            Integer.valueOf(XposedHelpers.getIntField(param.thisObject,
                                    "mDisplayState")));
                } catch (Throwable ignored) {
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Config c = config();
                    if (!c.wakeRefresh || param.args == null || param.args.length < 8) return;

                    int autoState = ((Number) param.args[0]).intValue();
                    int newDisplayState = ((Number) param.args[7]).intValue();
                    Integer oldState = OLD_DISPLAY_STATES.remove(param.thisObject);

                    if (autoState != 1 || newDisplayState != Display.STATE_ON) return;
                    if (oldState != null && oldState.intValue() == Display.STATE_ON) return;

                    scheduleAlsRearm(param.thisObject);
                } catch (Throwable t) {
                    log("screen-on ALS transition", t);
                }
            }
        });

        XposedBridge.log(TAG + ": brightness floor + wake ALS refresh hooks registered");
    }

    private static void scheduleAlsRearm(final Object controller) {
        try {
            Object h = XposedHelpers.getObjectField(controller, "mHandler");
            if (h instanceof Handler) {
                ((Handler) h).postDelayed(() -> rearmAmbientSensor(controller), 120L);
            } else {
                rearmAmbientSensor(controller);
            }
        } catch (Throwable t) {
            rearmAmbientSensor(controller);
        }
    }

    private static void rearmAmbientSensor(final Object controller) {
        Config c = config();
        if (!c.wakeRefresh) return;

        try {
            int state = XposedHelpers.getIntField(controller, "mState");
            int displayState = XposedHelpers.getIntField(controller, "mDisplayState");
            if (state != 1 || displayState != Display.STATE_ON) return;
        } catch (Throwable t) {
            log("ALS state gate", t);
            return;
        }

        try {
            XposedHelpers.callMethod(controller, "setLightSensorEnabled", false);
        } catch (Throwable t) {
            log("ALS disable", t);
        }

        // Float.NaN is Android's BRIGHTNESS_INVALID_FLOAT value; the public SDK hides the constant.
        try { XposedHelpers.setBooleanField(controller, "mAmbientLuxValid", false); }
        catch (Throwable ignored) {}
        try { XposedHelpers.setFloatField(controller, "mScreenAutoBrightness", Float.NaN); }
        catch (Throwable ignored) {}
        try { XposedHelpers.setFloatField(controller, "mRawScreenAutoBrightness", Float.NaN); }
        catch (Throwable ignored) {}

        try {
            XposedHelpers.callMethod(controller, "setLightSensorEnabled", true);
        } catch (Throwable t) {
            log("ALS enable", t);
        }

        try {
            Object h = XposedHelpers.getObjectField(controller, "mHandler");
            if (h instanceof Handler) {
                Handler handler = (Handler) h;
                handler.postDelayed(() -> safeCallUpdate(controller), 250L);
                handler.postDelayed(() -> safeCallUpdate(controller), 900L);
            } else {
                safeCallUpdate(controller);
            }
        } catch (Throwable t) {
            safeCallUpdate(controller);
        }
    }

    private static void safeCallUpdate(Object controller) {
        try {
            XposedHelpers.callMethod(controller, "update");
        } catch (Throwable t) {
            log("AutomaticBrightnessController.update", t);
        }
    }

    private static XC_MethodHook cacheContextHook() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Context context = findContext(param.args);
                if (context == null && param.thisObject instanceof Context) {
                    context = (Context) param.thisObject;
                }
                if (context != null) {
                    sContext = context;
                    refreshConfig(true);
                }
            }
        };
    }

    private static Context findContext(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof Context) return (Context) arg;
        }
        return null;
    }

    private static Config config() {
        refreshConfig(false);
        return sConfig;
    }

    private static void refreshConfig(boolean force) {
        Context context = sContext;
        if (context == null) return;

        long now = SystemClock.uptimeMillis();
        if (!force && now - sConfigAt < SETTINGS_TTL_MS) return;
        sConfigAt = now;

        try {
            Bundle bundle = context.getContentResolver().call(SETTINGS_URI, "get", null, null);
            if (bundle != null) sConfig = Config.from(bundle);
        } catch (Throwable ignored) {
            // Provider may not be available during very early boot. Safe defaults remain active and
            // the next gesture/brightness evaluation retries the read.
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void safe(String name, ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + name + " skipped: " + t);
        }
    }

    private static void log(String where, Throwable t) {
        XposedBridge.log(TAG + ": " + where + ": " + t);
    }

    private static final class Config {
        final boolean showBackArrow;
        final boolean brightnessFloorEnabled;
        final float brightnessFloor;
        final boolean wakeRefresh;

        Config(boolean showBackArrow, boolean brightnessFloorEnabled,
               float brightnessFloor, boolean wakeRefresh) {
            this.showBackArrow = showBackArrow;
            this.brightnessFloorEnabled = brightnessFloorEnabled;
            this.brightnessFloor = brightnessFloor;
            this.wakeRefresh = wakeRefresh;
        }

        static Config defaults() {
            // Requested default: hide the Back arrow/capsule; keep a 10% auto-brightness floor.
            return new Config(false, true, 0.10f, true);
        }

        static Config from(Bundle bundle) {
            return new Config(
                    bundle.getBoolean("show_back_arrow", false),
                    bundle.getBoolean("brightness_floor_enabled", true),
                    clamp(bundle.getInt("brightness_floor", 10) / 100f, 0.01f, 0.30f),
                    bundle.getBoolean("wake_refresh", true));
        }
    }
}
