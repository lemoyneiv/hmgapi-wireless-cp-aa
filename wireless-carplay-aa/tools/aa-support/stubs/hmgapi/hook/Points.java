package hmgapi.hook;

public final class Points {
    public static final Object IMAGE = new Object(), ENUM = new Object();
    public static final int FOCUS_NATIVE = 17;
    public static final java.util.Map<Object, Object> receivers = new java.util.IdentityHashMap<Object, Object>();
    public static Object get(ClassLoader cl, String point, Object target) {
        if ("gpm.cluster_type.image".equals(point)) return IMAGE;
        if ("gpm.cluster_type.enum".equals(point)) return ENUM;
        if ("gpm.receiver.focus_native".equals(point)) return FOCUS_NATIVE;
        if ("gpm.navigation_status.receiver".equals(point)) {
            if (!receivers.containsKey(target)) throw new AssertionError("unregistered navigation status");
            return receivers.get(target);
        }
        throw new AssertionError("unexpected field: " + point);
    }
    public static final java.util.Set<String> availablePoints = new java.util.HashSet<String>();
    public static final java.util.Map<String, Class<?>> classes = new java.util.HashMap<String, Class<?>>();
    public static boolean has(String point) { return availablePoints.contains(point); }
    public static Class<?> cls(ClassLoader cl, String point) { return classes.get(point); }
    public static java.lang.reflect.Constructor<?> ctor(ClassLoader cl, String point) {
        Class<?> c = classes.get(point.substring(0, point.lastIndexOf('.')));
        if (c == null) return null;
        try {
            java.lang.reflect.Constructor<?> k = c.getDeclaredConstructor();
            k.setAccessible(true);
            return k;
        } catch (Exception e) {
            return null;
        }
    }
    public static Object call(ClassLoader cl, String point, Object target, Object... args) { return null; }
    public static java.lang.reflect.Field field(ClassLoader cl, String point) {
        Class<?> c = classes.get("framework.navi_tbt_trap_info");
        if (c != null && "framework.navi_tbt_trap_info.bar_graph_level".equals(point)) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField("barGraphLevel");
                f.setAccessible(true);
                return f;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
}
