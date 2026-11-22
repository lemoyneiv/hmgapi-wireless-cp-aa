package android.content;

import java.util.ArrayList;
import java.util.List;

public class IntentFilter {
    public final List<String> actions = new ArrayList<String>();
    public IntentFilter() {}
    public IntentFilter(String action) { actions.add(action); }
    public void addAction(String action) { actions.add(action); }
}
