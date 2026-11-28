package android.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Context {
    public static final class Registration {
        public final BroadcastReceiver receiver;
        public final IntentFilter filter;

        Registration(BroadcastReceiver receiver, IntentFilter filter) {
            this.receiver = receiver;
            this.filter = filter;
        }
    }

    private final String packageName;
    private final Map<String, Context> bus;
    public final List<Registration> registrations = new ArrayList<Registration>();
    public final List<Intent> sentBroadcasts = new ArrayList<Intent>();

    public Context(String packageName, Map<String, Context> bus) {
        this.packageName = packageName;
        this.bus = bus;
        if (bus != null && packageName != null) {
            bus.put(packageName, this);
        }
    }

    public Context(String packageName) {
        this(packageName, new LinkedHashMap<String, Context>());
    }

    public Context getApplicationContext() {
        return this;
    }

    public Intent registerReceiver(BroadcastReceiver r, IntentFilter f) {
        if (r != null && f != null) {
            registrations.add(new Registration(r, f));
        }
        return null;
    }

    public void unregisterReceiver(BroadcastReceiver r) {
        for (int i = registrations.size() - 1; i >= 0; i--) {
            if (registrations.get(i).receiver == r) registrations.remove(i);
        }
    }

    public void sendBroadcast(Intent intent) {
        if (intent == null) return;
        sentBroadcasts.add(intent);
        if (bus == null) return;
        String targetPkg = intent.getPackage();
        if (targetPkg != null) {
            Context dst = bus.get(targetPkg);
            if (dst != null) dst.deliver(intent);
        } else {
            for (Context dst : new ArrayList<Context>(bus.values())) {
                dst.deliver(intent);
            }
        }
    }

    public void deliver(Intent intent) {
        if (intent == null) return;
        String act = intent.getAction();
        for (Registration reg : new ArrayList<Registration>(registrations)) {
            if (reg.filter.matches(act)) {
                reg.receiver.onReceive(this, intent);
            }
        }
    }

    public String getPackageName() {
        return packageName;
    }
}
