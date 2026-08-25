package com.lineageos.launcherenhance;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Adds a lightweight crDroid-style memory readout to LineageOS 23.2 Overview.
 *
 * The view is injected as a sibling of LauncherRecentsView into Trebuchet's DragLayer, so the
 * existing RecentsView child/page bookkeeping is untouched. Monitoring only runs while Overview
 * is enabled and uses a 3 second refresh interval, matching the crDroid MemInfoView cadence.
 */
public final class RecentsMemoryHook implements IXposedHookLoadPackage {
    private static final String TARGET = "com.android.launcher3";
    private static final String TAG = "LauncherHub/MemInfo";
    private static final long REFRESH_MS = 3000L;
    private static final Map<View, Holder> HOLDERS = new WeakHashMap<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET.equals(lpparam.packageName)) return;
        try {
            Class<?> recents = XposedHelpers.findClass(
                    "com.android.quickstep.views.LauncherRecentsView", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(recents, "setOverviewStateEnabled", boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof View)) return;
                            View recentsView = (View) param.thisObject;
                            boolean enabled = (Boolean) param.args[0];
                            updateOverviewState(recentsView, enabled);
                        }
                    });
            XposedBridge.log(TAG + ": LauncherRecentsView hook OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook failed: " + t);
            XposedBridge.log(t);
        }
    }

    private static void updateOverviewState(View recentsView, boolean enabled) {
        synchronized (HOLDERS) {
            Holder holder = HOLDERS.get(recentsView);
            if (!enabled) {
                if (holder != null) holder.stop();
                return;
            }

            Config config = readConfig(recentsView.getContext());
            if (!config.showMemory) {
                if (holder != null) holder.stop();
                return;
            }

            if (holder == null) {
                holder = createHolder(recentsView);
                if (holder == null) return;
                HOLDERS.put(recentsView, holder);
            }
            holder.start();
        }
    }

    private static Holder createHolder(View recentsView) {
        if (!(recentsView.getParent() instanceof ViewGroup)) return null;
        ViewGroup parent = (ViewGroup) recentsView.getParent();
        Context context = recentsView.getContext();

        TextView text = new TextView(context);
        text.setTextSize(13f);
        text.setGravity(Gravity.CENTER);
        text.setTextColor(resolveReadableColor(context));
        text.setShadowLayer(dp(context, 2), 0, dp(context, 1), 0x66000000);
        text.setPadding(dp(context, 14), dp(context, 5), dp(context, 14), dp(context, 5));
        text.setClickable(true);
        text.setFocusable(true);
        text.setContentDescription("Recent apps memory information");
        text.setOnClickListener(v -> openRunningServices(context));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.leftMargin = dp(context, 16);
        lp.rightMargin = dp(context, 16);
        lp.bottomMargin = getBottomInset(recentsView) + dp(context, 18);

        try {
            parent.addView(text, lp);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": add overlay failed: " + t);
            return null;
        }
        return new Holder(recentsView, parent, text);
    }

    private static final class Holder {
        final View recentsView;
        final ViewGroup parent;
        final TextView text;
        final Handler main = new Handler(Looper.getMainLooper());
        boolean running;

        final Runnable worker = new Runnable() {
            @Override public void run() {
                if (!running || !recentsView.isAttachedToWindow()) {
                    stop();
                    return;
                }
                Config config = readConfig(recentsView.getContext());
                if (!config.showMemory) {
                    stop();
                    return;
                }
                updateText(config);
                if (running) main.postDelayed(this, REFRESH_MS);
            }
        };

        Holder(View recentsView, ViewGroup parent, TextView text) {
            this.recentsView = recentsView;
            this.parent = parent;
            this.text = text;
        }

        void start() {
            if (text.getParent() == null) {
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                lp.leftMargin = dp(recentsView.getContext(), 16);
                lp.rightMargin = dp(recentsView.getContext(), 16);
                lp.bottomMargin = getBottomInset(recentsView) + dp(recentsView.getContext(), 18);
                parent.addView(text, lp);
            }
            text.setVisibility(View.VISIBLE);
            if (running) return;
            running = true;
            main.removeCallbacks(worker);
            main.post(worker);
        }

        void stop() {
            running = false;
            main.removeCallbacks(worker);
            text.setVisibility(View.GONE);
        }

        void updateText(Config config) {
            Context context = recentsView.getContext();
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);

            String available = Formatter.formatShortFileSize(context, info.availMem);
            String total = formatRoundedTotalRam(info.totalMem);
            String zram = null;
            if (config.showZram) {
                long zramBytes = readZramSize();
                if (zramBytes > 0) zram = Formatter.formatShortFileSize(context, zramBytes);
            }

            boolean zh = Locale.getDefault().getLanguage().startsWith("zh");
            if (zh) {
                text.setText(zram == null
                        ? "可用内存 " + available + " / " + total
                        : "可用内存 " + available + " / " + total + "  ·  ZRAM " + zram);
            } else {
                text.setText(zram == null
                        ? "Available " + available + " / " + total
                        : "Available " + available + " / " + total + "  ·  ZRAM " + zram);
            }
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) text.getLayoutParams();
            int wanted = getBottomInset(recentsView) + dp(context, 18);
            if (lp.bottomMargin != wanted) {
                lp.bottomMargin = wanted;
                text.setLayoutParams(lp);
            }
        }
    }

    private static Config readConfig(Context context) {
        try {
            Bundle b = context.getContentResolver().call(
                    ConfigKeys.URI, ConfigKeys.METHOD_GET_CONFIG, null, null);
            if (b != null) {
                return new Config(
                        b.getBoolean(ConfigKeys.RECENTS_MEMINFO, false),
                        b.getBoolean(ConfigKeys.RECENTS_MEMINFO_ZRAM, false));
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": config read failed: " + t);
        }
        return new Config(false, false);
    }

    private static final class Config {
        final boolean showMemory;
        final boolean showZram;
        Config(boolean showMemory, boolean showZram) {
            this.showMemory = showMemory;
            this.showZram = showZram;
        }
    }

    private static long readZramSize() {
        long bytes = readLongFile("/sys/block/zram0/disksize");
        if (bytes > 0) return bytes;

        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/swaps"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.contains("zram")) continue;
                String[] parts = line.trim().split("\\s+");
                if (parts.length > 2) {
                    try {
                        long kb = Long.parseLong(parts[2]);
                        if (kb > 0) return kb * 1024L;
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (IOException ignored) {}
        return 0L;
    }

    private static long readLongFile(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line = reader.readLine();
            return line == null ? 0L : Long.parseLong(line.trim());
        } catch (IOException | NumberFormatException ignored) {
            return 0L;
        }
    }

    private static String formatRoundedTotalRam(long bytes) {
        double gib = bytes / (1024.0 * 1024.0 * 1024.0);
        int[] known = {1, 2, 3, 4, 6, 8, 10, 12, 16, 24, 32, 48, 64};
        for (int value : known) {
            if (gib <= value) return value + " GB";
        }
        return Math.round(gib) + " GB";
    }

    private static int resolveReadableColor(Context context) {
        int night = context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return night == android.content.res.Configuration.UI_MODE_NIGHT_YES
                ? Color.WHITE : Color.rgb(32, 32, 32);
    }

    private static int getBottomInset(View view) {
        try {
            WindowInsets insets = view.getRootWindowInsets();
            if (insets != null) {
                return insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static void openRunningServices(Context context) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN)
                    .setClassName("com.android.settings",
                            "com.android.settings.Settings$DevRunningServicesActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable ignored) {}
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
