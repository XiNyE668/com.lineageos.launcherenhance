package com.hhvvg.launcher.compat;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs only inside the Launcher3Customizer app process. It debounces setting changes and asks
 * APatch/su to restart Trebuchet after the setting has been persisted and delivered.
 */
public final class RootRestart {
    private static final String CUSTOMIZER_PKG = "com.hhvvg.launcher3customizer";
    private static final AtomicLong GENERATION = new AtomicLong();

    private RootRestart() {}

    public static void schedule() {
        if (!isCustomizerProcess()) return;
        final long generation = GENERATION.incrementAndGet();
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(800L);
                    if (GENERATION.get() != generation) return;
                    Runtime.getRuntime().exec(new String[]{
                            "su", "-c", "killall com.android.launcher3"
                    });
                } catch (Throwable ignored) {
                    // A denied/missing root permission must never crash the settings app.
                }
            }
        }, "L3C-RootRestart");
        worker.setDaemon(true);
        worker.start();
    }

    private static boolean isCustomizerProcess() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentPackageName = activityThread.getDeclaredMethod("currentPackageName");
            currentPackageName.setAccessible(true);
            Object value = currentPackageName.invoke(null);
            return CUSTOMIZER_PKG.equals(value);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
