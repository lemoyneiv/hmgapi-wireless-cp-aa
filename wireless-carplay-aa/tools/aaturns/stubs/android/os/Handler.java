package android.os;

import java.util.LinkedHashMap;
import java.util.Map;

/** host stand-in: records delayed runnables without firing them automatically */
public class Handler {
    public Runnable delayed;
    public final Map<Runnable, Long> scheduled = new LinkedHashMap<Runnable, Long>();

    public boolean post(Runnable r) { r.run(); return true; }

    public boolean postDelayed(Runnable r, long ms) {
        scheduled.put(r, Long.valueOf(ms));
        delayed = r;
        return true;
    }

    public void removeCallbacks(Runnable r) {
        scheduled.remove(r);
        if (delayed == r) {
            delayed = scheduled.isEmpty() ? null : scheduled.keySet().iterator().next();
        }
    }
}
