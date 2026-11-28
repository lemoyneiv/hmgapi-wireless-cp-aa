package hmgapi.hook;

import android.content.Context;

public final class LoadedApp {
    public final String packageName;
    public final String processName;
    public final ClassLoader classLoader = LoadedApp.class.getClassLoader();
    public final Context context;

    public LoadedApp(String name, Context context) {
        this.packageName = name;
        this.processName = name;
        this.context = context;
    }
}
