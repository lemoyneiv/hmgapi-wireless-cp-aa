package android.content;

import java.util.ArrayList;
import java.util.List;

public class Context {
    public final List<BroadcastReceiver> receivers = new ArrayList<BroadcastReceiver>();
    public final List<Intent> broadcasts = new ArrayList<Intent>();

    public Intent registerReceiver(BroadcastReceiver r, IntentFilter f) {
        if (r != null) receivers.add(r);
        return null;
    }

    public void unregisterReceiver(BroadcastReceiver r) {
        receivers.remove(r);
    }

    public void sendBroadcast(Intent i) {
        if (i != null) broadcasts.add(i);
    }

    public void deliver(Intent i) {
        for (BroadcastReceiver r : new ArrayList<BroadcastReceiver>(receivers)) {
            r.onReceive(this, i);
        }
    }
}
