package hmgapi.hook;

public final class Points {
    public static boolean has(String point) { return false; }
    public static Class<?> cls(ClassLoader cl, String point) { return null; }
    public static java.lang.reflect.Constructor<?> ctor(ClassLoader cl, String point) { return null; }
    public static java.lang.reflect.Field field(ClassLoader cl, String point) { return null; }
}
