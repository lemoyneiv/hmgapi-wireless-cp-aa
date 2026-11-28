package android.content;

import java.util.ArrayList;
import java.util.List;

public final class IntentFilter {
    public final List<String> actions = new ArrayList<String>();

    public IntentFilter() {}

    public IntentFilter(String action) {
        actions.add(action);
    }

    public void addAction(String action) {
        actions.add(action);
    }

    public boolean matches(String action) {
        return action != null && actions.contains(action);
    }
}
