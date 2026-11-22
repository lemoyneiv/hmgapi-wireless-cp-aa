package android.os;

public final class Handler {
    private static final java.util.ArrayDeque<Runnable> pending = new java.util.ArrayDeque<Runnable>();
    public Handler() {}
    public Handler(Looper looper) {}
    public boolean post(Runnable runnable) { pending.add(runnable); return true; }
    public boolean postDelayed(Runnable runnable, long delayMs) { pending.add(runnable); return true; }
    public void removeCallbacks(Runnable runnable) { pending.remove(runnable); }
    public static void drain() { while (!pending.isEmpty()) pending.remove().run(); }
}
