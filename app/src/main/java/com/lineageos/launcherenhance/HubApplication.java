package com.lineageos.launcherenhance;

import android.app.Activity;
import android.app.Application;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Small UI-only application layer. The Xposed hooks remain isolated from the module UI process.
 */
public final class HubApplication extends Application {
    private static final int HUB_ORANGE = Color.rgb(255, 153, 0);

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) {
                activity.getWindow().getDecorView().post(() -> enhanceMainActivity(activity));
            }
            @Override public void onActivityResumed(Activity activity) {
                activity.getWindow().getDecorView().post(() -> enhanceMainActivity(activity));
            }
            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }

    private void enhanceMainActivity(Activity activity) {
        if (!MainActivity.class.getName().equals(activity.getClass().getName())) return;
        View rootView = activity.findViewById(android.R.id.content);
        LinearLayout root = findSettingsRoot(rootView);
        if (root == null) return;

        applyHubWordmark(root);
        if (!containsText(root, "最近任务")) {
            injectRecentsSettings(activity, root);
        }
    }

    private void applyHubWordmark(ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof TextView) {
                TextView tv = (TextView) child;
                CharSequence text = tv.getText();
                if (text != null && "Launcher Enhance".contentEquals(text)) {
                    String full = "Launcher Hub";
                    SpannableString s = new SpannableString(full);
                    int hubStart = full.indexOf("Hub");
                    s.setSpan(new StyleSpan(Typeface.BOLD), 0, full.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    s.setSpan(new BackgroundColorSpan(HUB_ORANGE), hubStart, full.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    s.setSpan(new ForegroundColorSpan(Color.BLACK), hubStart, full.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    tv.setText(s);
                    tv.setContentDescription("Launcher Hub");
                }
            }
            if (child instanceof ViewGroup) applyHubWordmark((ViewGroup) child);
        }
    }

    private void injectRecentsSettings(Activity activity, LinearLayout root) {
        int insertAt = root.getChildCount();
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof TextView
                    && "应用配置".contentEquals(((TextView) child).getText())) {
                insertAt = i;
                break;
            }
        }

        SharedPreferences prefs = activity.getSharedPreferences(ConfigKeys.PREFS, MODE_PRIVATE);

        TextView section = new TextView(activity);
        section.setText("最近任务");
        section.setTextSize(18);
        section.setTypeface(Typeface.DEFAULT_BOLD);
        section.setPadding(0, dp(activity, 20), 0, dp(activity, 6));

        Switch memory = new Switch(activity);
        memory.setText("在最近任务底部显示内存信息");
        memory.setTextSize(16);
        memory.setPadding(0, dp(activity, 6), 0, dp(activity, 6));
        memory.setChecked(prefs.getBoolean(ConfigKeys.RECENTS_MEMINFO, false));

        Switch zram = new Switch(activity);
        zram.setText("内存信息同时显示 ZRAM");
        zram.setTextSize(16);
        zram.setPadding(0, dp(activity, 6), 0, dp(activity, 6));
        zram.setChecked(prefs.getBoolean(ConfigKeys.RECENTS_MEMINFO_ZRAM, false));
        zram.setEnabled(memory.isChecked());

        memory.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(ConfigKeys.RECENTS_MEMINFO, checked).apply();
            zram.setEnabled(checked);
            notifyConfig(activity);
        });
        zram.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(ConfigKeys.RECENTS_MEMINFO_ZRAM, checked).apply();
            notifyConfig(activity);
        });

        TextView hint = new TextView(activity);
        hint.setText("参考 crDroid 12.x MemInfoView：仅在 Overview 可见时每 3 秒刷新；ZRAM 优先读取 /sys/block/zram0/disksize，失败时回退 /proc/swaps。");
        hint.setTextSize(12);
        hint.setAlpha(0.72f);
        hint.setPadding(0, 0, 0, dp(activity, 4));

        root.addView(section, insertAt++);
        root.addView(memory, insertAt++);
        root.addView(zram, insertAt++);
        root.addView(hint, insertAt);
    }

    private static void notifyConfig(Activity activity) {
        try {
            activity.getContentResolver().notifyChange(ConfigKeys.URI, null);
        } catch (Throwable ignored) {}
    }

    private static LinearLayout findSettingsRoot(View view) {
        if (view instanceof LinearLayout) {
            LinearLayout ll = (LinearLayout) view;
            if (containsText(ll, "图标与文字") && containsText(ll, "应用配置")) return ll;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                LinearLayout found = findSettingsRoot(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean containsText(View view, String target) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            return text != null && target.contentEquals(text);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsText(group.getChildAt(i), target)) return true;
            }
        }
        return false;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
