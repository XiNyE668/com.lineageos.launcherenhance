package com.lineageos.launcherenhance;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Places a proxy for Trebuchet's Clear all action beside Screenshot while keeping the original
 * ClearAllButton in RecentsView as an invisible layout placeholder. This avoids disturbing
 * RecentsView's page/index/scroll bookkeeping on Android 16 / LineageOS 23.2.
 */
public final class RecentsActionsHook implements IXposedHookLoadPackage {
    private static final String TARGET = "com.android.launcher3";
    private static final String TAG = "LauncherHub/RecentsActions";
    private static final long CONFIG_CACHE_MS = 2000L;
    private static final Map<View, Button> PROXIES = new WeakHashMap<>();

    private static volatile Config cachedConfig = new Config(false, ConfigKeys.CLEAR_ALL_SIDE_RIGHT);
    private static volatile long configLoadedAt;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET.equals(lpparam.packageName)) return;

        hookOverviewActions(lpparam.classLoader);
        hookRecentsView(lpparam.classLoader);
        hookOriginalClearAll(lpparam.classLoader);
    }

    private static void hookOverviewActions(ClassLoader cl) {
        try {
            Class<?> actions = XposedHelpers.findClass(
                    "com.android.quickstep.views.OverviewActionsView", cl);
            XposedHelpers.findAndHookMethod(actions, "onFinishInflate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof View) {
                        installProxy((View) param.thisObject);
                    }
                }
            });
            XposedBridge.log(TAG + ": OverviewActionsView hook OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": OverviewActionsView hook failed: " + t);
            XposedBridge.log(t);
        }
    }

    private static void hookRecentsView(ClassLoader cl) {
        try {
            Class<?> recents = XposedHelpers.findClass(
                    "com.android.quickstep.views.LauncherRecentsView", cl);
            XposedHelpers.findAndHookMethod(recents, "setOverviewStateEnabled", boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof View) {
                                syncOriginalClearAll((View) param.thisObject);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": LauncherRecentsView hook OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": LauncherRecentsView hook failed: " + t);
            XposedBridge.log(t);
        }
    }

    private static void hookOriginalClearAll(ClassLoader cl) {
        try {
            Class<?> clearAll = XposedHelpers.findClass(
                    "com.android.quickstep.views.ClearAllButton", cl);
            XposedHelpers.findAndHookMethod(clearAll, "onRecentsViewScroll",
                    int.class, boolean.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof View) {
                                suppressOriginalIfNeeded((View) param.thisObject);
                            }
                        }
                    });
            try {
                XposedHelpers.findAndHookMethod(clearAll, "setContentAlpha", float.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if (param.thisObject instanceof View) {
                                    suppressOriginalIfNeeded((View) param.thisObject);
                                }
                            }
                        });
            } catch (Throwable ignored) {
                // Kotlin accessor names can vary between Launcher revisions; scroll hook is enough.
            }
            XposedBridge.log(TAG + ": ClearAllButton hook OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ClearAllButton hook failed: " + t);
            XposedBridge.log(t);
        }
    }

    private static void installProxy(View actionsView) {
        Context context = actionsView.getContext();
        Config config = readConfig(context);
        if (!config.inline) return;

        synchronized (PROXIES) {
            if (PROXIES.containsKey(actionsView)) return;

            Object rowObject;
            try {
                rowObject = XposedHelpers.getObjectField(actionsView, "mActionButtons");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": mActionButtons unavailable: " + t);
                return;
            }
            if (!(rowObject instanceof LinearLayout)) return;
            LinearLayout row = (LinearLayout) rowObject;

            Resources res = context.getResources();
            int screenshotId = res.getIdentifier("action_screenshot", "id", TARGET);
            View screenshot = screenshotId == 0 ? null : actionsView.findViewById(screenshotId);
            if (screenshot == null) {
                XposedBridge.log(TAG + ": action_screenshot not found");
                return;
            }

            Button proxy = createProxyButton(context, screenshot);
            proxy.setOnClickListener(v -> invokeClearAll(actionsView, v));

            int screenshotIndex = row.indexOfChild(screenshot);
            if (screenshotIndex < 0) screenshotIndex = 0;
            int insertIndex = config.side == ConfigKeys.CLEAR_ALL_SIDE_LEFT
                    ? screenshotIndex : screenshotIndex + 1;
            insertIndex = Math.max(0, Math.min(insertIndex, row.getChildCount()));

            int spacing = dimensionByName(context, "overview_actions_button_spacing");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (config.side == ConfigKeys.CLEAR_ALL_SIDE_LEFT) {
                lp.rightMargin = spacing;
            } else {
                lp.leftMargin = spacing;
            }

            try {
                row.addView(proxy, insertIndex, lp);
                PROXIES.put(actionsView, proxy);
                row.requestLayout();
                XposedBridge.log(TAG + ": inline Clear all added "
                        + (config.side == ConfigKeys.CLEAR_ALL_SIDE_LEFT ? "left" : "right")
                        + " of Screenshot");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": add proxy failed: " + t);
            }
        }
    }

    private static Button createProxyButton(Context context, View screenshot) {
        Button button = new Button(context);
        int clearAllText = context.getResources().getIdentifier(
                "recents_clear_all", "string", TARGET);
        button.setText(clearAllText != 0 ? clearAllText : android.R.string.ok);
        if (clearAllText == 0) button.setText("Clear all");
        button.setAllCaps(false);
        button.setTextAlignment(screenshot.getTextAlignment());
        button.setTextDirection(screenshot.getTextDirection());
        button.setGravity(screenshot instanceof Button
                ? ((Button) screenshot).getGravity() : android.view.Gravity.CENTER);
        button.setPadding(screenshot.getPaddingLeft(), screenshot.getPaddingTop(),
                screenshot.getPaddingRight(), screenshot.getPaddingBottom());
        button.setMinimumHeight(screenshot.getMinimumHeight());
        button.setMinimumWidth(0);
        button.setElevation(screenshot.getElevation());

        if (screenshot instanceof Button) {
            Button source = (Button) screenshot;
            button.setTextColor(source.getTextColors());
            button.setTextSize(TypedValue.COMPLEX_UNIT_PX, source.getTextSize());
            button.setTypeface(source.getTypeface());
            button.setIncludeFontPadding(source.getIncludeFontPadding());
        }

        try {
            Drawable bg = screenshot.getBackground();
            if (bg != null && bg.getConstantState() != null) {
                button.setBackground(bg.getConstantState().newDrawable(
                        context.getResources()).mutate());
            } else if (bg != null) {
                button.setBackground(bg);
            }
            button.setBackgroundTintList(screenshot.getBackgroundTintList());
            button.setBackgroundTintMode(screenshot.getBackgroundTintMode());
            button.setStateListAnimator(screenshot.getStateListAnimator());
        } catch (Throwable ignored) {}

        button.setContentDescription(button.getText());
        return button;
    }

    private static void invokeClearAll(View actionsView, View source) {
        try {
            Resources res = actionsView.getResources();
            int overviewId = res.getIdentifier("overview_panel", "id", TARGET);
            View root = actionsView.getRootView();
            View recents = overviewId == 0 ? null : root.findViewById(overviewId);
            if (recents == null) {
                XposedBridge.log(TAG + ": overview_panel not found");
                return;
            }
            XposedHelpers.callMethod(recents, "dismissAllTasks", source);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": dismissAllTasks failed: " + t);
            XposedBridge.log(t);
        }
    }

    private static void syncOriginalClearAll(View recentsView) {
        try {
            Object value = XposedHelpers.getObjectField(recentsView, "mClearAllButton");
            if (!(value instanceof View)) return;
            View clearAll = (View) value;
            Config config = readConfig(recentsView.getContext());
            if (config.inline) {
                clearAll.setVisibility(View.INVISIBLE);
                clearAll.setAlpha(0f);
                clearAll.setImportantForAccessibility(
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            } else {
                clearAll.setVisibility(View.VISIBLE);
                clearAll.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": sync original ClearAll failed: " + t);
        }
    }

    private static void suppressOriginalIfNeeded(View clearAll) {
        if (!readConfig(clearAll.getContext()).inline) return;
        clearAll.setVisibility(View.INVISIBLE);
        clearAll.setAlpha(0f);
        clearAll.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    private static Config readConfig(Context context) {
        long now = SystemClock.uptimeMillis();
        if (now - configLoadedAt < CONFIG_CACHE_MS) return cachedConfig;
        synchronized (RecentsActionsHook.class) {
            now = SystemClock.uptimeMillis();
            if (now - configLoadedAt < CONFIG_CACHE_MS) return cachedConfig;
            try {
                Bundle b = context.getContentResolver().call(
                        ConfigKeys.URI, ConfigKeys.METHOD_GET_CONFIG, null, null);
                if (b != null) {
                    boolean inline = b.getBoolean(ConfigKeys.RECENTS_CLEAR_ALL_INLINE, false);
                    int side = b.getInt(ConfigKeys.RECENTS_CLEAR_ALL_SIDE,
                            ConfigKeys.CLEAR_ALL_SIDE_RIGHT);
                    if (side != ConfigKeys.CLEAR_ALL_SIDE_LEFT) {
                        side = ConfigKeys.CLEAR_ALL_SIDE_RIGHT;
                    }
                    cachedConfig = new Config(inline, side);
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": config read failed: " + t);
            }
            configLoadedAt = now;
            return cachedConfig;
        }
    }

    private static int dimensionByName(Context context, String name) {
        try {
            int id = context.getResources().getIdentifier(name, "dimen", TARGET);
            return id == 0 ? dp(context, 8) : context.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {
            return dp(context, 8);
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class Config {
        final boolean inline;
        final int side;
        Config(boolean inline, int side) {
            this.inline = inline;
            this.side = side;
        }
    }
}
