package com.lineageos.launcherenhance;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.InputDevice;
import android.view.MotionEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class ThreeFingerScreenshotHook implements IXposedHookLoadPackage {
    private static final String TARGET = "android";
    private static final String PWM = "com.android.server.policy.PhoneWindowManager";
    private static final String POINTER_LISTENER =
            "android.view.WindowManagerPolicyConstants$PointerEventListener";
    private static final String TAG = "LauncherHub/ThreeFinger";

    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);
    private static volatile boolean sEnabled = true;
    private static Object sListenerProxy;
    private static Detector sDetector;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET.equals(lpparam.packageName)) return;
        try {
            Class<?> pwm = XposedHelpers.findClass(PWM, lpparam.classLoader);
            XposedBridge.hookAllMethods(pwm, "systemReady", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    registerOnce(param.thisObject, lpparam.classLoader);
                }
            });
            log("PhoneWindowManager systemReady hook installed");
        } catch (Throwable t) {
            log("hook registration skipped: " + t);
            XposedBridge.log(t);
        }
    }

    private static void registerOnce(Object pwm, ClassLoader cl) {
        if (pwm == null || !REGISTERED.compareAndSet(false, true)) return;
        try {
            Context context = (Context) XposedHelpers.getObjectField(pwm, "mContext");
            Handler handler = resolveHandler(pwm);
            refreshConfig(context);
            registerConfigObserver(context, handler);
            handler.postDelayed(() -> refreshConfig(context), 5_000L);
            handler.postDelayed(() -> refreshConfig(context), 20_000L);
            handler.postDelayed(() -> refreshConfig(context), 60_000L);

            sDetector = new Detector(context, () -> requestScreenshot(pwm, handler));
            Class<?> listenerClass = XposedHelpers.findClass(POINTER_LISTENER, cl);
            sListenerProxy = Proxy.newProxyInstance(cl, new Class<?>[]{listenerClass},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("onPointerEvent".equals(name) && args != null && args.length > 0
                                && args[0] instanceof MotionEvent) {
                            sDetector.onPointerEvent((MotionEvent) args[0]);
                            return null;
                        }
                        if ("toString".equals(name)) return "LauncherHubThreeFingerListener";
                        if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                        if ("equals".equals(name)) return proxy == (args == null ? null : args[0]);
                        return null;
                    });

            Object funcs = XposedHelpers.getObjectField(pwm, "mWindowManagerFuncs");
            try {
                XposedHelpers.callMethod(funcs, "registerPointerEventListener",
                        sListenerProxy, Display.DEFAULT_DISPLAY);
            } catch (Throwable twoArgFailure) {
                XposedHelpers.callMethod(funcs, "registerPointerEventListener", sListenerProxy);
            }
            log("global listener registered; enabled=" + sEnabled);
        } catch (Throwable t) {
            REGISTERED.set(false);
            sListenerProxy = null;
            sDetector = null;
            log("listener registration failed safely: " + t);
            XposedBridge.log(t);
        }
    }

    private static Handler resolveHandler(Object pwm) {
        try {
            Object h = XposedHelpers.getObjectField(pwm, "mHandler");
            if (h instanceof Handler) return (Handler) h;
        } catch (Throwable ignored) {}
        return new Handler(Looper.getMainLooper());
    }

    private static void registerConfigObserver(Context context, Handler handler) {
        try {
            context.getContentResolver().registerContentObserver(
                    ConfigKeys.URI, true, new ContentObserver(handler) {
                        @Override public void onChange(boolean selfChange) { refreshConfig(context); }
                        @Override public void onChange(boolean selfChange, android.net.Uri uri) {
                            refreshConfig(context);
                        }
                    });
        } catch (Throwable t) {
            log("config observer skipped: " + t);
        }
    }

    private static void refreshConfig(Context context) {
        try {
            Bundle b = context.getContentResolver().call(
                    ConfigKeys.URI, ConfigKeys.METHOD_GET_CONFIG, null, null);
            if (b != null) {
                sEnabled = b.getBoolean(ConfigKeys.THREE_FINGER_SCREENSHOT, true);
                if (!sEnabled && sDetector != null) sDetector.reset();
            }
        } catch (Throwable t) {
            log("config read deferred: " + t);
        }
    }

    private static void requestScreenshot(Object pwm, Handler handler) {
        if (!sEnabled) return;
        handler.post(() -> {
            try {
                int type = staticInt("android.view.WindowManager",
                        "TAKE_SCREENSHOT_FULLSCREEN", 1);
                int source = staticInt("android.view.WindowManager$ScreenshotSource",
                        "SCREENSHOT_KEY_OTHER", 2);
                XposedHelpers.callMethod(pwm, "takeScreenshot", type, source);
            } catch (Throwable t) {
                log("screenshot request failed safely: " + t);
                XposedBridge.log(t);
            }
        });
    }

    private static int staticInt(String className, String fieldName, int fallback) {
        try {
            Class<?> c = Class.forName(className);
            Field f = c.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private interface Trigger { void run(); }

    private static final class Detector {
        private static final int NONE = 0;
        private static final int DETECTING = 1;
        private static final int DETECTED_FALSE = 2;
        private static final int DETECTED_TRUE = 3;
        private static final int NO_DETECT = 4;
        private final float[] initY = new float[3];
        private final int[] pointerIds = new int[3];
        private final Trigger trigger;
        private final float density;
        private final int threshold;
        private final int threeGestureThreshold;
        private final int screenHeight;
        private final int screenWidth;
        private int state = NONE;

        Detector(Context context, Trigger trigger) {
            this.trigger = trigger;
            android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
            density = dm.density;
            threshold = (int) (50.0f * density);
            threeGestureThreshold = threshold * 3;
            screenHeight = dm.heightPixels;
            screenWidth = dm.widthPixels;
        }

        void reset() { state = NONE; }

        void onPointerEvent(MotionEvent event) {
            if (!sEnabled || event == null) { reset(); return; }
            if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                state = NONE;
            } else if (state == NONE && event.getPointerCount() == 3) {
                if (checkStart(event)) {
                    state = DETECTING;
                    for (int i = 0; i < 3; i++) {
                        pointerIds[i] = event.getPointerId(i);
                        initY[i] = event.getY(i);
                    }
                } else {
                    state = NO_DETECT;
                }
            }
            if (state != DETECTING) return;
            if (event.getPointerCount() != 3) { state = DETECTED_FALSE; return; }
            if (event.getActionMasked() != MotionEvent.ACTION_MOVE) return;
            float distance = 0.0f;
            for (int i = 0; i < 3; i++) {
                int index = event.findPointerIndex(pointerIds[i]);
                if (index < 0 || index >= 3) { state = DETECTED_FALSE; return; }
                distance += event.getY(index) - initY[i];
            }
            if (distance >= threeGestureThreshold) {
                state = DETECTED_TRUE;
                trigger.run();
            }
        }

        private boolean checkStart(MotionEvent event) {
            if (event.getEventTime() - event.getDownTime() > 500L) return false;
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (int i = 0; i < event.getPointerCount(); i++) {
                float x = event.getX(i), y = event.getY(i);
                if (y > screenHeight - threshold) return false;
                maxX = Math.max(maxX, x); minX = Math.min(minX, x);
                maxY = Math.max(maxY, y); minY = Math.min(minY, y);
            }
            if (maxY - minY > density * 150.0f) return false;
            return maxX - minX <= Math.min(screenWidth, screenHeight);
        }
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }
}
