package android.os;

public final class Handler {
    public Handler() {}
    public Handler(Looper looper) {}
    public boolean post(Runnable r) { r.run(); return true; }
    public boolean postDelayed(Runnable r, long delayMs) { return true; }
    public void removeCallbacks(Runnable r) {}
}
