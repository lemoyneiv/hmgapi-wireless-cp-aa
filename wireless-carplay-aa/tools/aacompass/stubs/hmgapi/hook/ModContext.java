package hmgapi.hook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ModContext {
    public final Map<String, Boolean> settings = new LinkedHashMap<String, Boolean>();
    public final Map<String, MethodHook> hooks = new LinkedHashMap<String, MethodHook>();
    public final List<String> logs = new ArrayList<String>();
    public final List<String> errors = new ArrayList<String>();
    public volatile boolean modEnabled = true;

    public boolean enabled() {
        return modEnabled;
    }

    public boolean settingBoolean(String key, boolean fallback) {
        Boolean v = settings.get(key);
        return v != null ? v.booleanValue() : fallback;
    }

    public java.lang.reflect.Member hook(ClassLoader cl, String point, final MethodHook hook) {
        final MethodHook prev = hooks.get(point);
        if (prev == null) {
            hooks.put(point, hook);
        } else {
            hooks.put(point, new MethodHook() {
                @Override
                protected void beforeHookedMethod(Param p) throws Throwable {
                    prev.beforeHookedMethod(p);
                    if (!p.isReturnEarly()) hook.beforeHookedMethod(p);
                }

                @Override
                protected void afterHookedMethod(Param p) throws Throwable {
                    hook.afterHookedMethod(p);
                    prev.afterHookedMethod(p);
                }
            });
        }
        return null;
    }

    public void log(String message) {
        synchronized (logs) {
            logs.add(message);
        }
    }

    public void error(String message, Throwable t) {
        synchronized (errors) {
            errors.add(message + (t != null ? ": " + t : ""));
        }
    }
}
