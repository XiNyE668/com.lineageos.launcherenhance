package com.xinye.backdisplay;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Display;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class ModuleHook implements IXposedHookLoadPackage {
    private static final String TAG = "BackDisplay/LOS23";
    private static final Uri SETTINGS_URI = Uri.parse("content://com.xinye.backdisplay.settings");
    private static final long SETTINGS_TTL_MS = 1500L;

    private static volatile Context sContext;
    private static volatile Config sConfig = Config.defaults();
    private static volatile long sConfigAt;
    private static final Map<Object, Integer> OLD_DISPLAY_STATES =
            Collections.synchronizedMap(new WeakHashMap<Object, Integer>());

    private interface ThrowingRunnable { void run() throws Throwable; }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if ("com.android.systemui".equals(lpparam.packageName)) {
            safe("SystemUI Back", () -> hookSystemUiBack(lpparam.classLoader));
        } else if ("com.android.launcher3".equals(lpparam.packageName)) {
            safe("Trebuchet Back", () -> hookLauncherBack(lpparam.classLoader));
        } else if ("android".equals(lpparam.packageName)) {
            safe("Automatic brightness", () -> hookBrightness(lpparam.classLoader));
        }
    }

    private static void hookSystemUiBack(ClassLoader cl) {
        Class<?> defaultCross = XposedHelpers.findClassIfExists(
                "com.android.wm.shell.back.DefaultCrossActivityBackAnimation", cl);
        if (defaultCross != null) {
            XposedBridge.hookAllConstructors(defaultCross, cacheContextHook());
            XposedBridge.hookAllMethods(defaultCross,
                    "preparePreCommitClosingRectMovement", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                Config c = config();
                                if (!c.backEnabled) return;
                                RectF start = (RectF) XposedHelpers.getObjectField(
                                        p.thisObject, "startClosingRect");
                                RectF target = (RectF) XposedHelpers.getObjectField(
                                        p.thisObject, "targetClosingRect");
                                rescaleAroundCurrentCenter(target, start, c.activityScale);
                            } catch (Throwable t) { log("cross-activity closing", t); }
                        }
                    });
            XposedBridge.hookAllMethods(defaultCross,
                    "preparePreCommitEnteringRectMovement", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                Config c = config();
                                if (!c.backEnabled) return;
                                RectF start = (RectF) XposedHelpers.getObjectField(
                                        p.thisObject, "startEnteringRect");
                                RectF target = (RectF) XposedHelpers.getObjectField(
                                        p.thisObject, "targetEnteringRect");
                                rescaleAroundCurrentCenter(target, start, c.activityScale);
                            } catch (Throwable t) { log("cross-activity entering", t); }
                        }
                    });
            XposedBridge.hookAllMethods(defaultCross,
                    "getPostCommitAnimationDuration", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            Config c = config();
                            if (c.backEnabled) p.setResult((long) c.durationMs);
                        }
                    });
        }

        Class<?> crossTask = XposedHelpers.findClassIfExists(
                "com.android.wm.shell.back.CrossTaskBackAnimation", cl);
        if (crossTask != null) {
            XposedBridge.hookAllConstructors(crossTask, cacheContextHook());
            XposedBridge.hookAllMethods(crossTask, "mapRange", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        Config c = config();
                        if (!c.backEnabled || p.args.length != 3) return;
                        float min = ((Number) p.args[1]).floatValue();
                        float max = ((Number) p.args[2]).floatValue();
                        if (near(min, 1f) && near(max, 0.8f)) {
                            p.args[2] = Float.valueOf(c.taskScale);
                        }
                    } catch (Throwable t) { log("cross-task scale", t); }
                }
            });
        }

        Class<?> backPanel = XposedHelpers.findClassIfExists(
                "com.android.systemui.navigationbar.gestural.BackPanelController", cl);
        if (backPanel != null) {
            XposedBridge.hookAllConstructors(backPanel, cacheContextHook());
            XposedBridge.hookAllMethods(backPanel, "updateArrowState", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Config c = config();
                        if (!c.backEnabled || near(c.edgeScale, 1f)) return;
                        Object view = XposedHelpers.getObjectField(p.thisObject, "mView");
                        XposedHelpers.callMethod(view, "setScaleX", c.edgeScale);
                        XposedHelpers.callMethod(view, "setScaleY", c.edgeScale);
                    } catch (Throwable t) { log("BackPanel scale", t); }
                }
            });
        }

        hookDurationAnimator("com.android.wm.shell.back.CrossTaskBackAnimation");
        XposedBridge.log(TAG + ": SystemUI Back hooks registered");
    }

    private static void hookLauncherBack(ClassLoader cl) {
        Class<?> launcherBack = XposedHelpers.findClassIfExists(
                "com.android.quickstep.LauncherBackAnimationController", cl);
        if (launcherBack == null) return;
        XposedBridge.hookAllConstructors(launcherBack, cacheContextHook());
        XposedBridge.hookAllMethods(launcherBack, "updateBackProgress", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    Config c = config();
                    if (!c.backEnabled || p.args.length < 1) return;
                    float progress = ((Number) p.args[0]).floatValue();
                    progress = clamp(progress, 0f, 1f);
                    Rect start = (Rect) XposedHelpers.getObjectField(p.thisObject, "mStartRect");
                    RectF current = (RectF) XposedHelpers.getObjectField(p.thisObject, "mCurrentRect");
                    if (start.width() <= 0 || start.height() <= 0) return;
                    float cx = current.centerX();
                    float cy = current.centerY();
                    float scale = 1f + (c.homeScale - 1f) * progress;
                    float w = start.width() * scale;
                    float h = start.height() * scale;
                    current.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
                    float cr0 = XposedHelpers.getFloatField(
                            p.thisObject, "mWindowScaleStartCornerRadius");
                    float cr1 = XposedHelpers.getFloatField(
                            p.thisObject, "mWindowScaleEndCornerRadius");
                    float radius = cr0 + (cr1 - cr0) * progress;
                    XposedHelpers.callMethod(p.thisObject, "applyTransform", current, radius);
                } catch (Throwable t) { log("return-home progress", t); }
            }
        });
        hookDurationAnimator("com.android.quickstep.LauncherBackAnimationController");
        XposedBridge.log(TAG + ": Trebuchet return-home hooks registered");
    }

    private static void hookDurationAnimator(final String ownerClass) {
        XposedBridge.hookAllMethods(ValueAnimator.class, "setDuration", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    Config c = config();
                    if (!c.backEnabled || p.args.length == 0) return;
                    long original = ((Number) p.args[0]).longValue();
                    if (original < 300L) return;
                    if (stackContains(ownerClass)) p.args[0] = Long.valueOf(c.durationMs);
                } catch (Throwable t) { log("duration " + ownerClass, t); }
            }
        });
    }

    private static void hookBrightness(ClassLoader cl) {
        hookBrightnessStrategy(cl,
                "com.android.server.display.brightness.strategy.AutomaticBrightnessStrategy");
        hookBrightnessStrategy(cl,
                "com.android.server.display.brightness.strategy.AutomaticBrightnessStrategy2");

        Class<?> abc = XposedHelpers.findClassIfExists(
                "com.android.server.display.AutomaticBrightnessController", cl);
        if (abc != null) {
            XposedBridge.hookAllMethods(abc, "configure", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        OLD_DISPLAY_STATES.put(p.thisObject,
                                Integer.valueOf(XposedHelpers.getIntField(p.thisObject,
                                        "mDisplayState")));
                    } catch (Throwable ignored) {}
                }

                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Config c = config();
                        if (!c.wakeRefresh || p.args.length < 8) return;
                        int autoState = ((Number) p.args[0]).intValue();
                        int displayState = ((Number) p.args[7]).intValue();
                        Integer old = OLD_DISPLAY_STATES.remove(p.thisObject);
                        if (autoState != 1 || displayState != Display.STATE_ON
                                || (old != null && old.intValue() == Display.STATE_ON)) return;
                        rearmAmbientSensor(p.thisObject);
                    } catch (Throwable t) { log("wake ALS refresh", t); }
                }
            });
        }
        XposedBridge.log(TAG + ": brightness hooks registered");
    }

    private static void hookBrightnessStrategy(ClassLoader cl, String name) {
        Class<?> strategy = XposedHelpers.findClassIfExists(name, cl);
        if (strategy == null) return;
        XposedBridge.hookAllConstructors(strategy, cacheContextHook());
        XposedBridge.hookAllMethods(strategy, "getAutomaticScreenBrightness",
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Config c = config();
                            if (!c.brightnessEnabled) return;
                            Object controller = XposedHelpers.getObjectField(
                                    p.thisObject, "mAutomaticBrightnessController");
                            if (controller == null) return;
                            int state = XposedHelpers.getIntField(controller, "mState");
                            int displayState = XposedHelpers.getIntField(
                                    controller, "mDisplayState");
                            if (state != 1 || displayState != Display.STATE_ON) return;
                            Object result = p.getResult();
                            float brightness = result instanceof Number
                                    ? ((Number) result).floatValue() : Float.NaN;
                            if (Float.isNaN(brightness) || brightness < c.brightnessFloor) {
                                p.setResult(Float.valueOf(c.brightnessFloor));
                            }
                        } catch (Throwable t) { log("brightness floor", t); }
                    }
                });
    }

    private static void rearmAmbientSensor(final Object controller) {
        try { XposedHelpers.callMethod(controller, "setLightSensorEnabled", false); }
        catch (Throwable ignored) {}
        try { XposedHelpers.setBooleanField(controller, "mAmbientLuxValid", false); }
        catch (Throwable ignored) {}
        try { XposedHelpers.setFloatField(controller, "mScreenAutoBrightness", Float.NaN); }
        catch (Throwable ignored) {}
        try { XposedHelpers.setFloatField(controller, "mRawScreenAutoBrightness", Float.NaN); }
        catch (Throwable ignored) {}
        try { XposedHelpers.callMethod(controller, "setLightSensorEnabled", true); }
        catch (Throwable t) { log("ALS re-enable", t); }
        try {
            Object h = XposedHelpers.getObjectField(controller, "mHandler");
            if (h instanceof Handler) {
                Handler handler = (Handler) h;
                handler.postDelayed(() -> safeCallUpdate(controller), 250L);
                handler.postDelayed(() -> safeCallUpdate(controller), 900L);
            } else {
                safeCallUpdate(controller);
            }
        } catch (Throwable t) { safeCallUpdate(controller); }
    }

    private static void safeCallUpdate(Object controller) {
        try { XposedHelpers.callMethod(controller, "update"); }
        catch (Throwable t) { log("ABC update", t); }
    }

    private static XC_MethodHook cacheContextHook() {
        return new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Context c = findContext(p.args);
                if (c == null && p.thisObject instanceof Context) c = (Context) p.thisObject;
                if (c != null) {
                    sContext = c;
                    refreshConfig(true);
                }
            }
        };
    }

    private static Context findContext(Object[] args) {
        if (args == null) return null;
        for (Object o : args) if (o instanceof Context) return (Context) o;
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
            Bundle b = context.getContentResolver().call(SETTINGS_URI, "get", null, null);
            if (b != null) sConfig = Config.from(b);
        } catch (Throwable t) {
            // During early system_server boot the module provider may not be ready yet.
            // Keep safe defaults and retry on a later hook call.
        }
    }

    private static void rescaleAroundCurrentCenter(RectF target, RectF start, float scale) {
        if (target == null || start == null || start.width() <= 0f || start.height() <= 0f) return;
        float cx = target.centerX();
        float cy = target.centerY();
        float w = start.width() * scale;
        float h = start.height() * scale;
        target.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
    }

    private static boolean stackContains(String className) {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        int max = Math.min(stack.length, 28);
        for (int i = 0; i < max; i++) {
            if (className.equals(stack[i].getClassName())) return true;
        }
        return false;
    }

    private static boolean near(float a, float b) { return Math.abs(a - b) < 0.001f; }
    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static void safe(String name, ThrowingRunnable r) {
        try { r.run(); }
        catch (Throwable t) { XposedBridge.log(TAG + ": " + name + " skipped: " + t); }
    }

    private static void log(String where, Throwable t) {
        XposedBridge.log(TAG + ": " + where + ": " + t);
    }

    private static final class Config {
        final boolean backEnabled;
        final float activityScale;
        final float taskScale;
        final float homeScale;
        final int durationMs;
        final float edgeScale;
        final boolean brightnessEnabled;
        final float brightnessFloor;
        final boolean wakeRefresh;

        Config(boolean backEnabled, float activityScale, float taskScale, float homeScale,
               int durationMs, float edgeScale, boolean brightnessEnabled,
               float brightnessFloor, boolean wakeRefresh) {
            this.backEnabled = backEnabled;
            this.activityScale = activityScale;
            this.taskScale = taskScale;
            this.homeScale = homeScale;
            this.durationMs = durationMs;
            this.edgeScale = edgeScale;
            this.brightnessEnabled = brightnessEnabled;
            this.brightnessFloor = brightnessFloor;
            this.wakeRefresh = wakeRefresh;
        }

        static Config defaults() {
            return new Config(true, .90f, .84f, .82f, 420, 1f, true, .10f, true);
        }

        static Config from(Bundle b) {
            return new Config(
                    b.getBoolean("back_enabled", true),
                    clamp(b.getInt("activity_scale", 90) / 100f, .84f, .96f),
                    clamp(b.getInt("task_scale", 84) / 100f, .74f, .94f),
                    clamp(b.getInt("home_scale", 82) / 100f, .70f, .94f),
                    (int) clamp(b.getInt("back_duration", 420), 250, 650),
                    clamp(b.getInt("edge_scale", 100) / 100f, .80f, 1.20f),
                    b.getBoolean("brightness_enabled", true),
                    clamp(b.getInt("brightness_floor", 10) / 100f, .01f, .30f),
                    b.getBoolean("wake_refresh", true));
        }
    }
}
