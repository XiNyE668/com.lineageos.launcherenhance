package de.robv.android.xposed;
public final class XposedHelpers {
    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) { return null; }
    public static Class<?> findClass(String className, ClassLoader classLoader) { return null; }
    public static Object getObjectField(Object obj, String fieldName) { return null; }
    public static boolean getBooleanField(Object obj, String fieldName) { return false; }
    public static int getIntField(Object obj, String fieldName) { return 0; }
    public static float getFloatField(Object obj, String fieldName) { return 0f; }
    public static void setObjectField(Object obj, String fieldName, Object value) {}
    public static void setBooleanField(Object obj, String fieldName, boolean value) {}
    public static void setIntField(Object obj, String fieldName, int value) {}
    public static void setFloatField(Object obj, String fieldName, float value) {}
    public static Object callMethod(Object obj, String methodName, Object... args) { return null; }
    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) { return null; }
}
