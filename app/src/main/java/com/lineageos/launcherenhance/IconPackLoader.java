package com.lineageos.launcherenhance;

import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Icon-pack parser shared by the provider-layer hook.
 *
 * Compatibility is intentionally modeled after Launcher3Customizer 1.0.1:
 * exact ComponentName matching, appfilter XML resource support, plus drawable.xml asset fallback.
 */
final class IconPackLoader {
    private static String cachedPackage = "";
    private static Resources cachedResources;
    private static Map<String, String> componentToDrawable = Collections.emptyMap();

    private IconPackLoader() {}

    static synchronized void invalidate() {
        cachedPackage = "";
        cachedResources = null;
        componentToDrawable = Collections.emptyMap();
    }

    /**
     * Legacy compatibility entry point used by use.apk's old BubbleTextView path.
     * Returning null disables that late UI-level replacement so the provider-layer result is the
     * single source of truth.
     */
    static Drawable getDrawable(Context launcherContext, String packPackage,
            ComponentName component) {
        return null;
    }

    /** Returns an exact component icon for IconProvider. Stock icon is kept when no match exists. */
    static Drawable getProviderDrawable(Context launcherContext, String packPackage,
            ComponentName component) {
        if (launcherContext == null || packPackage == null || packPackage.isEmpty()
                || component == null) return null;
        try {
            ensureLoaded(launcherContext, packPackage);
            if (cachedResources == null) return null;

            String drawableName = componentToDrawable.get(component.flattenToString());
            if (drawableName == null || drawableName.isEmpty()) return null;
            drawableName = sanitize(drawableName);

            int id = cachedResources.getIdentifier(drawableName, "drawable", packPackage);
            if (id == 0) id = cachedResources.getIdentifier(drawableName, "mipmap", packPackage);
            if (id == 0) return null;

            Drawable drawable = cachedResources.getDrawable(id, null);
            return drawable == null ? null : drawable.mutate();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static synchronized void ensureLoaded(Context launcherContext, String packPackage)
            throws Exception {
        if (packPackage.equals(cachedPackage) && cachedResources != null) return;

        Context packContext = launcherContext.createPackageContext(
                packPackage, Context.CONTEXT_IGNORE_SECURITY);
        Resources resources = packContext.getResources();
        Map<String, String> exact = new HashMap<>();
        boolean parsed = false;

        // Preferred Android icon-pack format.
        int appFilterId = resources.getIdentifier("appfilter", "xml", packPackage);
        if (appFilterId != 0) {
            XmlResourceParser parser = resources.getXml(appFilterId);
            try {
                parse(parser, exact);
                parsed = true;
            } finally {
                parser.close();
            }
        }

        // Some packs ship appfilter.xml only as an asset.
        if (!parsed) {
            parsed = parseAsset(packContext, "appfilter.xml", exact);
        }

        // Launcher3Customizer 1.0.1 specifically falls back to drawable.xml.
        if (!parsed) {
            parseAsset(packContext, "drawable.xml", exact);
        }

        cachedPackage = packPackage;
        cachedResources = resources;
        componentToDrawable = exact;
    }

    private static boolean parseAsset(Context packContext, String asset,
            Map<String, String> exact) {
        try (InputStream in = packContext.getAssets().open(asset)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(
                    "http://xmlpull.org/v1/doc/features.html#process-namespaces", false);
            parser.setInput(in, "utf-8");
            parse(parser, exact);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void parse(XmlPullParser parser, Map<String, String> exact) throws Exception {
        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && "item".equals(parser.getName())) {
                String componentValue = parser.getAttributeValue(null, "component");
                String drawable = parser.getAttributeValue(null, "drawable");
                if (componentValue != null && drawable != null) {
                    ComponentName component = parseComponent(componentValue);
                    if (component != null) {
                        exact.put(component.flattenToString(), drawable);
                    }
                }
            }
            event = parser.next();
        }
    }

    private static ComponentName parseComponent(String value) {
        String raw = value.trim();
        if (raw.startsWith("ComponentInfo{") && raw.endsWith("}")) {
            raw = raw.substring("ComponentInfo{".length(), raw.length() - 1);
        }
        return ComponentName.unflattenFromString(raw);
    }

    private static String sanitize(String value) {
        String out = value.trim();
        if (out.startsWith("@drawable/")) return out.substring(10);
        if (out.startsWith("@mipmap/")) return out.substring(8);
        return out;
    }
}
