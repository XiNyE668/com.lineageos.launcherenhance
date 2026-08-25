package com.lineageos.launcherenhance;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.AndroidAppHelper;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class LauncherEnhanceHook implements IXposedHookLoadPackage {
    private static final String TARGET = "com.android.launcher3";
    private static final String TAG = "LauncherEnhance";

    private static final int DISPLAY_WORKSPACE = 0;
    private static final int DISPLAY_ALL_APPS = 1;
    private static final int DISPLAY_FOLDER = 2;
    private static final int DISPLAY_TASKBAR = 5;
    private static final int DISPLAY_SEARCH_RESULT = 6;
    private static final int DISPLAY_SEARCH_RESULT_SMALL = 7;
    private static final int DISPLAY_PREDICTION_ROW = 8;
    private static final int DISPLAY_SEARCH_RESULT_APP_ROW = 9;

    private static final Map<String, String> hookStatus = new LinkedHashMap<>();
    private static final Paint badgeBackground = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint badgeText = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET.equals(lpparam.packageName)) return;

        log("init process=" + lpparam.processName + " Android=" + android.os.Build.VERSION.SDK_INT);
        hookApplicationAttach();
        safeHook("BubbleTextView", () -> hookBubbleTextView(lpparam.classLoader));
        safeHook("AllAppsStore", () -> hookAllAppsStore(lpparam.classLoader));
        safeHook("QSB", () -> hookQsb(lpparam.classLoader));
        safeHook("Folder", () -> hookFolder(lpparam.classLoader));
    }

    private static void hookApplicationAttach() {
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Context context = (Context) param.args[0];
                        if (context == null || !TARGET.equals(context.getPackageName())) return;
                        try {
                            ConfigClient.invalidate();
                            ConfigClient.get(context);
                            context.getContentResolver().registerContentObserver(
                                    ConfigKeys.URI, true,
                                    new ContentObserver(new Handler(Looper.getMainLooper())) {
                                        @Override
                                        public void onChange(boolean selfChange) {
                                            ConfigClient.invalidate();
                                            IconPackLoader.invalidate();
                                        }
                                    });
                            mark("ConfigProvider", "OK");
                        } catch (Throwable t) {
                            mark("ConfigProvider", "FAIL " + shortError(t));
                        }
                        reportStatus(context);
                    }
                });
    }

    private static void hookBubbleTextView(ClassLoader cl) {
        Class<?> bubble = XposedHelpers.findClass("com.android.launcher3.BubbleTextView", cl);
        Class<?> itemInfo = XposedHelpers.findClass("com.android.launcher3.model.data.ItemInfo", cl);
        Class<?> itemInfoWithIcon = XposedHelpers.findClass(
                "com.android.launcher3.model.data.ItemInfoWithIcon", cl);
        Class<?> workspaceItemInfo = XposedHelpers.findClass(
                "com.android.launcher3.model.data.WorkspaceItemInfo", cl);
        Class<?> appInfo = XposedHelpers.findClass("com.android.launcher3.model.data.AppInfo", cl);

        XposedBridge.hookAllConstructors(bubble, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof TextView) {
                    tuneBubble((TextView) param.thisObject);
                }
            }
        });

        XposedHelpers.findAndHookMethod(bubble, "applyLabel", itemInfo, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof TextView) {
                    applyCustomLabel((TextView) param.thisObject, param.args[0]);
                }
            }
        });

        XC_MethodHook iconHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof View && param.args.length > 0) {
                    applyIconPack((View) param.thisObject, param.args[0]);
                }
            }
        };
        XposedHelpers.findAndHookMethod(bubble, "applyFromWorkspaceItem", workspaceItemInfo, iconHook);
        XposedHelpers.findAndHookMethod(bubble, "applyFromApplicationInfo", appInfo, iconHook);
        XposedHelpers.findAndHookMethod(bubble, "applyFromItemInfoWithIcon", itemInfoWithIcon, iconHook);

        XposedHelpers.findAndHookMethod(bubble, "setIconVisible", boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!(Boolean) param.args[0]) return;
                        View view = (View) param.thisObject;
                        Object info = view.getTag();
                        if (info != null) applyIconPack(view, info);
                    }
                });

        XposedHelpers.findAndHookMethod(bubble, "onTouchEvent", MotionEvent.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        View view = (View) param.thisObject;
                        Config c = ConfigClient.get(view.getContext());
                        if (!c.pressScaleEnabled || getDisplay(view) == DISPLAY_TASKBAR) return;
                        MotionEvent e = (MotionEvent) param.args[0];
                        float scale = c.pressScalePercent / 100f;
                        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                            view.animate().cancel();
                            view.animate().scaleX(scale).scaleY(scale).setDuration(65).start();
                        } else if (e.getActionMasked() == MotionEvent.ACTION_UP
                                || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                            view.animate().cancel();
                            view.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(bubble, "onDraw", Canvas.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                View view = (View) param.thisObject;
                Config c = ConfigClient.get(view.getContext());
                if (!c.showBadgeCount || c.hideDots) return;
                drawBadgeCount(view, (Canvas) param.args[0], c);
            }
        });
    }

    private static void tuneBubble(TextView view) {
        try {
            Config c = ConfigClient.get(view.getContext());
            int display = getDisplay(view);
            boolean workspaceLike = isWorkspaceLike(display);
            boolean drawerLike = isDrawerLike(display);

            if (workspaceLike || drawerLike) {
                int originalIconSize = XposedHelpers.getIntField(view, "mIconSize");
                int iconScale = workspaceLike ? c.workspaceIconScale : c.drawerIconScale;
                XposedHelpers.setIntField(view, "mIconSize",
                        Math.max(1, Math.round(originalIconSize * iconScale / 100f)));

                float originalTextPx = view.getTextSize();
                int textScale = workspaceLike ? c.workspaceLabelScale : c.drawerLabelScale;
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        Math.max(1f, originalTextPx * textScale / 100f));

                int gap = view.getCompoundDrawablePadding()
                        + dp(view.getContext(), c.labelGapDeltaDp);
                view.setCompoundDrawablePadding(Math.max(0, gap));

                boolean hide = workspaceLike ? c.hideWorkspaceLabels : c.hideDrawerLabels;
                if (hide) {
                    XposedHelpers.setBooleanField(view, "mShouldShowLabel", false);
                    view.setText("");
                }
            }

            if (c.hideDots || c.showBadgeCount) {
                XposedHelpers.callMethod(view, "setForceHideDot", true);
            }
            if (c.dotColor != 0) {
                Object dotParams = XposedHelpers.getObjectField(view, "mDotParams");
                XposedHelpers.callMethod(dotParams, "setDotColor", c.dotColor);
            }
        } catch (Throwable t) {
            log("tuneBubble: " + t);
        }
    }

    private static void applyCustomLabel(TextView view, Object info) {
        try {
            Config c = ConfigClient.get(view.getContext());
            int display = getDisplay(view);
            if ((isWorkspaceLike(display) && c.hideWorkspaceLabels)
                    || (isDrawerLike(display) && c.hideDrawerLabels)) {
                view.setText("");
                return;
            }
            String pkg = packageNameFromInfo(info);
            if (pkg == null) return;
            String custom = c.renamedPackages.get(pkg);
            if (custom != null && !custom.isEmpty()) {
                XposedHelpers.callMethod(view, "applyLabel", (CharSequence) custom);
            }
        } catch (Throwable t) {
            log("applyCustomLabel: " + t);
        }
    }

    private static void applyIconPack(View view, Object info) {
        try {
            Config c = ConfigClient.get(view.getContext());
            if (c.iconPackPackage.isEmpty()) return;
            ComponentName component = componentFromInfo(info);
            if (component == null) return;
            Drawable icon = IconPackLoader.getDrawable(view.getContext(), c.iconPackPackage, component);
            if (icon != null) XposedHelpers.callMethod(view, "applyCompoundDrawables", icon);
        } catch (Throwable t) {
            log("applyIconPack: " + t);
        }
    }

    private static void hookAllAppsStore(ClassLoader cl) {
        Class<?> store = XposedHelpers.findClass("com.android.launcher3.allapps.AllAppsStore", cl);
        Class<?> appInfo = XposedHelpers.findClass("com.android.launcher3.model.data.AppInfo", cl);
        Class<?> arrayClass = Array.newInstance(appInfo, 0).getClass();

        XposedHelpers.findAndHookMethod(store, "setApps", arrayClass, int.class, Map.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object apps = param.args[0];
                        if (apps == null) return;
                        Context context = currentContext();
                        if (context == null) return;
                        Config c = ConfigClient.get(context);
                        if (c.hiddenPackages.isEmpty()) return;

                        int len = Array.getLength(apps);
                        List<Object> kept = new ArrayList<>(len);
                        UserHandle currentUser = Process.myUserHandle();
                        for (int i = 0; i < len; i++) {
                            Object app = Array.get(apps, i);
                            String pkg = packageNameFromInfo(app);
                            Object user = getFieldQuietly(app, "user");
                            boolean sameUser = user instanceof UserHandle
                                    && currentUser.equals(user);
                            if (sameUser && pkg != null && c.hiddenPackages.contains(pkg)) continue;
                            kept.add(app);
                        }
                        if (kept.size() == len) return;
                        Object filtered = Array.newInstance(appInfo, kept.size());
                        for (int i = 0; i < kept.size(); i++) Array.set(filtered, i, kept.get(i));
                        param.args[0] = filtered;
                        log("AllAppsStore filtered " + (len - kept.size()) + " hidden app(s)");
                    }
                });
    }

    private static void hookQsb(ClassLoader cl) {
        Class<?> qsb = XposedHelpers.findClass("com.android.launcher3.qsb.QsbContainerView", cl);
        XposedBridge.hookAllConstructors(qsb, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof View) {
                    View view = (View) param.thisObject;
                    if (ConfigClient.get(view.getContext()).hideQsb) view.setVisibility(View.GONE);
                }
            }
        });
    }

    private static void hookFolder(ClassLoader cl) {
        Class<?> folder = XposedHelpers.findClass("com.android.launcher3.folder.Folder", cl);
        XposedHelpers.findAndHookMethod(folder, "centerAboutIcon", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                View view = (View) param.thisObject;
                if (!ConfigClient.get(view.getContext()).centerFolder) return;
                try {
                    Object activityContext = XposedHelpers.getObjectField(view, "mActivityContext");
                    Rect bounding = new Rect((Rect) XposedHelpers.callMethod(
                            activityContext, "getFolderBoundingBox"));
                    ViewGroup.LayoutParams lp = view.getLayoutParams();
                    if (lp == null || lp.width <= 0 || lp.height <= 0) return;
                    int x = bounding.left + Math.max(0, (bounding.width() - lp.width) / 2);
                    int y = bounding.top + Math.max(0, (bounding.height() - lp.height) / 2);
                    XposedHelpers.setIntField(lp, "x", x);
                    XposedHelpers.setIntField(lp, "y", y);
                    view.setPivotX(lp.width / 2f);
                    view.setPivotY(lp.height / 2f);
                    view.requestLayout();
                } catch (Throwable t) {
                    log("centerFolder: " + t);
                }
            }
        });
    }

    private static void drawBadgeCount(View view, Canvas canvas, Config c) {
        try {
            Object dotInfo = XposedHelpers.getObjectField(view, "mDotInfo");
            if (dotInfo == null) return;
            int count = (Integer) XposedHelpers.callMethod(dotInfo, "getNotificationCount");
            if (count <= 0) return;

            Rect iconBounds = new Rect();
            XposedHelpers.callMethod(view, "getIconBounds", iconBounds);
            String text = count > 99 ? "99+" : Integer.toString(count);
            float density = view.getResources().getDisplayMetrics().density;
            float height = 16f * density;
            float textSize = 9f * view.getResources().getDisplayMetrics().scaledDensity;

            int color = c.dotColor != 0 ? c.dotColor : resolveAccent(view.getContext());
            badgeBackground.setColor(color);
            badgeBackground.setStyle(Paint.Style.FILL);
            badgeText.setColor(Color.WHITE);
            badgeText.setTextSize(textSize);
            badgeText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            badgeText.setTextAlign(Paint.Align.CENTER);

            float width = Math.max(height, badgeText.measureText(text) + 8f * density);
            float cx = iconBounds.right - 2f * density;
            float cy = iconBounds.top + 6f * density;
            RectF r = new RectF(cx - width / 2f, cy - height / 2f,
                    cx + width / 2f, cy + height / 2f);
            canvas.drawRoundRect(r, height / 2f, height / 2f, badgeBackground);
            Paint.FontMetrics fm = badgeText.getFontMetrics();
            float baseline = cy - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(text, cx, baseline, badgeText);
        } catch (Throwable ignored) {}
    }

    private static int resolveAccent(Context context) {
        TypedValue tv = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.colorAccent, tv, true)
                && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) return tv.data;
        return Color.rgb(33, 150, 243);
    }

    private static int getDisplay(View view) {
        try { return XposedHelpers.getIntField(view, "mDisplay"); }
        catch (Throwable t) { return -1; }
    }

    private static boolean isWorkspaceLike(int display) {
        return display == DISPLAY_WORKSPACE || display == DISPLAY_FOLDER;
    }

    private static boolean isDrawerLike(int display) {
        return display == DISPLAY_ALL_APPS
                || display == DISPLAY_SEARCH_RESULT
                || display == DISPLAY_SEARCH_RESULT_SMALL
                || display == DISPLAY_PREDICTION_ROW
                || display == DISPLAY_SEARCH_RESULT_APP_ROW;
    }

    private static ComponentName componentFromInfo(Object info) {
        if (info == null) return null;
        try {
            Object value = XposedHelpers.callMethod(info, "getTargetComponent");
            if (value instanceof ComponentName) return (ComponentName) value;
        } catch (Throwable ignored) {}
        Object value = getFieldQuietly(info, "componentName");
        return value instanceof ComponentName ? (ComponentName) value : null;
    }

    private static String packageNameFromInfo(Object info) {
        ComponentName cn = componentFromInfo(info);
        return cn == null ? null : cn.getPackageName();
    }

    private static Object getFieldQuietly(Object object, String name) {
        try { return XposedHelpers.getObjectField(object, name); }
        catch (Throwable ignored) { return null; }
    }

    private static Context currentContext() {
        try { return AndroidAppHelper.currentApplication(); }
        catch (Throwable t) { return null; }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static void safeHook(String name, Runnable runnable) {
        try {
            runnable.run();
            mark(name, "OK");
        } catch (Throwable t) {
            mark(name, "FAIL " + shortError(t));
            log(name + " hook failed: " + t);
            XposedBridge.log(t);
        }
    }

    private static synchronized void mark(String key, String value) {
        hookStatus.put(key, value);
    }

    private static void reportStatus(Context context) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("API ").append(android.os.Build.VERSION.SDK_INT)
                    .append(" · ").append(android.os.Build.DISPLAY).append('\n');
            synchronized (LauncherEnhanceHook.class) {
                for (Map.Entry<String, String> e : hookStatus.entrySet()) {
                    sb.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
                }
            }
            Bundle extras = new Bundle();
            extras.putString(ConfigKeys.STATUS, sb.toString().trim());
            context.getContentResolver().call(ConfigKeys.URI,
                    ConfigKeys.METHOD_REPORT_STATUS, null, extras);
        } catch (Throwable t) {
            log("reportStatus failed: " + t);
        }
    }

    private static String shortError(Throwable t) {
        String name = t.getClass().getSimpleName();
        String message = t.getMessage();
        return message == null ? name : name + ": " + message;
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private static final class ConfigClient {
        private static volatile Config cache = Config.defaults();
        private static volatile long loadedAt;

        static Config get(Context context) {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - loadedAt < 3000) return cache;
            synchronized (ConfigClient.class) {
                now = android.os.SystemClock.uptimeMillis();
                if (now - loadedAt < 3000) return cache;
                try {
                    Bundle b = context.getContentResolver().call(ConfigKeys.URI,
                            ConfigKeys.METHOD_GET_CONFIG, null, null);
                    if (b != null) cache = Config.fromBundle(b);
                } catch (Throwable t) {
                    log("config read failed: " + t);
                }
                loadedAt = now;
                return cache;
            }
        }

        static void invalidate() { loadedAt = 0; }
    }

    private static final class Config {
        final boolean hideWorkspaceLabels;
        final boolean hideDrawerLabels;
        final int workspaceIconScale;
        final int drawerIconScale;
        final int workspaceLabelScale;
        final int drawerLabelScale;
        final int labelGapDeltaDp;
        final boolean hideQsb;
        final boolean centerFolder;
        final boolean hideDots;
        final boolean showBadgeCount;
        final int dotColor;
        final boolean pressScaleEnabled;
        final int pressScalePercent;
        final String iconPackPackage;
        final Set<String> hiddenPackages;
        final Map<String, String> renamedPackages;

        Config(boolean hideWorkspaceLabels, boolean hideDrawerLabels,
                int workspaceIconScale, int drawerIconScale,
                int workspaceLabelScale, int drawerLabelScale,
                int labelGapDeltaDp, boolean hideQsb, boolean centerFolder,
                boolean hideDots, boolean showBadgeCount, int dotColor,
                boolean pressScaleEnabled, int pressScalePercent,
                String iconPackPackage, Set<String> hiddenPackages,
                Map<String, String> renamedPackages) {
            this.hideWorkspaceLabels = hideWorkspaceLabels;
            this.hideDrawerLabels = hideDrawerLabels;
            this.workspaceIconScale = workspaceIconScale;
            this.drawerIconScale = drawerIconScale;
            this.workspaceLabelScale = workspaceLabelScale;
            this.drawerLabelScale = drawerLabelScale;
            this.labelGapDeltaDp = labelGapDeltaDp;
            this.hideQsb = hideQsb;
            this.centerFolder = centerFolder;
            this.hideDots = hideDots;
            this.showBadgeCount = showBadgeCount;
            this.dotColor = dotColor;
            this.pressScaleEnabled = pressScaleEnabled;
            this.pressScalePercent = pressScalePercent;
            this.iconPackPackage = iconPackPackage == null ? "" : iconPackPackage;
            this.hiddenPackages = hiddenPackages;
            this.renamedPackages = renamedPackages;
        }

        static Config defaults() {
            return new Config(false, false, 100, 100, 100, 100, 0,
                    false, false, false, false, 0, false, 92, "",
                    Collections.emptySet(), Collections.emptyMap());
        }

        static Config fromBundle(Bundle b) {
            Set<String> hidden = new HashSet<>();
            ArrayList<String> hiddenList = b.getStringArrayList(ConfigKeys.HIDDEN_PACKAGES);
            if (hiddenList != null) hidden.addAll(hiddenList);

            Map<String, String> renames = new HashMap<>();
            ArrayList<String> renameList = b.getStringArrayList(ConfigKeys.RENAMED_PACKAGES);
            if (renameList != null) {
                for (String item : renameList) {
                    int split = item.indexOf('\t');
                    if (split > 0 && split < item.length() - 1) {
                        renames.put(item.substring(0, split), item.substring(split + 1));
                    }
                }
            }

            return new Config(
                    b.getBoolean(ConfigKeys.HIDE_WORKSPACE_LABELS, false),
                    b.getBoolean(ConfigKeys.HIDE_DRAWER_LABELS, false),
                    clampScale(b.getInt(ConfigKeys.WORKSPACE_ICON_SCALE, 100)),
                    clampScale(b.getInt(ConfigKeys.DRAWER_ICON_SCALE, 100)),
                    clampScale(b.getInt(ConfigKeys.WORKSPACE_LABEL_SCALE, 100)),
                    clampScale(b.getInt(ConfigKeys.DRAWER_LABEL_SCALE, 100)),
                    Math.max(-16, Math.min(32, b.getInt(ConfigKeys.LABEL_GAP_DELTA_DP, 0))),
                    b.getBoolean(ConfigKeys.HIDE_QSB, false),
                    b.getBoolean(ConfigKeys.CENTER_FOLDER, false),
                    b.getBoolean(ConfigKeys.HIDE_DOTS, false),
                    b.getBoolean(ConfigKeys.SHOW_BADGE_COUNT, false),
                    b.getInt(ConfigKeys.DOT_COLOR, 0),
                    b.getBoolean(ConfigKeys.PRESS_SCALE_ENABLED, false),
                    Math.max(70, Math.min(100, b.getInt(ConfigKeys.PRESS_SCALE_PERCENT, 92))),
                    b.getString(ConfigKeys.ICON_PACK, ""),
                    Collections.unmodifiableSet(hidden),
                    Collections.unmodifiableMap(renames));
        }

        private static int clampScale(int value) {
            return Math.max(50, Math.min(160, value));
        }
    }
}
