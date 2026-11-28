package android.content;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Intent {
    public static final int FLAG_RECEIVER_REGISTERED_ONLY = 0x40000000;

    private final String action;
    private String pkg;
    private int flags;
    private final Map<String, Object> extras = new LinkedHashMap<String, Object>();

    public Intent() {
        this.action = null;
    }

    public Intent(String action) {
        this.action = action;
    }

    public String getAction() {
        return action;
    }

    public Intent setPackage(String pkg) {
        this.pkg = pkg;
        return this;
    }

    public String getPackage() {
        return pkg;
    }

    public Intent addFlags(int f) {
        this.flags |= f;
        return this;
    }

    public int getFlags() {
        return flags;
    }

    public boolean hasExtra(String key) {
        return extras.containsKey(key);
    }

    public Intent putExtra(String key, String value) {
        extras.put(key, value);
        return this;
    }

    public Intent putExtra(String key, int value) {
        extras.put(key, Integer.valueOf(value));
        return this;
    }

    public Intent putExtra(String key, float value) {
        extras.put(key, Float.valueOf(value));
        return this;
    }

    public Intent putExtra(String key, boolean value) {
        extras.put(key, Boolean.valueOf(value));
        return this;
    }

    public String getStringExtra(String key) {
        Object v = extras.get(key);
        return v instanceof String ? (String) v : null;
    }

    public int getIntExtra(String key, int fallback) {
        Object v = extras.get(key);
        return v instanceof Integer ? ((Integer) v).intValue() : fallback;
    }

    public float getFloatExtra(String key, float fallback) {
        Object v = extras.get(key);
        return v instanceof Float ? ((Float) v).floatValue() : fallback;
    }

    public boolean getBooleanExtra(String key, boolean fallback) {
        Object v = extras.get(key);
        return v instanceof Boolean ? ((Boolean) v).booleanValue() : fallback;
    }
}
