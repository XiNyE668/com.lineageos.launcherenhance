package de.robv.android.xposed;

import android.app.Application;

import java.lang.reflect.Method;

/**
 * Compatibility shim for the legacy Xposed API artifact, which does not expose AndroidAppHelper.
 * At runtime this only asks ActivityThread for the current process Application instance.
 */
public final class AndroidAppHelper {
    private AndroidAppHelper() {}

    public static Application currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method method = activityThread.getDeclaredMethod("currentApplication");
            method.setAccessible(true);
            Object app = method.invoke(null);
            return app instanceof Application ? (Application) app : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
