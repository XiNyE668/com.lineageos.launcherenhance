package com.lineageos.launcherenhance;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
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
 * Keeps Trebuchet's original ClearAllButton inside RecentsView as an invisible layout placeholder,
 * while exposing a working proxy beside Screenshot. The proxy delegates to the original button's
 * click listener, preserving Trebuchet's own dismiss animation and filtered-recents behaviour.
 */
public final class RecentsActionsHook implements IXposedHookLoadPackage {
    private static final String TARGET = "com.android.launcher3";
    private static final String TAG = "LauncherHub/RecentsActions";
    private static final Map<View, Button> PROXIES = new WeakHashMap<>();

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
                    if (param.thisObject instanceof View) installProxy((View) param.thisObject);
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
                                suppressOriginal((View) param.thisObject);
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
                                hideOriginalButton((View) param.thisObject);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": ClearAllButton hook OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ClearAllButton hook failed: " + t);
            XposedBridge.log(t);
        }
    }

    private static void installProxy(View actionsView) {
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

            Context context = actionsView.getContext();
            Resources res = context.getResources();
            int screenshotId = res.getIdentifier("action_screenshot", "id", TARGET);
            View screenshot = screenshotId == 0 ? null : actionsView.findViewById(screenshotId);
            if (screenshot == null) {
                XposedBridge.log(TAG + ": action_screenshot not found");
                return;
            }

            Button proxy = createProxyButton(context, screenshot);
            proxy.setOnClickListener(v -> invokeOriginalClearAll(actionsView));

            int side = readSide(context);
            int screenshotIndex = row.indexOfChild(screenshot);
            if (screenshotIndex < 0) screenshotIndex = 0;
            int insertIndex = side == ConfigKeys.CLEAR_ALL_SIDE_LEFT
                    ? screenshotIndex : screenshotIndex + 1;
            insertIndex = Math.max(0, Math.min(insertIndex, row.getChildCount()));

            int spacing = dimensionByName(context, "overview_actions_button_spacing");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (side == ConfigKeys.CLEAR_ALL_SIDE_LEFT) lp.rightMargin = spacing;
            else lp.leftMargin = spacing;

            try {
                row.addView(proxy, insertIndex, lp);
                PROXIES.put(actionsView, proxy);
                row.requestLayout();
                XposedBridge.log(TAG + ": inline Clear all added "
                        + (side == ConfigKeys.CLEAR_ALL_SIDE_LEFT ? "left" : "right")
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

    private static void invokeOriginalClearAll(View actionsView) {
        try {
            View recents = findRecents(actionsView);
            if (recents == null) {
                XposedBridge.log(TAG + ": overview_panel not found");
                return;
            }
            Object original = XposedHelpers.getObjectField(recents, "mClearAllButton");
            if (original instanceof View) {
                boolean handled = ((View) original).performClick();
                XposedBridge.log(TAG + ": original Clear all performClick=" + handled);
                if (handled) return;
            }
            // Fallback for unusual Launcher revisions where the click listener is unavailable.
            XposedHelpers.callMethod(recents, "dismissAllTasks", original instanceof View
                    ? original : recents);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Clear all delegation failed: " + t);
            XposedBridge.log(t);
        }
    }

    private static View findRecents(View actionsView) {
        Resources res = actionsView.getResources();
        int overviewId = res.getIdentifier("overview_panel", "id", TARGET);
        return overviewId == 0 ? null : actionsView.getRootView().findViewById(overviewId);
    }

    private static void suppressOriginal(View recentsView) {
        try {
            Object value = XposedHelpers.getObjectField(recentsView, "mClearAllButton");
            if (value instanceof View) hideOriginalButton((View) value);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": suppress original Clear all failed: " + t);
        }
    }

    private static void hideOriginalButton(View clearAll) {
        clearAll.setVisibility(View.INVISIBLE);
        clearAll.setAlpha(0f);
        clearAll.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    private static int readSide(Context context) {
        try {
            Bundle b = context.getContentResolver().call(
                    ConfigKeys.URI, ConfigKeys.METHOD_GET_CONFIG, null, null);
            if (b != null && b.getInt(ConfigKeys.RECENTS_CLEAR_ALL_SIDE,
                    ConfigKeys.CLEAR_ALL_SIDE_RIGHT) == ConfigKeys.CLEAR_ALL_SIDE_LEFT) {
                return ConfigKeys.CLEAR_ALL_SIDE_LEFT;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": config read failed: " + t);
        }
        return ConfigKeys.CLEAR_ALL_SIDE_RIGHT;
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
}
