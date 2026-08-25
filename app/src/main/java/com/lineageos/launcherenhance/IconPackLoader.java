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

final class IconPackLoader {
    private static String cachedPackage = "";
    private static Resources cachedResources;
    private static Map<String, String> componentToDrawable = Collections.emptyMap();
    private static Map<String, String> packageFallback = Collections.emptyMap();
    private IconPackLoader() {}
    static synchronized void invalidate() { cachedPackage = ""; cachedResources = null; componentToDrawable = Collections.emptyMap(); packageFallback = Collections.emptyMap(); }

    static Drawable getDrawable(Context launcherContext, String packPackage, ComponentName component) {
        if (launcherContext == null || packPackage == null || packPackage.isEmpty() || component == null) return null;
        try {
            ensureLoaded(launcherContext, packPackage);
            if (cachedResources == null) return null;
            String drawableName = componentToDrawable.get(component.flattenToString());
            if (drawableName == null) drawableName = packageFallback.get(component.getPackageName());
            if (drawableName == null || drawableName.isEmpty()) return null;
            drawableName = sanitize(drawableName);
            int id = cachedResources.getIdentifier(drawableName, "drawable", packPackage);
            if (id == 0) id = cachedResources.getIdentifier(drawableName, "mipmap", packPackage);
            if (id == 0) return null;
            Drawable d = cachedResources.getDrawable(id, null);
            return d == null ? null : d.mutate();
        } catch (Throwable ignored) { return null; }
    }

    private static synchronized void ensureLoaded(Context launcherContext, String packPackage) throws Exception {
        if (packPackage.equals(cachedPackage) && cachedResources != null) return;
        Context packContext = launcherContext.createPackageContext(packPackage, Context.CONTEXT_IGNORE_SECURITY);
        Resources res = packContext.getResources();
        Map<String, String> exact = new HashMap<>();
        Map<String, String> fallback = new HashMap<>();
        boolean parsed = false;
        int id = res.getIdentifier("appfilter", "xml", packPackage);
        if (id != 0) {
            XmlResourceParser parser = res.getXml(id);
            try { parse(parser, exact, fallback); parsed = true; } finally { parser.close(); }
        }
        if (!parsed) {
            try (InputStream in = packContext.getAssets().open("appfilter.xml")) {
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(in, "utf-8");
                parse(parser, exact, fallback);
            } catch (Throwable ignored) {}
        }
        cachedPackage = packPackage;
        cachedResources = res;
        componentToDrawable = exact;
        packageFallback = fallback;
    }

    private static void parse(XmlPullParser parser, Map<String, String> exact, Map<String, String> fallback) throws Exception {
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (event != XmlPullParser.START_TAG || !"item".equals(parser.getName())) continue;
            String componentValue = parser.getAttributeValue(null, "component");
            String drawable = parser.getAttributeValue(null, "drawable");
            if (componentValue == null || drawable == null) continue;
            ComponentName c = parseComponent(componentValue);
            if (c == null) continue;
            exact.put(c.flattenToString(), drawable);
            fallback.putIfAbsent(c.getPackageName(), drawable);
        }
    }
    private static ComponentName parseComponent(String value) {
        String raw = value.trim();
        if (raw.startsWith("ComponentInfo{") && raw.endsWith("}")) raw = raw.substring(14, raw.length() - 1);
        return ComponentName.unflattenFromString(raw);
    }
    private static String sanitize(String value) {
        String out = value.trim();
        if (out.startsWith("@drawable/")) return out.substring(10);
        if (out.startsWith("@mipmap/")) return out.substring(8);
        return out;
    }
}
