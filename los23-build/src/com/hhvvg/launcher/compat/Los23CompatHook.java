package com.hhvvg.launcher.compat;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/** LineageOS 23.2 compatibility hooks for Launcher3Customizer 1.0.1. */
public final class Los23CompatHook implements IXposedHookLoadPackage {
    private static final String TAG = "L3C/LOS23";
    private static final String LAUNCHER_PKG = "com.android.launcher3";
    private static final Map<Object, Object> MEMORY_VIEWS = new WeakHashMap<>();
    private static volatile Object cachedService;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!LAUNCHER_PKG.equals(lpparam.packageName)) return;
        safe("BubbleTextView", () -> hookBubbleTextView(lpparam.classLoader));
        safe("DeviceProfile spring effect", () -> hookSpringLoadedEffect(lpparam.classLoader));
        safe("Overview actions", () -> hookOverviewActions(lpparam.classLoader));
        XposedBridge.log(TAG + ": LOS23.2 compatibility hooks registered");
    }

    private static void hookBubbleTextView(ClassLoader loader) {
        Class<?> bubble = XposedHelpers.findClass("com.android.launcher3.BubbleTextView", loader);
        XposedBridge.hookAllConstructors(bubble, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try { applyBubbleSettings(param.thisObject); }
                catch (Throwable t) { XposedBridge.log(TAG + ": BubbleTextView apply failed: " + t); }
            }
        });
    }

    private static void applyBubbleSettings(Object view) throws Throwable {
        int display = XposedHelpers.getIntField(view, "mDisplay");
        boolean workspace = display == 0;
        boolean allApps = display == 1 || display == 8 || display == 9;
        boolean folder = display == 2;
        if (!workspace && !allApps && !folder) return;

        float iconScale = clamp(getServiceFloat("getIconScale", 1f), 0.50f, 1.60f);
        float textScale = clamp(getServiceFloat("getIconTextScale", 1f), 0.50f, 1.60f);
        float paddingScale = clamp(getServiceFloat("getIconDrawablePaddingScale", 1f), 0.0f, 2.0f);

        if (Float.compare(iconScale, 1f) != 0) {
            int iconSize = XposedHelpers.getIntField(view, "mIconSize");
            XposedHelpers.setIntField(view, "mIconSize", Math.max(1, Math.round(iconSize * iconScale)));
        }
        if (Float.compare(textScale, 1f) != 0) {
            Object n = XposedHelpers.callMethod(view, "getTextSize");
            float size = n instanceof Number ? ((Number) n).floatValue() : 0f;
            if (size > 0f) XposedHelpers.callMethod(view, "setTextSize", 0, size * textScale);
        }
        if (Float.compare(paddingScale, 1f) != 0) {
            Object n = XposedHelpers.callMethod(view, "getCompoundDrawablePadding");
            int padding = n instanceof Number ? ((Number) n).intValue() : 0;
            XposedHelpers.callMethod(view, "setCompoundDrawablePadding", Math.max(0, Math.round(padding * paddingScale)));
        }
        boolean showLabel = allApps
                ? getServiceBoolean("isAllAppsIconTextVisible", true)
                : getServiceBoolean("isIconTextVisible", true);
        if (!showLabel) XposedHelpers.setBooleanField(view, "mShouldShowLabel", false);
    }

    private static void hookSpringLoadedEffect(ClassLoader loader) {
        Class<?> dp = XposedHelpers.findClass("com.android.launcher3.DeviceProfile", loader);
        XposedBridge.hookAllMethods(dp, "getWorkspaceSpringLoadScale", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (getServiceBoolean("isUseCustomSpringLoadedEffect", false)) param.setResult(Float.valueOf(1f));
                } catch (Throwable t) { XposedBridge.log(TAG + ": spring effect failed: " + t); }
            }
        });
    }

    private static void hookOverviewActions(ClassLoader loader) {
        Class<?> actions = XposedHelpers.findClass("com.android.quickstep.views.OverviewActionsView", loader);
        XposedBridge.hookAllMethods(actions, "onFinishInflate", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try { installOverviewUi(param.thisObject); }
                catch (Throwable t) { XposedBridge.log(TAG + ": overview UI install failed: " + t); }
            }
        });
        XposedBridge.hookAllMethods(actions, "updateHiddenFlags", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) { updateMemoryText(param.thisObject); }
        });
    }

    private static void installOverviewUi(final Object actionsView) throws Throwable {
        synchronized (MEMORY_VIEWS) { if (MEMORY_VIEWS.containsKey(actionsView)) return; }
        Object context = XposedHelpers.callMethod(actionsView, "getContext");
        Object resources = XposedHelpers.callMethod(context, "getResources");
        String packageName = String.valueOf(XposedHelpers.callMethod(context, "getPackageName"));
        int screenshotId = ((Number) XposedHelpers.callMethod(resources, "getIdentifier", "action_screenshot", "id", packageName)).intValue();
        Object screenshot = XposedHelpers.callMethod(actionsView, "findViewById", screenshotId);
        Object actionButtons = XposedHelpers.getObjectField(actionsView, "mActionButtons");
        if (screenshot == null || actionButtons == null) throw new IllegalStateException("overview action views not found");

        Object clearButton = newAndroidView("android.widget.Button", context);
        XposedHelpers.callMethod(clearButton, "setText", "Clear all");
        copyButtonPresentation(screenshot, clearButton);
        Object screenshotLp = XposedHelpers.callMethod(screenshot, "getLayoutParams");
        if (screenshotLp != null) XposedHelpers.callMethod(clearButton, "setLayoutParams", screenshotLp);

        Class<?> listenerClass = Class.forName("android.view.View$OnClickListener", false, actionsView.getClass().getClassLoader());
        Object listener = Proxy.newProxyInstance(Los23CompatHook.class.getClassLoader(), new Class<?>[]{listenerClass},
                new InvocationHandler() {
                    @Override public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("onClick".equals(method.getName())) {
                            try { clickNativeClearAll(actionsView); }
                            catch (Throwable t) { XposedBridge.log(TAG + ": Clear all failed: " + t); }
                        }
                        return null;
                    }
                });
        XposedHelpers.callMethod(clearButton, "setOnClickListener", listener);
        int screenshotIndex = ((Number) XposedHelpers.callMethod(actionButtons, "indexOfChild", screenshot)).intValue();
        XposedHelpers.callMethod(actionButtons, "addView", clearButton, Math.max(0, screenshotIndex));

        try {
            int spacingId = ((Number) XposedHelpers.callMethod(resources, "getIdentifier", "overview_actions_button_spacing", "dimen", packageName)).intValue();
            int spacing = spacingId != 0
                    ? ((Number) XposedHelpers.callMethod(resources, "getDimensionPixelSize", spacingId)).intValue()
                    : dp(context, 8);
            Object lp = XposedHelpers.callMethod(screenshot, "getLayoutParams");
            XposedHelpers.callMethod(lp, "setMarginStart", spacing);
            XposedHelpers.callMethod(screenshot, "setLayoutParams", lp);
        } catch (Throwable ignored) {}

        Object memory = newAndroidView("android.widget.TextView", context);
        XposedHelpers.callMethod(memory, "setTextSize", 12f);
        XposedHelpers.callMethod(memory, "setGravity", 17);
        XposedHelpers.callMethod(memory, "setAlpha", 0.85f);
        XposedHelpers.callMethod(memory, "setSingleLine", true);
        Class<?> flpClass = Class.forName("android.widget.FrameLayout$LayoutParams", false, actionsView.getClass().getClassLoader());
        Constructor<?> lpCtor = flpClass.getConstructor(int.class, int.class, int.class);
        Object memoryLp = lpCtor.newInstance(-2, -2, 81);
        int actionHeight = 0;
        try {
            Object abLp = XposedHelpers.callMethod(actionButtons, "getLayoutParams");
            Field height = findField(abLp.getClass(), "height");
            if (height != null) actionHeight = height.getInt(abLp);
        } catch (Throwable ignored) {}
        if (actionHeight <= 0) actionHeight = dp(context, 48);
        Field bottomMargin = findField(memoryLp.getClass(), "bottomMargin");
        if (bottomMargin != null) bottomMargin.setInt(memoryLp, actionHeight + dp(context, 6));
        XposedHelpers.callMethod(memory, "setLayoutParams", memoryLp);
        XposedHelpers.callMethod(actionsView, "addView", memory);
        synchronized (MEMORY_VIEWS) { MEMORY_VIEWS.put(actionsView, memory); }
        updateMemoryText(actionsView);
        XposedBridge.log(TAG + ": Memory/ZRAM + Clear all installed");
    }

    private static void copyButtonPresentation(Object src, Object dst) {
        try { XposedHelpers.callMethod(dst, "setTextColor", XposedHelpers.callMethod(src, "getTextColors")); } catch (Throwable ignored) {}
        try { XposedHelpers.callMethod(dst, "setTextSize", 0, XposedHelpers.callMethod(src, "getTextSize")); } catch (Throwable ignored) {}
        try { XposedHelpers.callMethod(dst, "setTypeface", XposedHelpers.callMethod(src, "getTypeface")); } catch (Throwable ignored) {}
        try { XposedHelpers.callMethod(dst, "setMinHeight", XposedHelpers.callMethod(src, "getMinHeight")); } catch (Throwable ignored) {}
        try { XposedHelpers.callMethod(dst, "setMinWidth", XposedHelpers.callMethod(src, "getMinWidth")); } catch (Throwable ignored) {}
    }

    private static void clickNativeClearAll(Object actionsView) throws Throwable {
        Object root = XposedHelpers.callMethod(actionsView, "getRootView");
        Object recents = findRecentsView(root, 0);
        if (recents == null) throw new IllegalStateException("RecentsView not found");
        Object nativeClear = XposedHelpers.getObjectField(recents, "mClearAllButton");
        if (nativeClear == null) throw new IllegalStateException("native ClearAllButton not found");
        XposedHelpers.callMethod(nativeClear, "performClick");
    }

    private static Object findRecentsView(Object view, int depth) {
        if (view == null || depth > 14) return null;
        if (isClassOrSuper(view.getClass(), "com.android.quickstep.views.RecentsView")) return view;
        try {
            int count = ((Number) XposedHelpers.callMethod(view, "getChildCount")).intValue();
            for (int i = 0; i < count; i++) {
                Object found = findRecentsView(XposedHelpers.callMethod(view, "getChildAt", i), depth + 1);
                if (found != null) return found;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean isClassOrSuper(Class<?> c, String name) {
        for (Class<?> x = c; x != null; x = x.getSuperclass()) if (name.equals(x.getName())) return true;
        return false;
    }

    private static void updateMemoryText(Object actionsView) {
        Object memory;
        synchronized (MEMORY_VIEWS) { memory = MEMORY_VIEWS.get(actionsView); }
        if (memory == null) return;
        try { XposedHelpers.callMethod(memory, "setText", memoryText()); } catch (Throwable ignored) {}
    }

    private static String memoryText() {
        long memTotal = 0, memAvailable = 0, swapTotal = 0, swapFree = 0;
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("MemTotal:")) memTotal = kbValue(line);
                else if (line.startsWith("MemAvailable:")) memAvailable = kbValue(line);
                else if (line.startsWith("SwapTotal:")) swapTotal = kbValue(line);
                else if (line.startsWith("SwapFree:")) swapFree = kbValue(line);
            }
        } catch (Throwable ignored) {}
        long swapUsed = Math.max(0, swapTotal - swapFree);
        return String.format(Locale.US, "Memory: %.1fG free / %.1fG   ZRAM: %.1fG / %.1fG",
                gib(memAvailable), gib(memTotal), gib(swapUsed), gib(swapTotal));
    }

    private static long kbValue(String line) {
        String[] p = line.trim().split("\\s+");
        return p.length > 1 ? Long.parseLong(p[1]) : 0;
    }
    private static double gib(long kb) { return kb / 1048576.0; }

    private static Object newAndroidView(String className, Object context) throws Throwable {
        ClassLoader loader = context.getClass().getClassLoader();
        Class<?> cls = Class.forName(className, false, loader);
        Class<?> ctx = Class.forName("android.content.Context", false, loader);
        return cls.getConstructor(ctx).newInstance(context);
    }

    private static int dp(Object context, int dp) {
        try {
            Object res = XposedHelpers.callMethod(context, "getResources");
            Object dm = XposedHelpers.callMethod(res, "getDisplayMetrics");
            Field f = findField(dm.getClass(), "density");
            float density = f != null ? f.getFloat(dm) : 1f;
            return Math.max(1, Math.round(dp * density));
        } catch (Throwable t) { return dp; }
    }

    private static Field findField(Class<?> c, String name) {
        for (Class<?> x = c; x != null; x = x.getSuperclass()) {
            try { Field f = x.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object getService() {
        Object s = cachedService;
        if (s != null) return s;
        try {
            Class<?> serviceClass = Class.forName("com.hhvvg.launcher.service.LauncherService", false, Los23CompatHook.class.getClassLoader());
            Method m = serviceClass.getDeclaredMethod("getLauncherService");
            m.setAccessible(true); s = m.invoke(null);
            if (s != null) cachedService = s;
            return s;
        } catch (Throwable t) { return null; }
    }

    private static Object serviceCall(String name) throws Throwable {
        Object service = getService();
        if (service == null) return null;
        Method method = null;
        for (Method m : service.getClass().getMethods()) {
            if (name.equals(m.getName()) && m.getParameterTypes().length == 0) { method = m; break; }
        }
        if (method == null) {
            for (Class<?> i : service.getClass().getInterfaces()) {
                try { method = i.getMethod(name); break; } catch (Throwable ignored) {}
            }
        }
        if (method == null) throw new NoSuchMethodException(name);
        method.setAccessible(true); return method.invoke(service);
    }

    private static float getServiceFloat(String name, float def) {
        try { Object v = serviceCall(name); return v instanceof Number ? ((Number) v).floatValue() : def; }
        catch (Throwable t) { return def; }
    }
    private static boolean getServiceBoolean(String name, boolean def) {
        try { Object v = serviceCall(name); return v instanceof Boolean ? (Boolean) v : def; }
        catch (Throwable t) { return def; }
    }
    private static float clamp(float x, float min, float max) {
        if (Float.isNaN(x) || Float.isInfinite(x)) return 1f;
        return Math.max(min, Math.min(max, x));
    }

    private interface UnsafeRunnable { void run() throws Throwable; }
    private static void safe(String what, UnsafeRunnable r) {
        try { r.run(); }
        catch (Throwable t) { XposedBridge.log(TAG + ": " + what + " hook skipped: " + t); }
    }
}
