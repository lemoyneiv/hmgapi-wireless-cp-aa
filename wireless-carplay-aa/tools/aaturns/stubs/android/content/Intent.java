package android.content;

import java.util.LinkedHashMap;
import java.util.Map;

public class Intent {
    private final String action;
    private String pkg;
    private final Map<String, Object> extras = new LinkedHashMap<String, Object>();

    public Intent() { this.action = null; }
    public Intent(String action) { this.action = action; }
    public String getAction() { return action; }
    public Intent setPackage(String pkg) { this.pkg = pkg; return this; }
    public String getPackage() { return pkg; }
    public Intent putExtra(String k, String v) { extras.put(k, v); return this; }
    public Intent putExtra(String k, int v) { extras.put(k, Integer.valueOf(v)); return this; }
    public Intent putExtra(String k, boolean v) { extras.put(k, Boolean.valueOf(v)); return this; }
    public String getStringExtra(String k) { Object v = extras.get(k); return v instanceof String ? (String) v : null; }
    public int getIntExtra(String k, int def) { Object v = extras.get(k); return v instanceof Integer ? (Integer) v : def; }
    public boolean getBooleanExtra(String k, boolean def) { Object v = extras.get(k); return v instanceof Boolean ? (Boolean) v : def; }
    @Override
    public String toString() {
        return String.valueOf(action) + " pkg=" + pkg + " " + extras;
    }
}
