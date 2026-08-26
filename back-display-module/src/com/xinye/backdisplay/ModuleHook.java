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
 * LineageOS 23.2 / Android 16 module with two narrow features:
 *  1) crDroid-style Back gesture arrow/capsule visibility toggle.
 *  2) Automatic-brightness minimum floor + one-shot ALS refresh on screen-on.
 *
 * No predictive-back window animation tuning is performed.
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
     * LineageOS 23.2 currently does this in BackPanelController.updateArrowState():
     *   GestureState.ENTRY -> mView.isVisible = true
     *
     * crDroid changes only that assignment to:
     *   mView.isVisible = backArrowVisibility
     *
     * We reproduce that exact semantic point with LSPosed: after the stock ENTRY branch runs,
     * if the user selected OFF, only the BackPanel view is made GONE. For every non-ENTRY state,
     * and whenever the switch is ON, we do absolutely nothing to the stock controller.
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
                    Config c = config();
                    if (c.showBackArrow) return; // ON = 100% stock LineageOS behavior.

                    Object state = XposedHelpers.getObjectField(param.thisObject, "currentState");
                    if (state == null || !"ENTRY".equals(String.valueOf(state))) return;

                    Object panel = XposedHelpers.getObjectField(param.thisObject, "mView");
                    if (panel instanceof View) {
                        ((View) panel).setVisibility(View.GONE);
                    } else if (panel != null) {
                        XposedHelpers.callMethod(panel, "setVisibility", View.GONE);
                    }
                } catch (Throwable t) {
                    log("BackPanel ENTRY visibility", t);
                }
            }
        });

        XposedBridge.log(TAG + ": exact ENTRY-gated Back arrow visibility hook registered");
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

        // Re-arm ALS once when AutomaticBrightnessController transitions from non-ON to ON.
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

        // Float.NaN is Android's hidden BRIGHTNESS_INVALID_FLOAT sentinel.
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
            // Provider can be unavailable during very early boot; safe defaults remain active.
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
